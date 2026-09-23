package com.mailsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mailsystem.dto.PageResult;
import com.mailsystem.dto.SyncMailEvent;
import com.mailsystem.entity.Attachment;
import com.mailsystem.entity.Mail;
import com.mailsystem.entity.MailAccount;
import com.mailsystem.entity.MailStatus;
import com.mailsystem.entity.User;
import com.mailsystem.event.MailAnalysisEvent;
import com.mailsystem.event.MailSentEvent;
import com.mailsystem.mapper.AttachmentMapper;
import com.mailsystem.mapper.MailMapper;
import com.mailsystem.mapper.MailStatusMapper;
import com.mailsystem.mapper.UserMapper;
import com.mailsystem.service.MailAccountService;
import com.mailsystem.service.MailCacheService;
import com.mailsystem.service.MailService;
import com.mailsystem.service.OutboundRelayService;
import com.mailsystem.websocket.MailNotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 邮件服务实现
 */
@Service
public class MailServiceImpl implements MailService {

    @Autowired
    private MailMapper mailMapper;

    @Autowired
    private MailStatusMapper mailStatusMapper;

    @Autowired
    private AttachmentMapper attachmentMapper;

    @Autowired
    private UserMapper userMapper;

    /** WebSocket 实时推送 */
    @Autowired(required = false)
    private MailNotificationService notificationService;

    /**
     * 邮件缓存。
     * <p>
     * 不再直接注入 RedisTemplate：驱逐用的键模式必须与写入时逐字一致，
     * 两组操作分散在两个类里迟早会发散（改了键格式 → 驱逐静默失效）。
     * 缓存键的构造与驱逐现在都收在 {@code MailCacheService} 里。
     * </p>
     */
    @Autowired
    private MailCacheService cacheService;

    @Autowired
    private MailAccountService mailAccountService;

    /**
     * 项目级发信中继。仅在选中本域地址（{@code CLOUDFLARE} 类型账户）时才需要 ——
     * 用户自带 SMTP 的账户不经过它。
     */
    @Autowired
    private OutboundRelayService outboundRelayService;

    /** 发布"邮件已入库"事件：外部投递与分析都在事务提交后进行 */
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 判定一个地址是否"像"外部邮箱。
     * <p>
     * 放宽到"有 @、有点、无空格"即可：真正的合法性由 SMTP 服务器判定，
     * 这里只用于把"用户打错的站内地址"和"外部地址"区分开。过严的正则
     * 会把合法但少见的地址误判成错误输入。
     * </p>
     */
    private static final Pattern EXTERNAL_EMAIL_PATTERN =
            Pattern.compile("^[^@\\s,]+@[^@\\s,]+\\.[^@\\s,]+$");

    /** external_to 列宽，超长时截断（投递用的是事件里的完整列表，不受影响） */
    private static final int MAX_EXTERNAL_TO_CHARS = 256;

    @Override
    @Transactional
    public Mail sendMail(Long senderId, String receiverEmails, String ccEmails, String subject, String body, List<Long> attachmentIds) {
        User sender = userMapper.selectById(senderId);
        if (sender == null) {
            throw new RuntimeException("发件人不存在");
        }

        Recipients to = resolveRecipients(receiverEmails);
        Recipients cc = resolveRecipients(ccEmails);

        // To 与 Cc 里的外部地址合并投递：同一封信不该为了抄送再发一次 SMTP
        LinkedHashSet<String> externalAddresses = new LinkedHashSet<>(to.externalAddresses);
        externalAddresses.addAll(cc.externalAddresses);

        MailAccount sendAccount = requireSendAccountIfNeeded(senderId, externalAddresses);

        Mail mail = new Mail();
        mail.setSenderId(senderId);
        mail.setSenderEmail(sender.getEmail());
        mail.setReceiverIds(to.internalIdString());
        mail.setCcIds(cc.internalIdString());
        mail.setSubject(subject);
        mail.setBody(body);
        mail.setSendTime(LocalDateTime.now());
        mail.setStatus(1);
        applyExternalFields(mail, externalAddresses, sendAccount);
        mailMapper.insert(mail);

        // 绑定附件
        bindAttachments(mail.getId(), attachmentIds);

        // 收件人与抄送去重后统一建状态记录：同一个人既在收件人又在抄送时，
        // mail_status 的 uk_mail_user 唯一键会让第二次插入直接报错
        List<Long> internalRecipients = mergeDedup(to.internalIds, cc.internalIds);
        for (Long receiverId : internalRecipients) {
            createMailStatus(mail.getId(), receiverId);
            // WebSocket 实时推送新邮件通知
            pushNewMailNotification(receiverId, mail);
        }

        // 外部投递与"站内已送达"的处理都放在事务提交之后
        publishMailSent(mail, internalRecipients, externalAddresses, sendAccount);

        // 智能分析（LLM 主 + 规则兜底）同样挪到事务提交之后
        publishAnalysisRequested(mail, internalRecipients);

        // 驱逐相关用户的 Redis 缓存
        cacheService.evictAll(senderId);
        for (Long rid : internalRecipients) { cacheService.evictAll(rid); }

        return mail;
    }

    @Override
    public List<Mail> receiveMails(Long userId) {
        List<Mail> mails = mailMapper.selectInbox(userId);
        for (Mail mail : mails) {
            resolveIdNames(mail);
        }
        return mails;
    }

    @Override
    public PageResult<Mail> listMails(Long userId, Integer type, int page, int pageSize) {
        if (type == null) type = 1;

        // 先试缓存；未命中或缓存损坏时 MailCacheService 返回 null，降级查库
        PageResult<Mail> cached = cacheService.getPage(userId, type, page, pageSize);
        if (cached != null) {
            return cached;
        }

        Page<Mail> pageObj = new Page<>(page, pageSize);
        IPage<Mail> result;
        switch (type) {
            case 2:
                result = mailMapper.selectSentPage(pageObj, userId);
                break;
            case 3:
                result = mailMapper.selectTrashPage(pageObj, userId);
                break;
            case 4:
                // 草稿箱不分页或简单分页
                QueryWrapper<Mail> wrapper = new QueryWrapper<>();
                wrapper.eq("sender_id", userId).eq("status", 2).orderByDesc("send_time");
                Page<Mail> draftPage = mailMapper.selectPage(pageObj, wrapper);
                result = draftPage;
                break;
            default:
                result = mailMapper.selectInboxPage(pageObj, userId);
        }

        // 解析昵称
        for (Mail mail : result.getRecords()) {
            resolveIdNames(mail);
        }

        PageResult<Mail> pageResult = new PageResult<>(
                result.getRecords(), result.getTotal(), page, pageSize);

        cacheService.putPage(userId, type, page, pageSize, pageResult);

        return pageResult;
    }

    /**
     * 邮件详情。
     * <p>
     * 走 {@code selectDetailForUser} 而不是 {@code selectById}，一次解决两件事：
     * </p>
     * <ol>
     *   <li><b>归属校验</b>：原实现只按 id 查，<b>完全忽略 userId</b>，
     *       任何登录用户改一下 URL 里的 id 就能读到别人的邮件（越权读取）</li>
     *   <li><b>按收件人覆盖分析结论</b>：同一封邮件发给多人时，
     *       分类/摘要/优先级应当各人不同</li>
     * </ol>
     * <p>
     * 不属于该用户时返回"邮件不存在"而不是"无权访问"：后者会泄露
     * "这个 id 确实存在"这一信息，让人可以枚举出系统里有多少封邮件。
     * </p>
     */
    @Override
    public Mail getMailDetail(Long mailId, Long userId) {
        Mail mail = mailMapper.selectDetailForUser(mailId, userId);
        if (mail == null) {
            throw new RuntimeException("邮件不存在");
        }
        resolveIdNames(mail);
        return mail;
    }

    @Override
    public List<Attachment> getAttachments(Long mailId) {
        return attachmentMapper.selectByMailId(mailId);
    }

    @Override
    @Transactional
    public void markAsRead(Long mailId, Long userId) {
        MailStatus status = mailStatusMapper.selectByMailIdAndUserId(mailId, userId);
        if (status == null) {
            return;
        }
        if (status.getIsRead() == 0) {
            mailStatusMapper.markAsRead(mailId, userId);
            // 驱逐缓存
            cacheService.evictAll(userId);
            // 清除未读数缓存
            cacheService.evictUnread(userId);
            // WebSocket 推送
            if (notificationService != null) {
                notificationService.notifyUser(userId, "MAIL_READ",
                        Collections.singletonMap("mailId", mailId));
            }
        }
    }

    @Override
    @Transactional
    public void markAsUnread(Long mailId, Long userId) {
        if (userId == null) {
            System.err.println("[markAsUnread] userId is null for mailId=" + mailId);
            return;
        }
        MailStatus status = mailStatusMapper.selectByMailIdAndUserId(mailId, userId);
        if (status == null) {
            // 如果收件人侧没有状态记录，创建一个未读记录
            mailStatusMapper.insertStatus(mailId, userId, 0, 0, 0, null);
        } else if (status.getIsRead() == 1) {
            mailStatusMapper.markAsUnread(mailId, userId);
        }
        cacheService.evictAll(userId);
    }

    @Override
    @Transactional
    public boolean toggleRead(Long mailId, Long userId) {
        if (userId == null) {
            System.err.println("[toggleRead] userId is null for mailId=" + mailId);
            return false;
        }
        MailStatus status = mailStatusMapper.selectByMailIdAndUserId(mailId, userId);
        if (status == null) {
            // 无状态记录，视为未读 → 标记已读
            mailStatusMapper.insertStatus(mailId, userId, 1, 0, 0, LocalDateTime.now());
            cacheService.evictAll(userId);
            return true;
        }
        if (status.getIsRead() == 0) {
            mailStatusMapper.markAsRead(mailId, userId);
            cacheService.evictAll(userId);
            cacheService.evictUnread(userId);
            return true;
        } else {
            mailStatusMapper.markAsUnread(mailId, userId);
            cacheService.evictAll(userId);
            cacheService.evictUnread(userId);
            return false;
        }
    }

    @Override
    @Transactional
    public void deleteMail(Long mailId, Long userId) {
        MailStatus status = mailStatusMapper.selectByMailIdAndUserId(mailId, userId);
        if (status != null) {
            mailStatusMapper.softDelete(mailId, userId);
        } else {
            Mail mail = mailMapper.selectById(mailId);
            if (mail != null && userId.equals(mail.getSenderId())) {
                MailStatus ms = new MailStatus();
                ms.setMailId(mailId);
                ms.setUserId(userId);
                ms.setIsRead(1);
                ms.setIsDeleted(1);
                ms.setSyncStatus(0);
                mailStatusMapper.insert(ms);
            }
        }
        cacheService.evictAll(userId);
    }

    @Override
    public PageResult<Mail> searchMails(Long userId, String keyword, int page, int pageSize) {
        Page<Mail> pageObj = new Page<>(page, pageSize);
        IPage<Mail> result = mailMapper.searchMailsPage(pageObj, userId, keyword);
        for (Mail mail : result.getRecords()) {
            resolveIdNames(mail);
        }
        return new PageResult<>(result.getRecords(), result.getTotal(), page, pageSize);
    }

    @Override
    public int getUnreadCount(Long userId) {
        Integer cached = cacheService.getUnread(userId);
        if (cached != null) {
            return cached;
        }
        int count = mailStatusMapper.countUnread(userId);
        cacheService.putUnread(userId, count);
        return count;
    }

    @Override
    public MailStatus getMailStatus(Long mailId, Long userId) {
        return mailStatusMapper.selectByMailIdAndUserId(mailId, userId);
    }

    @Override
    @Transactional
    public void restoreMail(Long mailId, Long userId) {
        MailStatus status = mailStatusMapper.selectByMailIdAndUserId(mailId, userId);
        if (status == null || status.getIsDeleted() == 0) {
            throw new RuntimeException("邮件不在垃圾箱中");
        }
        Mail mail = mailMapper.selectById(mailId);
        if (mail != null && userId.equals(mail.getSenderId())) {
            mailStatusMapper.deleteByMailIdAndUserId(mailId, userId);
        } else {
            mailStatusMapper.restoreDelete(mailId, userId);
        }
        cacheService.evictAll(userId);
    }

    @Override
    @Transactional
    public void permanentDeleteMail(Long mailId, Long userId) {
        MailStatus status = mailStatusMapper.selectByMailIdAndUserId(mailId, userId);
        if (status == null || status.getIsDeleted() == 0) {
            throw new RuntimeException("邮件不在垃圾箱中");
        }
        Mail mail = mailMapper.selectById(mailId);
        if (mail != null && userId.equals(mail.getSenderId())) {
            mailStatusMapper.deleteByMailIdAndUserId(mailId, userId);
            mail.setStatus(0);
            mailMapper.updateById(mail);
        } else {
            mailStatusMapper.deleteByMailIdAndUserId(mailId, userId);
        }
        cacheService.evictAll(userId);
    }

    @Override
    @Transactional
    public void emptyTrash(Long userId) {
        QueryWrapper<MailStatus> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).eq("is_deleted", 1);
        List<MailStatus> trashItems = mailStatusMapper.selectList(wrapper);
        for (MailStatus item : trashItems) {
            Long mailId = item.getMailId();
            Mail mail = mailMapper.selectById(mailId);
            if (mail != null && userId.equals(mail.getSenderId())) {
                mailStatusMapper.deleteByMailIdAndUserId(mailId, userId);
                mail.setStatus(0);
                mailMapper.updateById(mail);
            } else {
                mailStatusMapper.deleteByMailIdAndUserId(mailId, userId);
            }
        }
        cacheService.evictAll(userId);
    }

    @Override
    @Transactional
    public Mail saveDraft(Long userId, String receiverEmails, String ccEmails, String subject, String body, List<Long> attachmentIds) {
        User sender = userMapper.selectById(userId);
        if (sender == null) {
            throw new RuntimeException("用户不存在");
        }

        Mail mail = new Mail();
        mail.setSenderId(userId);
        mail.setSenderEmail(sender.getEmail());
        mail.setReceiverIds(receiverEmails);
        mail.setCcIds(ccEmails != null && !ccEmails.isEmpty() ? ccEmails : null);
        mail.setSubject(subject != null ? subject : "");
        mail.setBody(body);
        mail.setSendTime(LocalDateTime.now());
        mail.setStatus(2);
        mailMapper.insert(mail);

        bindAttachments(mail.getId(), attachmentIds);

        return mail;
    }

    @Override
    @Transactional
    public Mail updateDraft(Long draftId, Long userId, String receiverEmails, String ccEmails, String subject, String body, List<Long> attachmentIds) {
        Mail mail = mailMapper.selectById(draftId);
        if (mail == null) throw new RuntimeException("草稿不存在");
        if (!userId.equals(mail.getSenderId())) throw new RuntimeException("无权修改此草稿");
        if (mail.getStatus() != 2) throw new RuntimeException("该邮件不是草稿");

        mail.setReceiverIds(receiverEmails);
        mail.setCcIds(ccEmails != null && !ccEmails.isEmpty() ? ccEmails : null);
        mail.setSubject(subject);
        mail.setBody(body);
        mail.setSendTime(LocalDateTime.now());
        mailMapper.updateById(mail);

        // 重新绑定附件
        if (attachmentIds != null) {
            attachmentMapper.unbindByMailId(draftId);
            bindAttachments(draftId, attachmentIds);
        }

        return mail;
    }

    @Override
    @Transactional
    public void deleteDraft(Long draftId, Long userId) {
        Mail mail = mailMapper.selectById(draftId);
        if (mail == null) throw new RuntimeException("草稿不存在");
        if (!userId.equals(mail.getSenderId())) throw new RuntimeException("无权删除此草稿");
        if (mail.getStatus() != 2) throw new RuntimeException("该邮件不是草稿");
        attachmentMapper.unbindByMailId(draftId);
        mailMapper.deleteById(draftId);
    }

    @Override
    @Transactional
    public Mail sendDraft(Long draftId, Long userId) {
        Mail mail = mailMapper.selectById(draftId);
        if (mail == null) throw new RuntimeException("草稿不存在");
        if (!userId.equals(mail.getSenderId())) throw new RuntimeException("无权发送此草稿");
        if (mail.getStatus() != 2) throw new RuntimeException("该邮件不是草稿，无法发送");

        // 草稿里的 receiver_ids/cc_ids 存的是地址原文（见 saveDraft），
        // 到发送这一刻才解析成站内 ID 与外部地址
        Recipients to = resolveRecipients(mail.getReceiverIds());
        Recipients cc = resolveRecipients(mail.getCcIds());

        LinkedHashSet<String> externalAddresses = new LinkedHashSet<>(to.externalAddresses);
        externalAddresses.addAll(cc.externalAddresses);

        MailAccount sendAccount = requireSendAccountIfNeeded(userId, externalAddresses);

        mail.setReceiverIds(to.internalIdString());
        mail.setCcIds(cc.internalIdString());
        mail.setStatus(1);
        mail.setSendTime(LocalDateTime.now());
        applyExternalFields(mail, externalAddresses, sendAccount);
        mailMapper.updateById(mail);

        // 创建状态记录（收件人与抄送去重）
        List<Long> internalRecipients = mergeDedup(to.internalIds, cc.internalIds);
        for (Long receiverId : internalRecipients) {
            createMailStatus(mail.getId(), receiverId);
            pushNewMailNotification(receiverId, mail);
        }

        publishMailSent(mail, internalRecipients, externalAddresses, sendAccount);

        publishAnalysisRequested(mail, internalRecipients);
        for (Long receiverId : internalRecipients) { cacheService.evictAll(receiverId); }
        cacheService.evictAll(userId);
        return mail;
    }

    @Override
    @Transactional
    public Mail forwardMail(Long forwarderId, Long originalMailId, String receiverEmails, String ccEmails, String additionalBody) {
        Mail original = mailMapper.selectById(originalMailId);
        if (original == null) throw new RuntimeException("原始邮件不存在");

        User forwarder = userMapper.selectById(forwarderId);
        if (forwarder == null) throw new RuntimeException("转发人不存在");

        // 构建转发主题
        String fwSubject = (original.getSubject() != null && original.getSubject().startsWith("Fw:"))
                ? original.getSubject() : "Fw: " + original.getSubject();

        // 构建转发正文
        StringBuilder body = new StringBuilder();
        if (additionalBody != null && !additionalBody.isEmpty()) {
            body.append(additionalBody).append("\n\n");
        }
        body.append("---------- 原始邮件 ----------\n");
        body.append("发件人: ").append(original.getSenderEmail()).append("\n");
        body.append("发送时间: ").append(original.getSendTime() != null
                ? original.getSendTime().format(DT_FMT) : "").append("\n");
        body.append("收件人: ").append(resolveRecipientEmails(original)).append("\n");
        body.append("主题: ").append(original.getSubject()).append("\n\n");
        body.append(original.getBody());

        // 复制原邮件附件
        List<Long> newAttachmentIds = copyAttachments(originalMailId);

        // 发送邮件
        return sendMail(forwarderId, receiverEmails, ccEmails, fwSubject, body.toString(), newAttachmentIds);
    }

    @Override
    @Transactional
    public void batchDelete(List<Long> mailIds, Long userId) {
        for (Long mailId : mailIds) {
            deleteMail(mailId, userId);
        }
        cacheService.evictAll(userId);
    }

    @Override
    @Transactional
    public void batchPermanentDelete(List<Long> mailIds, Long userId) {
        for (Long mailId : mailIds) {
            permanentDeleteMail(mailId, userId);
        }
        cacheService.evictAll(userId);
    }

    @Override
    public List<SyncMailEvent> syncChanges(Long userId, String since) {
        List<MailStatus> changes = mailStatusMapper.selectChangesSince(userId, since);
        List<SyncMailEvent> events = new ArrayList<>();
        for (MailStatus ms : changes) {
            String eventType;
            if (ms.getIsDeleted() == 1 && ms.getIsRead() == 0) {
                eventType = "DELETE";
            } else if (ms.getIsDeleted() == 0 && ms.getIsRead() == 1 && ms.getReadTime() != null) {
                eventType = "READ";
            } else {
                eventType = "NEW";
            }
            LocalDateTime eventTime = ms.getUpdatedTime() != null ? ms.getUpdatedTime()
                    : (ms.getReadTime() != null ? ms.getReadTime() : LocalDateTime.now());
            events.add(new SyncMailEvent(ms.getMailId(), eventType, eventTime));
        }
        return events;
    }

    // ==================== 私有方法 ====================

    private void createMailStatus(Long mailId, Long userId) {
        MailStatus ms = new MailStatus();
        ms.setMailId(mailId);
        ms.setUserId(userId);
        ms.setIsRead(0);
        ms.setIsDeleted(0);
        ms.setSyncStatus(0);
        mailStatusMapper.insert(ms);
    }

    private void bindAttachments(Long mailId, List<Long> attachmentIds) {
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            for (Long attId : attachmentIds) {
                Attachment att = attachmentMapper.selectById(attId);
                if (att != null) {
                    att.setMailId(mailId);
                    attachmentMapper.updateById(att);
                }
            }
        }
    }

    private List<Long> copyAttachments(Long originalMailId) {
        List<Attachment> originalAtts = attachmentMapper.selectByMailId(originalMailId);
        List<Long> newIds = new ArrayList<>();
        for (Attachment att : originalAtts) {
            Attachment copy = new Attachment();
            copy.setFileName(att.getFileName());
            copy.setFilePath(att.getFilePath());
            copy.setFileSize(att.getFileSize());
            copy.setContentType(att.getContentType());
            copy.setUploadTime(LocalDateTime.now());
            attachmentMapper.insert(copy);
            newIds.add(copy.getId());
        }
        return newIds;
    }

    private void pushNewMailNotification(Long receiverId, Mail mail) {
        if (notificationService != null) {
            notificationService.notifyNewMail(receiverId, mail.getId(), mail.getSenderEmail(), mail.getSubject());
        }
    }

    /**
     * 解析收件地址串，把每个地址归类为"站内收件人"或"外部地址"。
     *
     * <h3>判定顺序</h3>
     * <ol>
     *   <li>能在 {@code user} 表里按邮箱精确命中 → 站内收件人</li>
     *   <li>否则形如合法邮箱 → 外部地址，走 SMTP</li>
     *   <li>否则 → 报错。保持与改动前一致的语义：
     *       用户把站内地址打错时仍得到"收件人不存在"，而不是被当成外部地址
     *       白白发出去（那封信会石沉大海）</li>
     * </ol>
     * <p>
     * 站内优先是刻意的：某个站内用户的邮箱恰好长得像外部地址（比如
     * {@code zhangsan@qq.com} 注册了本站），应当投到站内信箱而不是绕一圈
     * 从外面寄回来。
     * </p>
     */
    private Recipients resolveRecipients(String emails) {
        Recipients result = new Recipients();
        if (emails == null || emails.trim().isEmpty()) {
            return result;
        }
        for (String raw : emails.split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            User user = userMapper.selectByEmail(token);
            if (user != null) {
                result.internalIds.add(user.getId());
            } else if (EXTERNAL_EMAIL_PATTERN.matcher(token).matches()) {
                result.externalAddresses.add(token);
            } else {
                throw new RuntimeException("收件人不存在: " + token);
            }
        }
        return result;
    }

    /**
     * 存在外部收件人时，确认发件人有可用的发信通道。
     * <p>
     * 在写库<b>之前</b>确认：否则会留下一封 {@code external_to} 有值、
     * 却永远发不出去、用户也不知道为什么的邮件。
     * </p>
     * <p>
     * 需要查两件事，因为本系统有两条互不依赖的发信通道：
     * </p>
     * <ul>
     *   <li><b>账户本身能发</b> —— 用户绑定的外部邮箱（自带 SMTP）</li>
     *   <li><b>项目能发</b> —— 本域地址（{@code CLOUDFLARE}）依赖项目级中继，
     *       而中继未配置时账户看起来一切正常，信却出不去</li>
     * </ul>
     * <p>
     * 第一项由 {@code findPrimaryForSend} 的 SQL 保证（它同时接受这两类账户）；
     * 第二项只能在这里判断，因为"中继是否可用"是运行时配置，不在数据库里。
     * </p>
     */
    private MailAccount requireSendAccountIfNeeded(Long senderId, Set<String> externalAddresses) {
        if (externalAddresses.isEmpty()) {
            return null;
        }
        MailAccount account = mailAccountService.findPrimaryForSend(senderId);
        if (account == null) {
            throw new RuntimeException("收件人包含外部邮箱，请先在「个人设置 - 邮箱账户」中绑定你的邮箱账户，"
                    + "或领取一个本系统域名下的地址（无需授权码）");
        }
        // 本域地址自己不带 SMTP 配置，它的发信能力完全取决于项目级中继是否就绪。
        // 不在这里拦住的话，用户会看到"发送成功"，几秒后收到一条 WebSocket 失败通知
        if (account.isCloudflareRouting() && !outboundRelayService.isAvailable()) {
            throw new RuntimeException("你当前使用的是本系统域名下的地址（" + account.getEmailAddress()
                    + "），它依赖本系统的发信中继，而中继当前未配置，暂时发不出外部邮件。"
                    + "请改用你已绑定的外部邮箱，或联系管理员配置发信中继（见 Cloudflare.md「发信」一节）");
        }
        return account;
    }

    /**
     * 写入外部投递相关字段。无外部收件人时保持为 null，
     * 这样"纯站内邮件"在库里的形态与改动前完全一致。
     */
    private void applyExternalFields(Mail mail, Set<String> externalAddresses, MailAccount account) {
        if (externalAddresses.isEmpty()) {
            mail.setDirection(Mail.DIRECTION_INTERNAL);
            return;
        }
        mail.setDirection(Mail.DIRECTION_EXTERNAL);
        mail.setExternalTo(truncate(String.join(",", externalAddresses), MAX_EXTERNAL_TO_CHARS));
        mail.setExternalStatus(Mail.EXTERNAL_STATUS_PENDING);
        // 记下本次用的账户：投递在事务提交后进行，那时用户可能已经改了默认账户
        mail.setAccountId(account.getId());
    }

    /**
     * 发布"邮件已入库"事件。
     * <p>
     * 监听方（{@code MailDeliveryListener}）在事务提交后执行 SMTP 投递 ——
     * 把网络往返留在事务里会占着数据库连接，且事务若回滚，外部收件人已经
     * 收到了这封信，出现"外面收到了、站内没有"的不一致。
     * </p>
     */
    private void publishMailSent(Mail mail, List<Long> internalRecipients,
                                 Set<String> externalAddresses, MailAccount account) {
        if (internalRecipients.isEmpty() && externalAddresses.isEmpty()) {
            return;
        }
        eventPublisher.publishEvent(new MailSentEvent(
                mail.getId(), mail.getSenderId(), mail.getSenderEmail(),
                mail.getSubject(), mail.getBody(),
                internalRecipients, new ArrayList<>(externalAddresses),
                account == null ? null : account.getId()));
    }

    /**
     * 合并收件人与抄送人 ID 并去重，保持出现顺序。
     * <p>
     * 去重是必需的：{@code mail_status} 有 {@code uk_mail_user(mail_id, user_id)}
     * 唯一键，同一个人既在收件人又在抄送时，第二行会直接插入失败。
     * </p>
     */
    private List<Long> mergeDedup(Collection<Long> first, Collection<Long> second) {
        LinkedHashSet<Long> merged = new LinkedHashSet<>(first);
        merged.addAll(second);
        return new ArrayList<>(merged);
    }

    private void resolveIdNames(Mail mail) {
        // 外部收件人没有站内 ID，但发件箱的"收件人"一栏得显示出来，
        // 因此把 external_to 追加在站内昵称之后
        String internalNames = resolveIdsToNames(mail.getReceiverIds());
        String externalTo = mail.getExternalTo();
        if (externalTo == null || externalTo.trim().isEmpty()) {
            mail.setReceiverNames(internalNames);
        } else if (internalNames == null || internalNames.isEmpty()) {
            mail.setReceiverNames(externalTo);
        } else {
            mail.setReceiverNames(internalNames + ", " + externalTo);
        }
        mail.setCcNames(resolveIdsToNames(mail.getCcIds()));
    }

    /** 收件人展示串：站内邮箱 + 外部地址（转发时引用原邮件用） */
    private String resolveRecipientEmails(Mail mail) {
        String internal = resolveReceiverEmails(mail.getReceiverIds());
        String externalTo = mail.getExternalTo();
        if (externalTo == null || externalTo.trim().isEmpty()) {
            return internal;
        }
        return internal.isEmpty() ? externalTo : internal + ", " + externalTo;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 地址解析结果：站内收件人 ID 与外部邮箱地址分列。
     * <p>
     * 两列都用 {@link LinkedHashSet} 去重并保持输入顺序 ——
     * 用户重复填同一个地址是常见操作，不该产生两条 {@code mail_status}。
     * </p>
     */
    private static class Recipients {
        final LinkedHashSet<Long> internalIds = new LinkedHashSet<>();
        final LinkedHashSet<String> externalAddresses = new LinkedHashSet<>();

        /** 逗号分隔的站内 ID 串；无站内收件人时返回 null（与旧的存法一致） */
        String internalIdString() {
            if (internalIds.isEmpty()) {
                return null;
            }
            return internalIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
    }

    private String resolveIdsToNames(String ids) {
        if (ids == null || ids.trim().isEmpty()) return null;
        return Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(id -> {
                    try {
                        User user = userMapper.selectById(Long.valueOf(id));
                        if (user != null) {
                            return user.getNickname() != null && !user.getNickname().isEmpty()
                                    ? user.getNickname() : user.getEmail();
                        }
                        return "未知用户(" + id + ")";
                    } catch (NumberFormatException e) {
                        return id;
                    }
                })
                .collect(Collectors.joining(","));
    }

    private String resolveReceiverEmails(String receiverIds) {
        if (receiverIds == null || receiverIds.trim().isEmpty()) return "";
        return Arrays.stream(receiverIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(id -> {
                    try {
                        User user = userMapper.selectById(Long.valueOf(id));
                        return user != null ? user.getEmail() : id;
                    } catch (NumberFormatException e) {
                        return id;
                    }
                })
                .collect(Collectors.joining(", "));
    }

    /**
     * 发布"邮件已入库、请分析"事件。
     * <p>
     * 分析<b>不再</b>在这里同步调用（原 {@code executePlugins} 就是那么做的）：
     * 调用点在 {@code @Transactional} 之内，而分析可能包含一次数秒的 LLM 往返 ——
     * 那会让数据库连接陪着等，并且事务若回滚，分析结果就指向了一封不存在的邮件。
     * </p>
     * <p>
     * 监听方 {@code MailAnalysisListener} 标了
     * {@code @TransactionalEventListener(AFTER_COMMIT)}，因此分析一定发生在
     * 提交之后；{@code @Async("analysisExecutor")} 则保证发信响应不等分析。
     * </p>
     */
    private void publishAnalysisRequested(Mail mail, List<Long> internalRecipients) {
        if (internalRecipients == null || internalRecipients.isEmpty()) {
            // 只发给外部地址的邮件没有站内收件人，也就没有"谁的结论"可言
            return;
        }
        eventPublisher.publishEvent(new MailAnalysisEvent(
                mail.getId(), mail.getSenderId(), internalRecipients));
    }
}

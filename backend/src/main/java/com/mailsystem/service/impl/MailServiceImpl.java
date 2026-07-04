package com.mailsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mailsystem.dto.PageResult;
import com.mailsystem.dto.SyncMailEvent;
import com.mailsystem.entity.Attachment;
import com.mailsystem.entity.Mail;
import com.mailsystem.entity.MailStatus;
import com.mailsystem.entity.User;
import com.mailsystem.mapper.AttachmentMapper;
import com.mailsystem.mapper.MailMapper;
import com.mailsystem.mapper.MailStatusMapper;
import com.mailsystem.mapper.UserMapper;
import com.mailsystem.plugin.PluginInterface;
import com.mailsystem.plugin.dynamic.DynamicPluginRegistry;
import com.mailsystem.service.MailService;
import com.mailsystem.websocket.MailNotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
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

    /** 注入所有编译时插件实现 */
    @Autowired(required = false)
    private List<PluginInterface> plugins;

    /** 动态插件注册中心 */
    @Autowired
    private DynamicPluginRegistry dynamicPluginRegistry;

    /** WebSocket 实时推送 */
    @Autowired(required = false)
    private MailNotificationService notificationService;

    /** Redis 缓存 */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    @Transactional
    public Mail sendMail(Long senderId, String receiverEmails, String ccEmails, String subject, String body, List<Long> attachmentIds) {
        User sender = userMapper.selectById(senderId);
        if (sender == null) {
            throw new RuntimeException("发件人不存在");
        }

        String receiverIds = resolveEmailsToIdString(receiverEmails);
        String ccIds = resolveEmailsToIdString(ccEmails);

        Mail mail = new Mail();
        mail.setSenderId(senderId);
        mail.setSenderEmail(sender.getEmail());
        mail.setReceiverIds(receiverIds);
        mail.setCcIds(ccIds != null && !ccIds.isEmpty() ? ccIds : null);
        mail.setSubject(subject);
        mail.setBody(body);
        mail.setSendTime(LocalDateTime.now());
        mail.setStatus(1);
        mailMapper.insert(mail);

        // 绑定附件
        bindAttachments(mail.getId(), attachmentIds);

        // 为每个收件人创建邮件状态记录
        List<Long> receiverIdList = parseIdList(receiverIds);
        for (Long receiverId : receiverIdList) {
            createMailStatus(mail.getId(), receiverId);
            // WebSocket 实时推送新邮件通知
            pushNewMailNotification(receiverId, mail);
        }

        // 为每个抄送人创建邮件状态记录
        if (ccIds != null && !ccIds.isEmpty()) {
            List<Long> ccIdList = parseIdList(ccIds);
            for (Long ccId : ccIdList) {
                createMailStatus(mail.getId(), ccId);
                pushNewMailNotification(ccId, mail);
            }
        }

        // 执行智能插件处理
        executePlugins(mail);

        // 驱逐相关用户的 Redis 缓存
        evictUserCache(senderId);
        for (Long rid : receiverIdList) { evictUserCache(rid); }
        if (ccIds != null && !ccIds.isEmpty()) {
            for (Long cid : parseIdList(ccIds)) { evictUserCache(cid); }
        }

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

        // 尝试从 Redis 获取缓存（处理序列化兼容性问题）
        String cacheKey = cacheKey(userId, type, page, pageSize);
        PageResult<Mail> cached = getCachedPageResult(cacheKey);
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

        // 写入 Redis 缓存（5分钟过期）
        redisTemplate.opsForValue().set(cacheKey, pageResult, Duration.ofMinutes(5));

        return pageResult;
    }

    @Override
    public Mail getMailDetail(Long mailId, Long userId) {
        Mail mail = mailMapper.selectById(mailId);
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
            evictUserCache(userId);
            // 清除未读数缓存
            evictUnreadCache(userId);
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
        evictUserCache(userId);
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
            evictUserCache(userId);
            return true;
        }
        if (status.getIsRead() == 0) {
            mailStatusMapper.markAsRead(mailId, userId);
            evictUserCache(userId);
            evictUnreadCache(userId);
            return true;
        } else {
            mailStatusMapper.markAsUnread(mailId, userId);
            evictUserCache(userId);
            evictUnreadCache(userId);
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
            if (mail != null && mail.getSenderId().equals(userId)) {
                MailStatus ms = new MailStatus();
                ms.setMailId(mailId);
                ms.setUserId(userId);
                ms.setIsRead(1);
                ms.setIsDeleted(1);
                ms.setSyncStatus(0);
                mailStatusMapper.insert(ms);
            }
        }
        evictUserCache(userId);
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
        String cacheKey = "mail:unread:" + userId;
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return Integer.parseInt(cached);
        }
        int count = mailStatusMapper.countUnread(userId);
        stringRedisTemplate.opsForValue().set(cacheKey, String.valueOf(count), 2, TimeUnit.MINUTES);
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
        if (mail != null && mail.getSenderId().equals(userId)) {
            mailStatusMapper.deleteByMailIdAndUserId(mailId, userId);
        } else {
            mailStatusMapper.restoreDelete(mailId, userId);
        }
        evictUserCache(userId);
    }

    @Override
    @Transactional
    public void permanentDeleteMail(Long mailId, Long userId) {
        MailStatus status = mailStatusMapper.selectByMailIdAndUserId(mailId, userId);
        if (status == null || status.getIsDeleted() == 0) {
            throw new RuntimeException("邮件不在垃圾箱中");
        }
        Mail mail = mailMapper.selectById(mailId);
        if (mail != null && mail.getSenderId().equals(userId)) {
            mailStatusMapper.deleteByMailIdAndUserId(mailId, userId);
            mail.setStatus(0);
            mailMapper.updateById(mail);
        } else {
            mailStatusMapper.deleteByMailIdAndUserId(mailId, userId);
        }
        evictUserCache(userId);
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
            if (mail != null && mail.getSenderId().equals(userId)) {
                mailStatusMapper.deleteByMailIdAndUserId(mailId, userId);
                mail.setStatus(0);
                mailMapper.updateById(mail);
            } else {
                mailStatusMapper.deleteByMailIdAndUserId(mailId, userId);
            }
        }
        evictUserCache(userId);
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
        if (!mail.getSenderId().equals(userId)) throw new RuntimeException("无权修改此草稿");
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
        if (!mail.getSenderId().equals(userId)) throw new RuntimeException("无权删除此草稿");
        if (mail.getStatus() != 2) throw new RuntimeException("该邮件不是草稿");
        attachmentMapper.unbindByMailId(draftId);
        mailMapper.deleteById(draftId);
    }

    @Override
    @Transactional
    public Mail sendDraft(Long draftId, Long userId) {
        Mail mail = mailMapper.selectById(draftId);
        if (mail == null) throw new RuntimeException("草稿不存在");
        if (!mail.getSenderId().equals(userId)) throw new RuntimeException("无权发送此草稿");
        if (mail.getStatus() != 2) throw new RuntimeException("该邮件不是草稿，无法发送");

        String receiverIds = resolveEmailsToIdString(mail.getReceiverIds());
        String ccIds = resolveEmailsToIdString(mail.getCcIds());

        mail.setReceiverIds(receiverIds);
        mail.setCcIds(ccIds != null && !ccIds.isEmpty() ? ccIds : null);
        mail.setStatus(1);
        mail.setSendTime(LocalDateTime.now());
        mailMapper.updateById(mail);

        // 创建状态记录
        if (receiverIds != null) {
            for (Long receiverId : parseIdList(receiverIds)) {
                createMailStatus(mail.getId(), receiverId);
                pushNewMailNotification(receiverId, mail);
                evictUserCache(receiverId);
            }
        }
        if (ccIds != null && !ccIds.isEmpty()) {
            for (Long ccId : parseIdList(ccIds)) {
                createMailStatus(mail.getId(), ccId);
                pushNewMailNotification(ccId, mail);
                evictUserCache(ccId);
            }
        }

        executePlugins(mail);
        evictUserCache(userId);
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
        body.append("收件人: ").append(original.getReceiverIds() != null
                ? resolveReceiverEmails(original.getReceiverIds()) : "").append("\n");
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
        evictUserCache(userId);
    }

    @Override
    @Transactional
    public void batchPermanentDelete(List<Long> mailIds, Long userId) {
        for (Long mailId : mailIds) {
            permanentDeleteMail(mailId, userId);
        }
        evictUserCache(userId);
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

    private String resolveEmailsToIdString(String emails) {
        if (emails == null || emails.trim().isEmpty()) return null;
        return Arrays.stream(emails.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(email -> {
                    User user = userMapper.selectByEmail(email);
                    if (user == null) throw new RuntimeException("用户不存在: " + email);
                    return String.valueOf(user.getId());
                })
                .collect(Collectors.joining(","));
    }

    private List<Long> parseIdList(String ids) {
        if (ids == null || ids.trim().isEmpty()) return Collections.emptyList();
        return Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .collect(Collectors.toList());
    }

    private void resolveIdNames(Mail mail) {
        mail.setReceiverNames(resolveIdsToNames(mail.getReceiverIds()));
        mail.setCcNames(resolveIdsToNames(mail.getCcIds()));
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

    private void executePlugins(Mail mail) {
        // 编译时插件
        if (plugins != null && !plugins.isEmpty()) {
            for (PluginInterface plugin : plugins) {
                try {
                    if (plugin.isEnabled()) {
                        plugin.process(mail);
                    }
                } catch (Exception e) {
                    System.err.println("插件 [" + plugin.getName() + "] 执行异常: " + e.getMessage());
                }
            }
        }
        // 动态加载的插件
        List<PluginInterface> dynamicPlugins = dynamicPluginRegistry.getAllPlugins();
        for (PluginInterface plugin : dynamicPlugins) {
            try {
                if (plugin.isEnabled()) {
                    plugin.process(mail);
                }
            } catch (Exception e) {
                System.err.println("动态插件 [" + plugin.getName() + "] 执行异常: " + e.getMessage());
            }
        }
    }

    // ==================== Redis 缓存工具方法 ====================

    private String cacheKey(Long userId, int type, int page, int pageSize) {
        return "mail:list:" + userId + ":" + type + ":" + page + ":" + pageSize;
    }

    /**
     * 安全读取 Redis 缓存的 PageResult
     * 处理 GenericJackson2JsonRedisSerializer 反序列化时的类型兼容问题：
     * 旧缓存数据可能无法还原为 PageResult 类型，此时返回 null 降级查 DB
     */
    @SuppressWarnings("unchecked")
    private PageResult<Mail> getCachedPageResult(String cacheKey) {
        try {
            Object obj = redisTemplate.opsForValue().get(cacheKey);
            if (obj == null) return null;
            if (obj instanceof PageResult) {
                return (PageResult<Mail>) obj;
            }
            // 缓存数据损坏（LinkedHashMap 等），删除并降级
            redisTemplate.delete(cacheKey);
            return null;
        } catch (Exception e) {
            // 反序列化失败时删除损坏缓存
            redisTemplate.delete(cacheKey);
            return null;
        }
    }

    private void evictUserCache(Long userId) {
        // 模糊删除用户所有邮件列表缓存
        String pattern = "mail:list:" + userId + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        evictUnreadCache(userId);
    }

    private void evictUnreadCache(Long userId) {
        stringRedisTemplate.delete("mail:unread:" + userId);
    }
}

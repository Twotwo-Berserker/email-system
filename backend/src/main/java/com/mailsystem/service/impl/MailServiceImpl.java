package com.mailsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mailsystem.entity.Attachment;
import com.mailsystem.entity.Mail;
import com.mailsystem.entity.MailStatus;
import com.mailsystem.entity.User;
import com.mailsystem.mapper.AttachmentMapper;
import com.mailsystem.mapper.MailMapper;
import com.mailsystem.mapper.MailStatusMapper;
import com.mailsystem.mapper.UserMapper;
import com.mailsystem.plugin.PluginInterface;
import com.mailsystem.service.MailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
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

    /** 注入所有插件实现 */
    @Autowired(required = false)
    private List<PluginInterface> plugins;

    @Override
    @Transactional
    public Mail sendMail(Long senderId, String receiverEmails, String ccEmails, String subject, String body, List<Long> attachmentIds) {
        // 获取发件人信息
        User sender = userMapper.selectById(senderId);
        if (sender == null) {
            throw new RuntimeException("发件人不存在");
        }

        // 将邮箱地址转换为用户ID（前端传来的是邮箱，数据库存的是ID）
        String receiverIds = resolveEmailsToIdString(receiverEmails);
        String ccIds = resolveEmailsToIdString(ccEmails);

        // 保存邮件
        Mail mail = new Mail();
        mail.setSenderId(senderId);
        mail.setSenderEmail(sender.getEmail());
        mail.setReceiverIds(receiverIds);
        mail.setCcIds(ccIds != null && !ccIds.isEmpty() ? ccIds : null);
        mail.setSubject(subject);
        mail.setBody(body);
        mail.setSendTime(LocalDateTime.now());
        mail.setStatus(1); // 正常发送
        mailMapper.insert(mail);

        // 绑定附件
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            for (Long attId : attachmentIds) {
                Attachment att = attachmentMapper.selectById(attId);
                if (att != null) {
                    att.setMailId(mail.getId());
                    attachmentMapper.updateById(att);
                }
            }
        }

        // 为每个收件人创建邮件状态记录
        List<Long> receiverIdList = parseIdList(receiverIds);
        for (Long receiverId : receiverIdList) {
            createMailStatus(mail.getId(), receiverId);
        }

        // 为每个抄送人创建邮件状态记录
        if (ccIds != null && !ccIds.isEmpty()) {
            List<Long> ccIdList = parseIdList(ccIds);
            for (Long ccId : ccIdList) {
                createMailStatus(mail.getId(), ccId);
            }
        }

        // 执行智能插件处理
        executePlugins(mail);

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
    public List<Mail> listMails(Long userId, Integer type) {
        // type: 1=收件箱, 2=已发送, 3=垃圾箱, 4=草稿
        if (type == null) type = 1;
        List<Mail> mails;
        switch (type) {
            case 2:
                mails = mailMapper.selectSent(userId);
                break;
            case 3:
                mails = mailMapper.selectTrash(userId);
                break;
            case 4:
                // 草稿箱
                QueryWrapper<Mail> wrapper = new QueryWrapper<>();
                wrapper.eq("sender_id", userId).eq("status", 2);
                mails = mailMapper.selectList(wrapper);
                break;
            default:
                mails = mailMapper.selectInbox(userId);
        }
        // 为所有邮件解析收件人/抄送人昵称
        for (Mail mail : mails) {
            resolveIdNames(mail);
        }
        return mails;
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
            // 发件人查看自己的邮件
            return;
        }
        if (status.getIsRead() == 0) {
            mailStatusMapper.markAsRead(mailId, userId);
        }
    }

    @Override
    @Transactional
    public void deleteMail(Long mailId, Long userId) {
        MailStatus status = mailStatusMapper.selectByMailIdAndUserId(mailId, userId);
        if (status != null) {
            // 收件人/抄送人删除：软删除自己的 mail_status 记录
            mailStatusMapper.softDelete(mailId, userId);
        } else {
            // 发件人删除自己已发送的邮件：创建一条 mail_status 记录标记为已删除
            // 这样只影响发件人自己的视图，不影响收件人的收件箱
            Mail mail = mailMapper.selectById(mailId);
            if (mail != null && mail.getSenderId().equals(userId)) {
                MailStatus ms = new MailStatus();
                ms.setMailId(mailId);
                ms.setUserId(userId);
                ms.setIsRead(1);    // 发件人已读自己的邮件
                ms.setIsDeleted(1); // 标记为已删除
                ms.setSyncStatus(0);
                mailStatusMapper.insert(ms);
            }
        }
    }

    @Override
    public List<Mail> searchMails(Long userId, String keyword) {
        List<Mail> mails = mailMapper.searchMails(userId, keyword);
        for (Mail mail : mails) {
            resolveIdNames(mail);
        }
        return mails;
    }

    @Override
    public int getUnreadCount(Long userId) {
        return mailStatusMapper.countUnread(userId);
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
        // 发件人恢复：删除 mail_status 记录（该记录是删除时创建的，恢复后应回到无记录状态）
        if (mail != null && mail.getSenderId().equals(userId)) {
            mailStatusMapper.deleteByMailIdAndUserId(mailId, userId);
        } else {
            // 收件人/抄送人恢复：将 is_deleted 设回 0
            mailStatusMapper.restoreDelete(mailId, userId);
        }
    }

    @Override
    @Transactional
    public void permanentDeleteMail(Long mailId, Long userId) {
        MailStatus status = mailStatusMapper.selectByMailIdAndUserId(mailId, userId);
        if (status == null || status.getIsDeleted() == 0) {
            throw new RuntimeException("邮件不在垃圾箱中");
        }

        Mail mail = mailMapper.selectById(mailId);
        // 发件人彻底删除：删除 mail_status 后邮件会重新出现在"已发送"中，
        // 因此需要将 mail.status 设为 0（发件人侧删除），彻底隐藏
        if (mail != null && mail.getSenderId().equals(userId)) {
            mailStatusMapper.deleteByMailIdAndUserId(mailId, userId);
            mail.setStatus(0);
            mailMapper.updateById(mail);
        } else {
            // 收件人/抄送人彻底删除：删除 mail_status 记录即可
            mailStatusMapper.deleteByMailIdAndUserId(mailId, userId);
        }
    }

    @Override
    @Transactional
    public void emptyTrash(Long userId) {
        // 查询该用户垃圾箱中的所有记录
        QueryWrapper<MailStatus> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).eq("is_deleted", 1);
        List<MailStatus> trashItems = mailStatusMapper.selectList(wrapper);
        for (MailStatus item : trashItems) {
            Long mailId = item.getMailId();
            Mail mail = mailMapper.selectById(mailId);
            // 发件人清空：需要同时标记 mail.status=0
            if (mail != null && mail.getSenderId().equals(userId)) {
                mailStatusMapper.deleteByMailIdAndUserId(mailId, userId);
                mail.setStatus(0);
                mailMapper.updateById(mail);
            } else {
                // 收件人清空
                mailStatusMapper.deleteByMailIdAndUserId(mailId, userId);
            }
        }
    }

    @Override
    @Transactional
    public Mail saveDraft(Long userId, String receiverEmails, String ccEmails, String subject, String body, List<Long> attachmentIds) {
        User sender = userMapper.selectById(userId);
        if (sender == null) {
            throw new RuntimeException("用户不存在");
        }

        // 草稿直接存储原始邮箱字符串，不解析为用户ID
        Mail mail = new Mail();
        mail.setSenderId(userId);
        mail.setSenderEmail(sender.getEmail());
        mail.setReceiverIds(receiverEmails); // 存储原始邮箱字符串
        mail.setCcIds(ccEmails != null && !ccEmails.isEmpty() ? ccEmails : null);
        mail.setSubject(subject != null ? subject : "");
        mail.setBody(body);
        mail.setSendTime(LocalDateTime.now());
        mail.setStatus(2); // 草稿状态
        mailMapper.insert(mail);

        // 绑定附件
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            for (Long attId : attachmentIds) {
                Attachment att = attachmentMapper.selectById(attId);
                if (att != null) {
                    att.setMailId(mail.getId());
                    attachmentMapper.updateById(att);
                }
            }
        }

        return mail;
    }

    @Override
    @Transactional
    public Mail updateDraft(Long draftId, Long userId, String receiverEmails, String ccEmails, String subject, String body, List<Long> attachmentIds) {
        Mail mail = mailMapper.selectById(draftId);
        if (mail == null) {
            throw new RuntimeException("草稿不存在");
        }
        if (!mail.getSenderId().equals(userId)) {
            throw new RuntimeException("无权修改此草稿");
        }
        if (mail.getStatus() != 2) {
            throw new RuntimeException("该邮件不是草稿");
        }

        mail.setReceiverIds(receiverEmails);
        mail.setCcIds(ccEmails != null && !ccEmails.isEmpty() ? ccEmails : null);
        mail.setSubject(subject);
        mail.setBody(body);
        mail.setSendTime(LocalDateTime.now()); // 更新保存时间
        mailMapper.updateById(mail);

        // 重新绑定附件：先解绑旧附件，再绑定新附件
        if (attachmentIds != null) {
            // 解绑该草稿的所有旧附件
            attachmentMapper.unbindByMailId(draftId);
            // 绑定新附件（可能为空）
            for (Long attId : attachmentIds) {
                Attachment att = attachmentMapper.selectById(attId);
                if (att != null) {
                    att.setMailId(draftId);
                    attachmentMapper.updateById(att);
                }
            }
        }

        return mail;
    }

    @Override
    @Transactional
    public void deleteDraft(Long draftId, Long userId) {
        Mail mail = mailMapper.selectById(draftId);
        if (mail == null) {
            throw new RuntimeException("草稿不存在");
        }
        if (!mail.getSenderId().equals(userId)) {
            throw new RuntimeException("无权删除此草稿");
        }
        if (mail.getStatus() != 2) {
            throw new RuntimeException("该邮件不是草稿");
        }
        // 解绑附件
        attachmentMapper.unbindByMailId(draftId);
        // 删除草稿
        mailMapper.deleteById(draftId);
    }

    @Override
    @Transactional
    public Mail sendDraft(Long draftId, Long userId) {
        Mail mail = mailMapper.selectById(draftId);
        if (mail == null) {
            throw new RuntimeException("草稿不存在");
        }
        if (!mail.getSenderId().equals(userId)) {
            throw new RuntimeException("无权发送此草稿");
        }
        if (mail.getStatus() != 2) {
            throw new RuntimeException("该邮件不是草稿，无法发送");
        }

        // 将草稿中的邮箱字符串解析为用户ID
        String receiverIds = resolveEmailsToIdString(mail.getReceiverIds());
        String ccIds = resolveEmailsToIdString(mail.getCcIds());

        // 更新邮件为已发送状态
        mail.setReceiverIds(receiverIds);
        mail.setCcIds(ccIds != null && !ccIds.isEmpty() ? ccIds : null);
        mail.setStatus(1);
        mail.setSendTime(LocalDateTime.now());
        mailMapper.updateById(mail);

        // 为收件人和抄送人创建邮件状态记录
        if (receiverIds != null) {
            List<Long> receiverIdList = parseIdList(receiverIds);
            for (Long receiverId : receiverIdList) {
                createMailStatus(mail.getId(), receiverId);
            }
        }

        if (ccIds != null && !ccIds.isEmpty()) {
            List<Long> ccIdList = parseIdList(ccIds);
            for (Long ccId : ccIdList) {
                createMailStatus(mail.getId(), ccId);
            }
        }

        // 执行智能插件处理
        executePlugins(mail);

        return mail;
    }

    // ==================== 私有方法 ====================

    /**
     * 创建邮件状态记录
     */
    private void createMailStatus(Long mailId, Long userId) {
        MailStatus ms = new MailStatus();
        ms.setMailId(mailId);
        ms.setUserId(userId);
        ms.setIsRead(0);
        ms.setIsDeleted(0);
        ms.setSyncStatus(0);
        mailStatusMapper.insert(ms);
    }

    /**
     * 将逗号分隔的邮箱字符串转换为逗号分隔的用户ID字符串
     * 前端传来的是 "aa@bb.com,cc@dd.com"，数据库存的是 "1,2"
     */
    private String resolveEmailsToIdString(String emails) {
        if (emails == null || emails.trim().isEmpty()) {
            return null;
        }
        return Arrays.stream(emails.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(email -> {
                    User user = userMapper.selectByEmail(email);
                    if (user == null) {
                        throw new RuntimeException("用户不存在: " + email);
                    }
                    return String.valueOf(user.getId());
                })
                .collect(Collectors.joining(","));
    }

    /**
     * 解析ID列表字符串 -> List<Long>
     */
    private List<Long> parseIdList(String ids) {
        return Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .collect(Collectors.toList());
    }

    /**
     * 将邮件中的 receiverIds / ccIds 解析为昵称，填充到 receiverNames / ccNames 字段
     */
    private void resolveIdNames(Mail mail) {
        mail.setReceiverNames(resolveIdsToNames(mail.getReceiverIds()));
        mail.setCcNames(resolveIdsToNames(mail.getCcIds()));
    }

    /**
     * 将逗号分隔的用户ID字符串解析为逗号分隔的用户昵称
     * "1,2" -> "Alice,Bob"
     * 对于草稿，receiver_ids 可能直接存储邮箱字符串，此时直接返回邮箱
     */
    private String resolveIdsToNames(String ids) {
        if (ids == null || ids.trim().isEmpty()) {
            return null;
        }
        return Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(id -> {
                    try {
                        User user = userMapper.selectById(Long.valueOf(id));
                        if (user != null) {
                            return user.getNickname() != null && !user.getNickname().isEmpty()
                                    ? user.getNickname()
                                    : user.getEmail();
                        }
                        return "未知用户(" + id + ")";
                    } catch (NumberFormatException e) {
                        // 草稿中存储的是原始邮箱字符串，直接返回
                        return id;
                    }
                })
                .collect(Collectors.joining(","));
    }

    /**
     * 执行所有已启用的插件
     */
    private void executePlugins(Mail mail) {
        if (plugins != null && !plugins.isEmpty()) {
            for (PluginInterface plugin : plugins) {
                try {
                    if (plugin.isEnabled()) {
                        plugin.process(mail);
                    }
                } catch (Exception e) {
                    // 插件失败不影响邮件发送
                    System.err.println("插件 [" + plugin.getName() + "] 执行异常: " + e.getMessage());
                }
            }
        }
    }
}

package com.mailsystem.service;

import com.mailsystem.entity.Attachment;
import com.mailsystem.entity.Mail;
import com.mailsystem.entity.MailStatus;

import java.util.List;

/**
 * 邮件服务接口
 */
public interface MailService {

    /**
     * 发送邮件
     */
    /**
     * 发送邮件
     * @param receiverEmails 收件人邮箱，逗号分隔
     * @param ccEmails 抄送人邮箱，逗号分隔（可为空）
     */
    Mail sendMail(Long senderId, String receiverEmails, String ccEmails, String subject, String body, List<Long> attachmentIds);

    /**
     * 拉取收件箱邮件列表
     */
    List<Mail> receiveMails(Long userId);

    /**
     * 获取邮件列表（分页）
     */
    List<Mail> listMails(Long userId, Integer type);

    /**
     * 获取邮件详情
     */
    Mail getMailDetail(Long mailId, Long userId);

    /**
     * 获取邮件附件列表
     */
    List<Attachment> getAttachments(Long mailId);

    /**
     * 标记已读
     */
    void markAsRead(Long mailId, Long userId);

    /**
     * 删除邮件（软删除）
     */
    void deleteMail(Long mailId, Long userId);

    /**
     * 搜索邮件
     */
    List<Mail> searchMails(Long userId, String keyword);

    /**
     * 获取未读邮件数量
     */
    int getUnreadCount(Long userId);

    /**
     * 获取邮件状态
     */
    MailStatus getMailStatus(Long mailId, Long userId);

    /**
     * 从垃圾箱恢复邮件
     */
    void restoreMail(Long mailId, Long userId);

    /**
     * 永久删除邮件（从垃圾箱彻底删除）
     */
    void permanentDeleteMail(Long mailId, Long userId);

    /**
     * 清空垃圾箱
     */
    void emptyTrash(Long userId);

    /**
     * 保存草稿
     */
    Mail saveDraft(Long userId, String receiverEmails, String ccEmails, String subject, String body, List<Long> attachmentIds);

    /**
     * 更新草稿
     */
    Mail updateDraft(Long draftId, Long userId, String receiverEmails, String ccEmails, String subject, String body, List<Long> attachmentIds);

    /**
     * 发送草稿（将草稿转为正式邮件并发送）
     */
    Mail sendDraft(Long draftId, Long userId);

    /**
     * 删除草稿
     */
    void deleteDraft(Long draftId, Long userId);
}

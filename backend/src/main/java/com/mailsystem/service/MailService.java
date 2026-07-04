package com.mailsystem.service;

import com.mailsystem.dto.PageResult;
import com.mailsystem.dto.SyncMailEvent;
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
     * @param type 1=收件箱, 2=已发送, 3=垃圾箱, 4=草稿
     * @param page 页码，从1开始
     * @param pageSize 每页大小
     */
    PageResult<Mail> listMails(Long userId, Integer type, int page, int pageSize);

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
     * 搜索邮件（分页）
     */
    PageResult<Mail> searchMails(Long userId, String keyword, int page, int pageSize);

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

    /**
     * 转发邮件
     * @param forwarderId 转发人用户ID
     * @param originalMailId 原始邮件ID
     * @param receiverEmails 收件人邮箱
     * @param ccEmails 抄送人邮箱
     * @param additionalBody 附加说明
     */
    Mail forwardMail(Long forwarderId, Long originalMailId, String receiverEmails, String ccEmails, String additionalBody);

    /**
     * 批量软删除邮件
     */
    void batchDelete(List<Long> mailIds, Long userId);

    /**
     * 批量永久删除邮件
     */
    void batchPermanentDelete(List<Long> mailIds, Long userId);

    /**
     * 获取增量同步变更事件
     * @param userId 用户ID
     * @param since 起始时间 (yyyy-MM-dd HH:mm:ss)
     */
    List<SyncMailEvent> syncChanges(Long userId, String since);
}

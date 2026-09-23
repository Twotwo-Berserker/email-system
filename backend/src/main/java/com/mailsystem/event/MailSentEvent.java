package com.mailsystem.event;

import java.util.Collections;
import java.util.List;

/**
 * 邮件已入库事件（事务提交后触发）
 * <p>
 * 由 {@code MailServiceImpl} 在 {@code sendMail}/{@code sendDraft} 中发布，
 * 监听方在<b>事务提交之后</b>执行，用于两件不该发生在事务里的事：
 * </p>
 * <ol>
 *   <li>通过 SMTP 投递到外部地址（网络往返，不能让数据库连接陪着等）</li>
 *   <li>LLM 分析（同上，且耗时更长）</li>
 * </ol>
 *
 * <h3>为什么事件里带正文快照</h3>
 * <p>
 * 监听方拿到 {@code mailId} 后再回表查当然也可以（AFTER_COMMIT 保证已提交），
 * 但带上 {@code subject}/{@code body} 有两个好处：站内投递这一绝大多数场景下
 * 监听方完全不需要再读库；以及避免将来有人把监听器改成 {@code BEFORE_COMMIT}
 * 时静默读到未提交的数据。代价只是每个事件多持有一份正文引用。
 * </p>
 * <p>
 * 本类<b>不可变</b>：异步消费者与发布方分处不同线程，可变的事件对象
 * 会成为难以复现的竞态来源。
 * </p>
 */
public class MailSentEvent {

    private final Long mailId;
    private final Long senderId;
    private final String senderEmail;
    private final String subject;
    private final String body;

    /** 站内收件人 ID（已去重，含抄送人） */
    private final List<Long> internalRecipients;

    /** 外部收件人地址（已去重） */
    private final List<String> externalRecipients;

    /** 邮件使用的发信账户，可能为 null（未绑定时） */
    private final Long accountId;

    public MailSentEvent(Long mailId, Long senderId, String senderEmail,
                         String subject, String body,
                         List<Long> internalRecipients, List<String> externalRecipients,
                         Long accountId) {
        this.mailId = mailId;
        this.senderId = senderId;
        this.senderEmail = senderEmail;
        this.subject = subject;
        this.body = body;
        this.internalRecipients = internalRecipients == null
                ? Collections.emptyList() : Collections.unmodifiableList(internalRecipients);
        this.externalRecipients = externalRecipients == null
                ? Collections.emptyList() : Collections.unmodifiableList(externalRecipients);
        this.accountId = accountId;
    }

    public Long getMailId() {
        return mailId;
    }

    public Long getSenderId() {
        return senderId;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public List<Long> getInternalRecipients() {
        return internalRecipients;
    }

    public List<String> getExternalRecipients() {
        return externalRecipients;
    }

    public Long getAccountId() {
        return accountId;
    }

    public boolean hasExternalRecipients() {
        return !externalRecipients.isEmpty();
    }
}

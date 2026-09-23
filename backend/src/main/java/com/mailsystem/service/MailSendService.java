package com.mailsystem.service;

import com.mailsystem.entity.Mail;

import java.util.List;

/**
 * 外部邮件投递服务（SMTP）
 * <p>
 * <b>刻意不在发信事务内调用</b>：SMTP 是一次网络往返（可能是几秒），
 * 放在 {@code sendMail} 的 {@code @Transactional} 里会让数据库连接被
 * 网络延迟占住。调用方是 {@code MailDeliveryListener} ——
 * 事务提交后才执行。
 * </p>
 */
public interface MailSendService {

    /**
     * 投递已落库的邮件到外部地址，并回写 {@code external_status}。
     * <p>
     * 本方法自行捕获异常并写入失败原因，<b>不向调用方抛出</b> ——
     * 外发失败不应该让站内收件人拿不到邮件，也不应该让已提交的事务回滚。
     * </p>
     *
     * @param mail                已落库的邮件（读取 id / subject / body / senderId / accountId）
     * @param externalRecipients  外部收件人地址（非空）
     */
    void sendExternal(Mail mail, List<String> externalRecipients);
}

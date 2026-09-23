package com.mailsystem.service.impl;

import com.mailsystem.dto.OutboundMessage;
import com.mailsystem.entity.Attachment;
import com.mailsystem.entity.Mail;
import com.mailsystem.entity.MailAccount;
import com.mailsystem.mapper.MailMapper;
import com.mailsystem.service.AttachmentService;
import com.mailsystem.service.MailAccountService;
import com.mailsystem.service.MailSendService;
import com.mailsystem.service.OutboundRelayService;
import com.mailsystem.util.MailConnectionFactory;
import com.mailsystem.util.MailErrors;
import com.mailsystem.websocket.MailNotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import javax.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部邮件投递实现 —— 按账户类型在两条通道间分流
 *
 * <h3>两条通道</h3>
 * <ul>
 *   <li><b>账户自带 SMTP</b>（{@code IMAP_SMTP}）—— 用用户绑定邮箱的服务器发信，
 *       发件人就是他自己的邮箱地址。需要授权码</li>
 *   <li><b>项目级中继</b>（{@code CLOUDFLARE}）—— 用本系统自己的身份发信，
 *       发件人是本系统域名下的地址。不需要授权码</li>
 * </ul>
 * <p>
 * 分流依据是 {@link MailAccount#isCloudflareRouting()}，而不是"SMTP 服务器
 * 地址是否为空" —— 后者会把一个"配置填漏了"的普通账户静默地换到另一条通道上，
 * 用户看到的发件人地址会莫名其妙地变掉。
 * </p>
 */
@Service
public class MailSendServiceImpl implements MailSendService {

    /** 失败原因写入 mail.external_error 时的截断长度（列宽 512） */
    private static final int MAX_ERROR_CHARS = 500;

    @Autowired
    private MailAccountService mailAccountService;

    @Autowired
    private MailConnectionFactory connectionFactory;

    @Autowired
    private OutboundRelayService outboundRelayService;

    @Autowired
    private AttachmentService attachmentService;

    @Autowired
    private MailMapper mailMapper;

    @Autowired(required = false)
    private MailNotificationService notificationService;

    @Override
    public void sendExternal(Mail mail, List<String> externalRecipients) {
        if (externalRecipients == null || externalRecipients.isEmpty()) {
            return;
        }

        MailAccount account = resolveAccount(mail);
        if (account == null) {
            fail(mail, "未绑定可用于发信的邮箱账户", "请先在「邮箱账户」页绑定你的邮箱，"
                    + "或领取一个本系统域名下的地址");
            return;
        }

        try {
            OutboundMessage message = buildMessage(account, mail, externalRecipients);

            if (account.isCloudflareRouting()) {
                outboundRelayService.send(message);
            } else {
                sendViaAccountSmtp(account, message);
            }

            markSent(mail);
            System.out.println("[MailSend] 邮件#" + mail.getId() + " 已通过 " + account.getEmailAddress()
                    + "（" + describeChannel(account) + "）投递给 " + externalRecipients);

        } catch (Exception e) {
            String reason = MailErrors.describe(e);
            fail(mail, reason, "外部投递失败：" + reason);
            System.err.println("[MailSend] 邮件#" + mail.getId() + " 外发失败: " + reason);
        }
    }

    // ==================== 通道选择 ====================

    /**
     * 选择发信账户。
     * <p>
     * 优先用 {@code mail.accountId}（草稿可能在绑定前就已保存、之后又换了账户，
     * 以发送时确定的账户为准），回落到该用户第一个可发信的账户。
     * </p>
     */
    private MailAccount resolveAccount(Mail mail) {
        if (mail.getSenderId() == null) {
            return null;
        }
        if (mail.getAccountId() != null) {
            try {
                // requireOwned 顺带校验了账户归属 —— 防止伪造 accountId 用别人的邮箱发信
                return mailAccountService.requireOwned(mail.getSenderId(), mail.getAccountId());
            } catch (RuntimeException e) {
                System.err.println("[MailSend] 指定的发信账户不可用（改用默认账户）: " + e.getMessage());
            }
        }
        return mailAccountService.findPrimaryForSend(mail.getSenderId());
    }

    private String describeChannel(MailAccount account) {
        return account.isCloudflareRouting()
                ? "中继 " + outboundRelayService.transportName()
                : "账户 SMTP";
    }

    // ==================== 内容组装 ====================

    /**
     * 把待投递的内容组装成通道无关的 {@link OutboundMessage}。
     * <p>
     * 附件在这里一次性读成字节：两条通道都要用，而每次读取都要访问
     * MinIO 或本地磁盘。
     * </p>
     */
    private OutboundMessage buildMessage(MailAccount account, Mail mail, List<String> recipients) {
        OutboundMessage message = new OutboundMessage();
        // From 必须是认证身份本身 —— QQ/163/Gmail 与中继服务商都会校验，
        // 用别的地址做 From 会被拒信或直接进垃圾箱。显示名不影响这个校验
        message.setFromAddress(account.getEmailAddress());
        message.setFromName(account.getDisplayName());
        message.setTo(new ArrayList<>(recipients));
        // 站内收件人不在 To 里 —— 他们通过 mail_status 收信，
        // 其站内邮箱（如 user@local）不是可投递的外部地址，
        // 写进头部只会让外部收件人看到一个不存在的地址
        message.setSubject(mail.getSubject() == null ? "" : mail.getSubject());
        message.setBody(mail.getBody() == null ? "" : mail.getBody());

        for (Attachment att : attachmentService.attachmentList(mail.getId())) {
            byte[] data = attachmentService.getAttachmentData(att.getId());
            if (data != null) {
                message.getAttachments().add(
                        new OutboundMessage.OutboundAttachment(att.getFileName(), data));
            }
        }
        return message;
    }

    // ==================== 通道一：账户自带 SMTP ====================

    private void sendViaAccountSmtp(MailAccount account, OutboundMessage message) throws Exception {
        JavaMailSenderImpl sender = connectionFactory.buildSender(
                account, mailAccountService.decryptSmtpPassword(account));

        MimeMessage mime = sender.createMimeMessage();
        // multipart=true：正文与附件（即使只有纯文本正文也允许附件）
        MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");

        String personal = account.getDisplayName();
        if (personal != null && !personal.trim().isEmpty()) {
            helper.setFrom(account.getEmailAddress(), personal);
        } else {
            helper.setFrom(account.getEmailAddress());
        }
        helper.setTo(message.getTo().toArray(new String[0]));
        helper.setSubject(message.getSubject());
        helper.setText(message.getBody(), false);

        for (OutboundMessage.OutboundAttachment att : message.getAttachments()) {
            helper.addAttachment(att.getFileName(), new ByteArrayResource(att.getData()));
        }

        sender.send(mime);
    }

    // ==================== 投递状态记账 ====================

    private void markSent(Mail mail) {
        Mail update = new Mail();
        update.setId(mail.getId());
        update.setExternalStatus(Mail.EXTERNAL_STATUS_SENT);
        // 显式置 null 需要 updateStrategy，简单起见只写状态；
        // 上一次的失败原因已被本次成功覆盖，不需要残留
        mailMapper.updateById(update);
    }

    /**
     * 记录失败并通知发件人。
     * <p>
     * 之所以要推送 WebSocket：外发是异步的，用户点完"发送"看到的提示是"发送成功"
     * （站内投递确实成功了）。若不通知，"给外部邮箱发信失败了"这件事要等到
     * 用户自己去已发送里翻到状态才会发现。
     * </p>
     */
    private void fail(Mail mail, String storedReason, String userMessage) {
        Mail update = new Mail();
        update.setId(mail.getId());
        update.setExternalStatus(Mail.EXTERNAL_STATUS_FAILED);
        update.setExternalError(MailErrors.truncate(storedReason, MAX_ERROR_CHARS));
        mailMapper.updateById(update);

        if (notificationService != null && mail.getSenderId() != null) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("mailId", mail.getId());
            payload.put("status", Mail.EXTERNAL_STATUS_FAILED);
            payload.put("message", userMessage);
            notificationService.notifyUser(mail.getSenderId(), "MAIL_SEND_FAILED", payload);
        }
    }
}

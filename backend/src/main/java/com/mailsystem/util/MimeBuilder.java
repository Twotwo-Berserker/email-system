package com.mailsystem.util;

import com.mailsystem.entity.Mail;
import org.springframework.stereotype.Component;

import javax.activation.DataHandler;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimeUtility;
import javax.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * 把库里的 {@link Mail} 还原成 RFC822 报文
 *
 * <h3>为什么需要它</h3>
 * <p>
 * IMAP 协议的本质是"把整封报文的字节交给客户端"，而本系统的库内形态是
 * <b>拆开的字段</b>（subject / body / external_from / 附件表）。
 * Python 代理（{@code proxy/}）要支持 {@code FETCH BODY[]} 就必须拿到原始报文，
 * 而拼报文这件事只有这边做得了 —— 附件内容存在 MinIO 或本地磁盘上，
 * 代理拿不到。
 * </p>
 *
 * <h3>为什么不存原始报文</h3>
 * <p>
 * 收信时把整封原始报文落一份当然更省事，但会带来两处不一致：Web 端只能渲染
 * 纯文本（HTML 在收信时已转成纯文本），而 IMAP 客户端拿到原始报文却能渲染 HTML ——
 * 同一个收件箱在两个入口看到的内容不一样，是比"信息略少"严重得多的问题。
 * 以库内字段为唯一真相，两个入口展示的就是同一份内容。
 * </p>
 *
 * <h3>已知的取舍</h3>
 * <ul>
 *   <li>正文一律以纯文本发出 —— 库内正文就是纯文本</li>
 *   <li>不还原原始的 {@code Received} / {@code DKIM-Signature} 等传输头。
 *       它们描述的是"这封信怎么到达的"，重建出来等于伪造</li>
 *   <li>{@code To} 只包含外部收件人 —— 站内收件人不是可投递的邮件地址</li>
 * </ul>
 */
@Component
public class MimeBuilder {

    /** 正文与附件分段共存时用的子类型 */
    private static final String MIXED_SUBTYPE = "mixed";

    /** 合成的 Message-ID 与占位发件人用的域名 —— 不是可投递的域，仅供客户端识别 */
    private static final String SYNTHETIC_DOMAIN = "mailsystem.local";

    /** 单封报文最多带多少个附件分段，防止一封信把内存与响应体撑爆 */
    private static final int MAX_ATTACHMENTS = 100;

    /**
     * 组装一封可被 IMAP 客户端直接消费的报文。
     *
     * @param mail        库内邮件（读取 subject / body / externalFrom / externalTo /
     *                    sendTime / externalMsgId）
     * @param attachments 附件内容。由调用方读好传进来，避免这个类反向依赖存储层
     */
    public byte[] build(Mail mail, List<AttachmentPart> attachments) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));

        message.setHeader("Message-ID", resolveMessageId(mail));
        message.setFrom(toAddress(mail.getExternalFrom()));
        applyRecipients(message, mail.getExternalTo());
        message.setSubject(mail.getSubject() == null ? "" : mail.getSubject(),
                StandardCharsets.UTF_8.name());
        message.setSentDate(toDate(mail.getSendTime()));

        List<AttachmentPart> usable = usableOnly(attachments);
        String body = mail.getBody() == null ? "" : mail.getBody();

        if (usable.isEmpty()) {
            message.setText(body, StandardCharsets.UTF_8.name());
        } else {
            MimeMultipart multipart = new MimeMultipart(MIXED_SUBTYPE);

            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(body, StandardCharsets.UTF_8.name());
            multipart.addBodyPart(textPart);

            for (AttachmentPart att : usable) {
                multipart.addBodyPart(toBodyPart(att));
            }
            message.setContent(multipart);
        }

        // 补全必需的头（MIME-Version、multipart 的 boundary 参数等）。
        // 少了它，multipart 报文没有 boundary，客户端会解析成一团乱码
        message.saveChanges();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        return out.toByteArray();
    }

    // ==================== 头部 ====================

    /**
     * 取 Message-ID。
     * <p>
     * 缺失时用邮件 ID 合成一个<b>确定性</b>的取值，而不是随机生成：
     * IMAP 客户端按 Message-ID 做本地去重与线索（thread）归并，
     * 每次 {@code FETCH} 都换一个新 ID 会让同一封信在客户端里不断"变成新邮件"。
     * </p>
     */
    private String resolveMessageId(Mail mail) {
        if (mail.getExternalMsgId() != null && !mail.getExternalMsgId().trim().isEmpty()) {
            String id = mail.getExternalMsgId().trim();
            return id.startsWith("<") ? id : "<" + id + ">";
        }
        return "<mail-" + mail.getId() + "@" + SYNTHETIC_DOMAIN + ">";
    }

    private InternetAddress toAddress(String address) throws Exception {
        if (address == null || address.trim().isEmpty()) {
            // From 不能留空：writeTo 会抛异常，客户端也会把整封判为畸形。
            // 给一个一眼看得出是占位的地址
            return new InternetAddress("unknown@" + SYNTHETIC_DOMAIN);
        }
        return new InternetAddress(address.trim());
    }

    private void applyRecipients(MimeMessage message, String externalTo) throws Exception {
        if (externalTo == null || externalTo.trim().isEmpty()) {
            return;
        }
        for (String raw : externalTo.split(",")) {
            String address = raw.trim();
            if (!address.isEmpty()) {
                message.addRecipient(Message.RecipientType.TO, new InternetAddress(address));
            }
        }
    }

    private Date toDate(LocalDateTime time) {
        LocalDateTime effective = time == null ? LocalDateTime.now() : time;
        return Date.from(effective.atZone(ZoneId.systemDefault()).toInstant());
    }

    // ==================== 附件分段 ====================

    private List<AttachmentPart> usableOnly(List<AttachmentPart> attachments) {
        List<AttachmentPart> usable = new ArrayList<>();
        if (attachments == null) {
            return usable;
        }
        for (AttachmentPart att : attachments) {
            if (att != null && att.getData() != null && att.getData().length > 0) {
                usable.add(att);
            }
            if (usable.size() >= MAX_ATTACHMENTS) {
                break;
            }
        }
        return usable;
    }

    /**
     * 组装一个附件分段。
     * <p>
     * 用 {@link DataHandler} 而不是 {@code setContent(bytes, type)} 再手写
     * {@code Content-Transfer-Encoding}：编码方式必须与实际的字节内容一致，
     * 手动指定等于替 JavaMail 猜 —— 猜错的结果是客户端解出来的附件是坏的，
     * 而且这种损坏在服务端看不出任何异常。
     * </p>
     */
    private MimeBodyPart toBodyPart(AttachmentPart att) throws Exception {
        MimeBodyPart part = new MimeBodyPart();
        String type = att.getContentType() == null || att.getContentType().trim().isEmpty()
                ? "application/octet-stream" : att.getContentType().trim();
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(att.getData(), type)));

        String fileName = att.getFileName() == null || att.getFileName().trim().isEmpty()
                ? "attachment" : att.getFileName().trim();
        // 中文文件名直接写进头部会被当成非法字符；MimeUtility 按 RFC 2047
        // 编成 =?UTF-8?B?...?=
        part.setFileName(MimeUtility.encodeText(fileName, StandardCharsets.UTF_8.name(), null));
        return part;
    }

    /**
     * 附件：文件名、类型与内容。
     * <p>
     * 刻意不复用 {@code OutboundMessage.OutboundAttachment} —— 那个类型表达的是
     * "要发出去的东西"，这里是"要还原出来的东西"。共用会让人以为改一处
     * 就会影响另一处，也会让"附件为什么带类型"这类差异无处安放。
     * </p>
     */
    public static final class AttachmentPart {

        private final String fileName;
        private final String contentType;
        private final byte[] data;

        public AttachmentPart(String fileName, String contentType, byte[] data) {
            this.fileName = fileName;
            this.contentType = contentType;
            this.data = data;
        }

        public String getFileName() {
            return fileName;
        }

        public String getContentType() {
            return contentType;
        }

        public byte[] getData() {
            return data;
        }
    }
}

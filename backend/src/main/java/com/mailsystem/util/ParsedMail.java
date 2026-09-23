package com.mailsystem.util;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 一封外部来信解析后的中间结果
 * <p>
 * 由 {@link MimeParser} 产出，是"网络协议层"与"业务落库层"之间的载体：
 * 无论这封信是从 IMAP 拉回来的，还是 Cloudflare Email Routing 推过来的，
 * 到这里都变成同一个形状，落库逻辑因此只需要写一份。
 * </p>
 * <p>
 * 字段刻意都可空 —— 现实中的邮件常常缺 Message-ID、缺 Subject、甚至缺 Date。
 * 解析器不做"补默认值"这类业务判断，那是落库方的事。
 * </p>
 */
public class ParsedMail {

    /** RFC5322 Message-ID，<b>已去掉尖括号</b>（见 {@link MimeParser#normalizeMessageId}） */
    private String messageId;

    private String subject;

    /** 发件人邮箱地址（已从 {@code 张三 <a@b.com>} 中取出 {@code a@b.com}） */
    private String fromAddress;

    private LocalDateTime sentTime;

    /** {@code text/plain} 部分原文 */
    private String plainBody;

    /** {@code text/html} 部分原文（仅在没有 plain 时才会被采用，见 {@link #resolveBody()}） */
    private String htmlBody;

    private final List<ParsedAttachment> attachments = new ArrayList<>();

    /**
     * 正文：优先 {@code text/plain}，其次把 HTML 转成纯文本。
     * <p>
     * plain 优先是刻意的 —— 它本来就是给人读的纯文本，保真度最高。
     * 详情页以纯文本渲染，直接存 HTML 既会让用户看到满屏标签，
     * 也会给前端留下 XSS 的面。
     * </p>
     */
    public String resolveBody() {
        if (plainBody != null && !plainBody.trim().isEmpty()) {
            return plainBody;
        }
        if (htmlBody != null && !htmlBody.trim().isEmpty()) {
            return HtmlUtil.toPlainText(htmlBody);
        }
        return "";
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public void setFromAddress(String fromAddress) {
        this.fromAddress = fromAddress;
    }

    public LocalDateTime getSentTime() {
        return sentTime;
    }

    public void setSentTime(LocalDateTime sentTime) {
        this.sentTime = sentTime;
    }

    public String getPlainBody() {
        return plainBody;
    }

    public void setPlainBody(String plainBody) {
        this.plainBody = plainBody;
    }

    public String getHtmlBody() {
        return htmlBody;
    }

    public void setHtmlBody(String htmlBody) {
        this.htmlBody = htmlBody;
    }

    public List<ParsedAttachment> getAttachments() {
        return attachments;
    }

    /** 一个 MIME 部件解析出来的附件 */
    public static class ParsedAttachment {

        private final String fileName;
        private final String contentType;
        private final byte[] data;

        public ParsedAttachment(String fileName, String contentType, byte[] data) {
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

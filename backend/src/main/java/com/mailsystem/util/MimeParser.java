package com.mailsystem.util;

import org.springframework.stereotype.Component;

import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeUtility;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Properties;

/**
 * MIME 邮件解析器 —— 把"网络上收到的一封信"变成 {@link ParsedMail}
 *
 * <h3>为什么单独抽出来</h3>
 * <p>
 * 本系统有<b>两条</b>收信链路：
 * </p>
 * <ol>
 *   <li><b>IMAP 轮询</b> —— 定时登录用户绑定的外部邮箱，把新邮件拉回来</li>
 *   <li><b>Cloudflare Email Routing</b> —— 外部邮件由 Cloudflare 在边缘接收后
 *       <b>推送</b>到本系统的 Webhook，全程不登录任何外部邮箱</li>
 * </ol>
 * <p>
 * 两条链路的输入形态不同（一条拿到 {@code javax.mail.Message}，另一条拿到原始字节流），
 * 但"怎么从一封信里取出主题、发件人、正文、附件"是同一个问题。
 * 这份逻辑原先内嵌在 {@code ImapReceiveServiceImpl} 里，第二条链路出现时若不抽取，
 * 就会复制出第二份 —— 而 MIME 解析恰恰是最容易在细节上分叉（编码、嵌套、
 * 附件判定）的地方，两份实现必然逐渐不一致。
 * </p>
 *
 * <h3>正文优先取 text/plain</h3>
 * <p>
 * {@code multipart/alternative} 里 plain 与 html 是同一段内容的两种表示。
 * 取 plain 的原因见 {@link ParsedMail#resolveBody()}。
 * </p>
 */
@Component
public class MimeParser {

    /** 单个附件大小上限，防止一封超大邮件把内存吃满 */
    private static final long MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024;

    /** 附件文件名缺失时的占位名 */
    private static final String DEFAULT_ATTACHMENT_NAME = "attachment";

    /**
     * MIME 树的最大递归深度。
     * <p>
     * 畸形邮件（自引用嵌套、深度几千层的 multipart）会让解析器陷入长时间递归，
     * 而 JavaMail 自己没有深度上限。真实邮件的嵌套很少超过 5 层，
     * 50 层已经宽裕到不可能误伤。
     * </p>
     */
    private static final int MAX_DEPTH = 50;

    /**
     * 解析一段原始 MIME 字节流（Webhook 链路）。
     * <p>
     * 刻意用 {@code new MimeMessage(session, stream)} 而不是自己逐行读头部：
     * 前者处理了折行、RFC2047 编码、多部分边界等全部细节。
     * </p>
     * <p>
     * 头部字段的提取全部交给 {@link #parse(Message)}，
     * 两条链路因此共用同一套取值逻辑，不会出现"同一个头在两处解析出不同结果"。
     * </p>
     */
    public ParsedMail parse(byte[] rawBytes) throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session, new ByteArrayInputStream(rawBytes));
        return parse(message);
    }

    /**
     * 解析一封已经在内存中的邮件（IMAP 链路）。
     */
    public ParsedMail parse(Message message) throws Exception {
        ParsedMail parsed = new ParsedMail();
        parsed.setMessageId(normalizeMessageId(headerOf(message, "Message-ID")));
        parsed.setSubject(decodeHeader(headerOf(message, "Subject")));
        parsed.setFromAddress(extractAddress(headerOf(message, "From")));
        parsed.setSentTime(toLocalDateTime(message.getSentDate()));
        walk(message, parsed, 0);
        return parsed;
    }

    // ==================== 递归遍历 MIME 树 ====================

    /**
     * 深度优先遍历 MIME 树，把正文与附件分别收集起来。
     *
     * @param depth 当前递归深度，用于挡住畸形邮件的无限嵌套
     */
    private void walk(Part part, ParsedMail parsed, int depth) throws Exception {
        if (depth > MAX_DEPTH) {
            System.err.println("[MimeParser] MIME 嵌套超过 " + MAX_DEPTH + " 层，停止解析该分支");
            return;
        }

        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                walk(multipart.getBodyPart(i), parsed, depth + 1);
            }
            return;
        }

        // 有文件名但不是 text/plain、text/html 的部件，即使没标 attachment
        // 也是附件（内联图片、outlook 的 winmail.dat 等）
        String disposition = part.getDisposition();
        boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(disposition)
                || (part.getFileName() != null
                && !part.isMimeType("text/plain") && !part.isMimeType("text/html"));

        if (isAttachment) {
            collectAttachment(part, parsed);
            return;
        }

        if (part.isMimeType("text/plain")) {
            // plain 优先：已有 plain 就不再被后面的 html 覆盖
            if (parsed.getPlainBody() == null) {
                parsed.setPlainBody(String.valueOf(part.getContent()));
            }
        } else if (part.isMimeType("text/html")) {
            if (parsed.getHtmlBody() == null) {
                parsed.setHtmlBody(String.valueOf(part.getContent()));
            }
        }
    }

    private void collectAttachment(Part part, ParsedMail parsed) {
        try {
            String fileName = decodeHeader(part.getFileName());
            if (fileName == null || fileName.trim().isEmpty()) {
                fileName = DEFAULT_ATTACHMENT_NAME;
            }
            byte[] data = readPartBytes(part);
            if (data == null || data.length == 0) {
                return;
            }
            if (data.length > MAX_ATTACHMENT_BYTES) {
                System.err.println("[MimeParser] 附件超过上限被跳过: " + fileName
                        + " (" + data.length + " bytes)");
                return;
            }
            parsed.getAttachments().add(
                    new ParsedMail.ParsedAttachment(fileName, part.getContentType(), data));
        } catch (Exception e) {
            System.err.println("[MimeParser] 读取附件失败: " + e.getMessage());
        }
    }

    private byte[] readPartBytes(Part part) throws Exception {
        try (InputStream in = part.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    // ==================== 头部取值 ====================

    private String headerOf(Message message, String name) {
        try {
            String[] values = message.getHeader(name);
            return (values == null || values.length == 0) ? null : values[0];
        } catch (Exception e) {
            return null;
        }
    }

    /** RFC2047 解码（{@code =?UTF-8?B?...?=} 形式的中文主题与文件名） */
    public String decodeHeader(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        try {
            return MimeUtility.decodeText(raw);
        } catch (Exception e) {
            return raw;
        }
    }

    /** 从 {@code 张三 <a@b.com>} 中取出 {@code a@b.com} */
    public String extractAddress(String rawFrom) {
        if (rawFrom == null || rawFrom.isEmpty()) {
            return null;
        }
        try {
            InternetAddress[] addresses = InternetAddress.parse(rawFrom);
            return addresses.length > 0 ? addresses[0].getAddress() : rawFrom;
        } catch (Exception e) {
            return rawFrom;
        }
    }

    /**
     * 规范化 Message-ID：去掉尖括号并截断。
     * <p>
     * RFC5322 的 Message-ID 形如 {@code <abc@host>}，但不同服务商有带括号
     * 有不带，直接比较会漏判重复，因此统一去掉。
     * </p>
     * <p>
     * 截断到 256 是列宽决定的：超长的 Message-ID 不能把插入搞失败。
     * </p>
     */
    public String normalizeMessageId(String raw) {
        if (raw == null) {
            return null;
        }
        String id = raw.trim();
        if (id.startsWith("<")) {
            id = id.substring(1);
        }
        if (id.endsWith(">")) {
            id = id.substring(0, id.length() - 1);
        }
        id = id.trim();
        if (id.isEmpty()) {
            return null;
        }
        return id.length() > 256 ? id.substring(0, 256) : id;
    }

    private LocalDateTime toLocalDateTime(Date date) {
        return date == null ? null
                : LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }
}

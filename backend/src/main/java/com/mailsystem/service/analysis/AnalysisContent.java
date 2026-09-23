package com.mailsystem.service.analysis;

import com.mailsystem.entity.Mail;
import com.mailsystem.util.HtmlUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 送 LLM 的邮件上下文构造，以及内容的指纹计算。
 *
 * <h3>截断的是正文，不是整段拼好的消息</h3>
 * <p>
 * 主题与发件人必须<b>永远</b>进入上下文：它们是最强的分类与伪造信号，
 * 而正文可以很长。若先拼接再整体截断，一封正文超长的邮件会把主题挤掉 ——
 * 结果是模型看不到主题，却要判断分类与风险。
 * </p>
 *
 * <h3>HTML 必须先剥离</h3>
 * <p>
 * P2 的 IMAP 收信会在只有 {@code text/html} 部分时把它存成纯文本，
 * 但历史数据与手工写入的正文里仍可能有标签。标签是纯 token 浪费，
 * 而且会把真正的内容挤出截断窗口。
 * </p>
 *
 * <h3>为什么是 public</h3>
 * <p>
 * 编排方（{@code MailAnalysisServiceImpl}）按项目约定放在
 * {@code com.mailsystem.service.impl} 下，与本包不同名，因此这几个
 * 管线内部工具必须公开。它们只在本管线内使用，不是对外 API。
 * </p>
 */
public final class AnalysisContent {

    private AnalysisContent() {
    }

    /**
     * 构造用户消息。
     *
     * @param maxBodyChars 正文截断长度（{@code app.analysis.max-content-chars}）
     */
    public static String forLlm(Mail mail, int maxBodyChars) {
        String subject = orEmpty(mail.getSubject());
        String sender = senderOf(mail);
        String body = HtmlUtil.toPlainText(orEmpty(mail.getBody()));
        if (body.length() > maxBodyChars) {
            body = body.substring(0, maxBodyChars) + "\n…（正文过长已截断）";
        }

        return "主题: " + subject + "\n"
                + "发件人: " + sender + "\n"
                + "正文:\n" + body;
    }

    /**
     * 内容指纹：主题与正文的 SHA-256（十六进制）。
     * <p>
     * 用于判断"同一封邮件是否已经分析过同一份内容"。故意<b>不</b>包含发件人：
     * 发件人变了但正文没变时（同一封通知从两个地址发出），结论依然适用，
     * 而把发件人算进去会让这类邮件每次都被重跑一遍，白花钱。
     * </p>
     * <p>
     * 用 SHA-256 而不是 {@code hashCode()}：后者是 32 位、碰撞概率在
     * 几十万封邮件时已经不可忽略，而一次碰撞的表现是"某封邮件明明没分析过
     * 却被跳过"，静默且极难排查。列宽是 CHAR(64)，正好容纳。
     * </p>
     */
    public static String hash(Mail mail) {
        String raw = orEmpty(mail.getSubject()) + "\n" + orEmpty(mail.getBody());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 是 JDK 必备算法，走不到这里。返回 null 让调用方跳过
            // 幂等判断（宁可多分析一次，也不要因为算不出指纹就什么都不做）
            System.err.println("[AnalysisContent] 计算内容指纹失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 发件人展示值：外部来信优先显示其外部地址。
     */
    static String senderOf(Mail mail) {
        String external = mail.getExternalFrom();
        if (external != null && !external.trim().isEmpty()) {
            return external;
        }
        return orEmpty(mail.getSenderEmail());
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}

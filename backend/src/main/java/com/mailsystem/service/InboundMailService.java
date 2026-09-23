package com.mailsystem.service;

import com.mailsystem.dto.InboundResultView;

/**
 * 入站收信服务 —— 处理 Cloudflare Email Routing 推送过来的邮件
 * <p>
 * 这是与 IMAP 轮询并列的第二条收信链路，也是本系统<b>不需要授权码</b>的那条：
 * 外部邮件由 Cloudflare 在 DNS/MX 层面接收后直接推送过来，本系统从不登录
 * 任何外部邮箱。详见 {@code Cloudflare.md}。
 * </p>
 */
public interface InboundMailService {

    /** 投递成功，已入该用户的收件箱 */
    String STATUS_DELIVERED = "DELIVERED";

    /** 重复投递（Message-ID 已存在），视为成功 —— 重试不该产生第二封 */
    String STATUS_DUPLICATE = "DUPLICATE";

    /** 收件地址在本系统中无归属用户，需要让发件人收到退信 */
    String STATUS_UNKNOWN_RECIPIENT = "UNKNOWN_RECIPIENT";

    /** 收件地址对应的账户已被用户停用 */
    String STATUS_DISABLED = "DISABLED";

    /**
     * 接收一封推送来的邮件。
     * <p>
     * 本方法<b>不抛异常</b>：投递结果一律通过返回值的 status 表达。
     * 调用方（Webhook 控制器）需要根据它决定 HTTP 状态码 —— 抛异常会让
     * "地址不存在"与"数据库暂时连不上"变成同一种失败，而前者应当退信、
     * 后者应当让 Cloudflare 重试。
     * </p>
     *
     * @param envelopeFrom 信封发件人（仅记录用，落库的发件人取自邮件头）
     * @param envelopeTo   信封收件人，投递依据
     * @param rawBytes     原始 MIME 报文
     */
    InboundResultView receive(String envelopeFrom, String envelopeTo, byte[] rawBytes);
}

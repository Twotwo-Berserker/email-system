package com.mailsystem.dto;

import lombok.Data;

/**
 * Cloudflare Email Routing 的入站投递请求体
 * <p>
 * 由 {@code deploy/cloudflare/} 下的 Email Worker 发出。字段刻意只有四个 ——
 * Worker 那边是无状态的转发器，任何"需要查库才能填出来"的信息都不该由它提供，
 * 包括这封信该投给谁（那是本系统自己的事，见
 * {@code InboundMailServiceImpl#receive}）。
 * </p>
 *
 * @see com.mailsystem.controller.InboundMailController
 */
@Data
public class InboundMailRequest {

    /**
     * 信封发件人（SMTP MAIL FROM），由 Cloudflare 提供。
     * <p>
     * 它与邮件头里的 {@code From} 可以不同（退信地址、邮件列表转发都会如此）。
     * 落库时用的仍是解析出来的 {@code From} —— 那才是收件人看到的发件人。
     * </p>
     */
    private String from;

    /**
     * 信封收件人（SMTP RCPT TO），<b>投递依据</b>。
     * <p>
     * 刻意用信封收件人而不是 {@code To} 头：本域地址常常是别名或被转发而来，
     * 此时 {@code To} 头里写的可能是一个完全不同的地址（甚至是 Bcc 的
     * 空 To）。信封地址才是"这封信实际被投到了哪个信箱"。
     * </p>
     */
    private String to;

    /** Cloudflare 收到该邮件的时间（ISO-8601），仅用于日志排查 */
    private String receivedAt;

    /** 原始 MIME 报文的 Base64（Worker 不做任何解析，原样透传） */
    private String raw;
}

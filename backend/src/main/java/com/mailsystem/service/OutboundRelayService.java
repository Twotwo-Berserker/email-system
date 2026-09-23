package com.mailsystem.service;

import com.mailsystem.dto.OutboundMessage;

/**
 * 项目级发信中继 —— 用本系统自己的身份把邮件发出去
 *
 * <h3>它解决什么问题</h3>
 * <p>
 * 用户领了本系统域名下的地址（{@code alice@mail.example.com}）之后，收信由
 * Cloudflare Email Routing 解决（见 {@code InboundMailService}）。但 Cloudflare
 * 的 Email Routing <b>只负责收</b>，它不提供发信服务。要让这个地址能发信，
 * 必须有一个"以本项目域名为身份"的发信通道 —— 那就是本服务。
 * </p>
 * <p>
 * 关键区别在于：这里用的是<b>项目自己的</b>凭据，不是用户外部邮箱的授权码。
 * 发件人是本系统的域名地址，认证身份也是本系统的账号，与 QQ / 163 / Gmail
 * 无关。这正是 {@code Cloudflare.md} 里"发件同样不依赖授权码模式"的含义。
 * </p>
 *
 * <h3>两条通道</h3>
 * <ul>
 *   <li><b>Resend</b> —— HTTP API，只需一个 API Key。走 443，不受主机商
 *       封禁 25/465/587 出站端口的影响，是云主机上最省事的一条</li>
 *   <li><b>SMTP 中继</b> —— 连项目自建/自购的 SMTP 服务器，如自建 Postfix、
 *       阿里云邮件推送、SendGrid 的 SMTP 接口。适合已经有邮件服务器的部署</li>
 * </ul>
 * <p>
 * 两者由 {@code app.outbound.transport} 选择，默认 {@code none} ——
 * 不配就不发，而不是悄悄用一个可能不存在的通道去试。
 * </p>
 *
 * <h3>为什么不是 {@code MailSendService} 的一部分</h3>
 * <p>
 * {@code MailSendService} 的职责是"把信投出去并回写投递状态"，它需要根据
 * 账户类型在两条通道之间选择；而"怎么通过某个通道把信送出去"是另一件事，
 * 会随接入的服务商增加而增加。分开之后，新增一个通道不必改动投递状态的
 * 记账逻辑。
 * </p>
 */
public interface OutboundRelayService {

    /**
     * 中继是否可用。
     * <p>
     * 调用方靠它<b>提前</b>给出可读的报错。发信前就拒绝，比写进库、
     * 几秒后再通过 WebSocket 冒出一句"发送失败"要好得多 ——
     * 后者在用户眼里是"点发送成功了，但信没出去"。
     * </p>
     */
    boolean isAvailable();

    /**
     * 当前通道名：{@code resend} / {@code smtp} / {@code none}。
     * <p>
     * 供日志与管理端展示。返回 {@code none} 与 {@link #isAvailable()} 为
     * {@code false} 是两回事：前者是"没配置"，后者还包含"配了但配得不全"。
     * </p>
     */
    String transportName();

    /**
     * 通过中继投递一封信。
     * <p>
     * 失败时抛出异常并带上服务端返回的原因 —— 调用方会把它写进
     * {@code mail.external_error} 并推给用户，因此异常信息必须是
     * "用户看了能采取行动"的那种（如"域名未验证"），而不是堆栈。
     * </p>
     */
    void send(OutboundMessage message) throws Exception;
}

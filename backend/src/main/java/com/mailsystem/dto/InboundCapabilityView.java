package com.mailsystem.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 本域邮箱的能力说明 —— {@code GET /mail-account/capabilities}
 *
 * <h3>为什么要有这个接口</h3>
 * <p>
 * "能不能领本域地址""领了之后能不能发信"这两件事取决于<b>部署方</b>的配置
 * （有没有绑 Cloudflare、有没有配发信中继），而用户从界面上看不出来。
 * 没有这个接口，前端只能把入口一律显示出来，用户点进去、填完、提交，
 * 才收到一句"本实例未启用"。能力说明让"这个入口现在能不能用"
 * 在用户动手之前就是已知的。
 * </p>
 *
 * <h3>为什么收与发要分开说明</h3>
 * <p>
 * 它们是两条独立配置的链路：Cloudflare 负责收，中继负责发。只配了前者时，
 * 本域地址是"能收不能发"的 —— 这是一个合理且常见的状态（比如只想收验证码），
 * 不该被笼统地报成"功能不可用"。
 * </p>
 */
@Data
public class InboundCapabilityView {

    /**
     * 本实例是否开启了入站接收（{@code app.inbound.enabled}）。
     * <p>
     * 为 false 时整个「本域地址」入口都不该显示 —— 领了也收不到信。
     * </p>
     */
    private Boolean inboundEnabled;

    /**
     * 可用于领取的域名列表（{@code app.inbound.domains}）。
     * <p>
     * 空列表意味着"开启了接收但没告诉系统收的是哪个域"，属于配置不完整，
     * 与 {@link #inboundEnabled} 为 false 是两种不同的故障。
     * </p>
     */
    private List<String> domains = new ArrayList<>();

    /** 发信中继的通道名：{@code resend} / {@code smtp} / {@code none} */
    private String outboundTransport;

    /** 发信中继是否就绪。为 false 时本域地址只能收信 */
    private Boolean outboundAvailable;

    /**
     * 给用户看的一句话说明，由后端生成。
     * <p>
     * 放在后端而不是前端拼：同一套判断（"中继未配置"的具体原因）后端在发信前
     * 已经要算一次，两处各写一遍必然出现"提示说能发、实际发不出去"。
     * </p>
     */
    private String message;
}

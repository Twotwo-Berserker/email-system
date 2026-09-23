package com.mailsystem.dto;

import lombok.Data;

/**
 * 领取本系统域名邮箱的请求 —— {@code POST /mail-account/inbound}
 * <p>
 * 与 {@code QuickBindRequest} 的本质区别：这里<b>没有授权码字段</b>。
 * 收信由 Cloudflare Email Routing 在边缘接收后推送给本系统，本系统从不
 * 登录任何外部邮箱，因此不存在需要授权的对象。
 * </p>
 */
@Data
public class CloudflareBindRequest {

    /**
     * 要领取的地址，两种写法都接受：
     * <ul>
     *   <li>{@code alice@mail.example.com} —— 完整地址。域名必须是本实例
     *       配置过的入站域名之一，否则这个地址永远不会收到信</li>
     *   <li>{@code alice} —— 纯用户名，拼上本实例的默认域名</li>
     * </ul>
     * 由是否含 {@code @} 区分，没有歧义（用户名本身不允许含 {@code @}）。
     * 之所以允许只填用户名：前端已经用下拉框给出了域名，让用户再手打一遍
     * 域名只是多了打错的机会。
     */
    private String address;

    /** 发件人显示名，可空 —— 显示为「显示名 &lt;地址&gt;」 */
    private String displayName;
}

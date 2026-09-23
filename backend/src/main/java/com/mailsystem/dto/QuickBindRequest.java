package com.mailsystem.dto;

import lombok.Data;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 一键绑定请求 —— 只需要用户真正知道的两件事
 * <p>
 * 对比 {@link MailAccountRequest}（9 个字段），这里只有 2 个必填：
 * 邮箱地址与授权码。服务器地址、端口、加密方式全部由
 * {@code MailProviderCatalog} 推断 + {@code MailConnectionProber} 验证得出。
 * </p>
 * <p>
 * 授权码仍然是<b>不可避免</b>的 —— 它是邮箱服务商自己签发的凭据，
 * 是对方服务器用来确认"操作者是账号主人"的唯一依据。客户端无法绕过这一点，
 * 能做的只是不再要求用户额外理解服务器地址与加密方式。
 * </p>
 */
@Data
public class QuickBindRequest {

    @NotBlank(message = "邮箱地址不能为空")
    @Email(message = "邮箱地址格式不正确")
    @Size(max = 128, message = "邮箱地址过长")
    private String emailAddress;

    /**
     * 邮箱授权码（明文入参）。落库前由服务层加密。
     * <p>
     * 命名刻意用 {@code password} 而非 {@code authCode}：这个值在 Gmail 上叫
     * "应用专用密码"、在 QQ 邮箱上叫"授权码"、在网易上叫"客户端授权码"，
     * 术语因服务商而异。用中性的 password 可以避免用户因为找不到"授权码"
     * 这三个字而以为该项与自己无关。
     * </p>
     */
    @NotBlank(message = "请填写邮箱授权码")
    @Size(max = 256, message = "授权码过长")
    private String password;

    @Size(max = 64, message = "显示名过长")
    private String displayName;

    /**
     * 是否同时用这个邮箱收信（开启 IMAP）。
     * <p>
     * 为空按 true 处理。"只用来发信"是少数场景（比如企业邮箱不允许 IMAP），
     * 默认收信能让用户在绑定后立刻看到效果 —— 否则他会以为绑定失败了。
     * </p>
     */
    private Boolean receiveEnabled;
}

package com.mailsystem.dto;

import lombok.Data;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 邮箱账户新增/修改请求
 * <p>
 * 两个密码字段是<b>明文入参</b>（HTTPS 传输），落库前由服务层用
 * {@code CryptoUtil} 加密。为空或为掩码串表示"不修改已保存的授权码"，
 * 语义与 LLM 配置的 apiKey 一致。
 * </p>
 * <p>
 * 端口为空时由服务层按 SSL 开关填默认值（SSL: 465/993，非 SSL: 587/143）。
 * </p>
 */
@Data
public class MailAccountRequest {

    @NotBlank(message = "邮箱地址不能为空")
    @Email(message = "邮箱地址格式不正确")
    @Size(max = 128, message = "邮箱地址过长")
    private String emailAddress;

    @Size(max = 64, message = "显示名过长")
    private String displayName;

    @Size(max = 128, message = "SMTP 服务器地址过长")
    private String smtpHost;

    private Integer smtpPort;

    /** 1=SSL(465), 0=STARTTLS(587)；为空按 1 处理 */
    private Integer smtpSsl;

    @Size(max = 128, message = "SMTP 用户名过长")
    private String smtpUsername;

    /** SMTP 授权码（明文）。空或含掩码标记表示不修改 */
    @Size(max = 256, message = "SMTP 授权码过长")
    private String smtpPassword;

    @Size(max = 128, message = "IMAP 服务器地址过长")
    private String imapHost;

    private Integer imapPort;

    private Integer imapSsl;

    @Size(max = 128, message = "IMAP 用户名过长")
    private String imapUsername;

    /** IMAP 授权码（明文）。空或含掩码标记表示不修改 */
    @Size(max = 256, message = "IMAP 授权码过长")
    private String imapPassword;

    /** 是否启用；为空按启用处理 */
    private Boolean enabled;
}

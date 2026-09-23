package com.mailsystem.dto;

import lombok.Data;

import java.util.List;

/**
 * 服务商识别结果 —— 前端据此告诉用户"我认出你的邮箱了，接下来这样做"
 * <p>
 * 这个 DTO 承载的不只是配置，还有<b>指引</b>（{@code guideSteps} 等）。
 * 绑定失败的用户里绝大多数不是配置填错，而是压根不知道该去哪儿生成授权码 ——
 * 把这一步的路径直接告诉他，比让他自己搜"XX邮箱 授权码 在哪"要有效得多。
 * </p>
 * <p>
 * 不含任何机密：这里的方法全部只依赖邮箱地址，不需要用户先提交授权码。
 * 因此前端可以在用户<b>边打字时</b>就实时调用（{@code useMx=false} 避免 DNS 等待）。
 * </p>
 */
@Data
public class ProviderInfoView {

    private String emailAddress;
    private String domain;

    /** 是否真的识别出了服务商。false 表示只能靠猜测，前端应提示用户可展开手动配置 */
    private boolean recognized;

    /** 识别依据：PRESET（域名命中）/ MX（MX 记录反查）/ PATTERN（按命名猜测） */
    private String source;

    private String providerId;
    private String providerName;

    /** 该服务商是否提供 SMTP 发信 —— false 时应直接劝退，不要让用户白试 */
    private boolean smtpSupported;
    private boolean imapSupported;

    /** 推断出的配置。这些值会在真正绑定时被探测结果覆盖 */
    private String smtpHost;
    private Integer smtpPort;
    private Integer smtpSsl;
    private String imapHost;
    private Integer imapPort;
    private Integer imapSsl;

    /** 探测时会依次尝试的端点（展示用，如 "smtp.qq.com:465 (SSL)"） */
    private List<String> smtpCandidates;
    private List<String> imapCandidates;

    /** 生成授权码的入口页面 */
    private String guideUrl;

    /** 生成授权码的分步路径，如「登录邮箱网页版 → 设置 → 账户 → 开启 IMAP/SMTP」 */
    private List<String> guideSteps;

    /** 该服务商特有的坑，如"必须先开启两步验证" */
    private String warning;

    /** 中性提示 */
    private String note;

    /** 完全未收录时的额外说明（如 Proton 不开放协议） */
    private String unsupportedReason;
}

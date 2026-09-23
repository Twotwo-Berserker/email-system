package com.mailsystem.dto;

import com.mailsystem.entity.MailAccount;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 邮箱账户视图（脱敏）
 * <p>
 * <b>不含任何授权码字段</b>，只有"是否已配置"的布尔量。用独立 DTO 而不是给
 * 实体加 {@code @JsonIgnore}，是为了让"接口返回什么"在类型层面一目了然 ——
 * 否则将来有人直接返回实体就会把密文带出去（虽然是密文，但也没必要给）。
 * </p>
 *
 * <h3>两类账户在这一层的表现</h3>
 * <p>
 * {@code CLOUDFLARE}（本域地址）的 {@code smtp*} / {@code imap*} 字段全部为
 * {@code null} —— 这是<b>正常状态</b>而非"没配完"。前端据此切换卡片的渲染方式：
 * 本域地址不显示"授权码""服务器地址"这类字段，改显示"无需授权码"。
 * 判据用 {@link #cloudflareRouting}，不要在前端自行判断
 * {@code smtpHost == null} —— 后者对一个配置填漏的普通账户同样成立。
 * </p>
 */
@Data
public class MailAccountView {

    private Long id;
    private Long userId;

    /** 归属用户邮箱（管理端用；用户端为自己的邮箱） */
    private String ownerEmail;

    private String emailAddress;
    private String displayName;

    /** 账户类型：{@code IMAP_SMTP}（绑定外部邮箱）或 {@code CLOUDFLARE}（本域地址） */
    private String providerType;

    /** 是否为本域地址（收信走 Cloudflare Email Routing，不依赖授权码） */
    private Boolean cloudflareRouting;

    private String smtpHost;
    private Integer smtpPort;
    private Integer smtpSsl;
    private String smtpUsername;

    /** 是否已配置 SMTP 授权码 */
    private Boolean hasSmtpPassword;

    private String imapHost;
    private Integer imapPort;
    private Integer imapSsl;
    private String imapUsername;

    /** 是否已配置 IMAP 授权码 */
    private Boolean hasImapPassword;

    private Integer enabled;
    private Long imapLastUid;
    private LocalDateTime lastSyncTime;
    private String lastSyncStatus;
    private String lastSyncError;
    private LocalDateTime createTime;

    /** 本次同步新增邮件数（仅手动同步的响应里出现） */
    private Integer syncedCount;

    public static MailAccountView from(MailAccount a) {
        MailAccountView v = new MailAccountView();
        v.setId(a.getId());
        v.setUserId(a.getUserId());
        v.setOwnerEmail(a.getOwnerEmail());
        v.setEmailAddress(a.getEmailAddress());
        v.setDisplayName(a.getDisplayName());
        v.setProviderType(a.getProviderType());
        v.setCloudflareRouting(a.isCloudflareRouting());
        v.setSmtpHost(a.getSmtpHost());
        v.setSmtpPort(a.getSmtpPort());
        v.setSmtpSsl(a.getSmtpSsl());
        v.setSmtpUsername(a.getSmtpUsername());
        v.setHasSmtpPassword(isPresent(a.getSmtpPasswordEnc()));
        v.setImapHost(a.getImapHost());
        v.setImapPort(a.getImapPort());
        v.setImapSsl(a.getImapSsl());
        v.setImapUsername(a.getImapUsername());
        v.setHasImapPassword(isPresent(a.getImapPasswordEnc()));
        v.setEnabled(a.getEnabled());
        v.setImapLastUid(a.getImapLastUid());
        v.setLastSyncTime(a.getLastSyncTime());
        v.setLastSyncStatus(a.getLastSyncStatus());
        v.setLastSyncError(a.getLastSyncError());
        v.setCreateTime(a.getCreateTime());
        v.setSyncedCount(a.getSyncedCount());
        return v;
    }

    private static boolean isPresent(String cipher) {
        return cipher != null && !cipher.isEmpty();
    }
}

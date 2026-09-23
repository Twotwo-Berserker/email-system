package com.mailsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户绑定的邮箱账户 —— 两种来源，由 {@link #providerType} 区分
 *
 * <h3>IMAP_SMTP：绑定别人的邮箱</h3>
 * <p>
 * 用户填自己的外部邮箱地址 + 授权码，系统用它的 SMTP 发信、IMAP 收信。
 * 需要授权码，因为要<b>登录对方的服务器</b>。
 * </p>
 *
 * <h3>CLOUDFLARE：使用本系统的域名邮箱</h3>
 * <p>
 * 用户领一个本系统域名下的地址（如 {@code alice@mail.example.com}）。
 * 收信由 Cloudflare Email Routing 在边缘接收后推送进来，发信走项目自己
 * 配置的中继 —— <b>两条路都不需要任何授权码</b>，因为系统从不登录
 * 别人的邮箱，它就是收信方本人。详见 {@code Cloudflare.md}。
 * </p>
 * <p>
 * 这类账户的 {@code smtp_*} / {@code imap_*} 字段全为空：填了反而会让
 * IMAP 轮询队列（要求 {@code imap_host} 非空）和发信账户选择（要求
 * {@code smtp_host} 非空）把它当成一个普通账户去连，然后每 3 分钟失败一次。
 * </p>
 *
 * <h3>授权码字段存的是密文</h3>
 * <p>
 * 两个 {@code _password_enc} 字段存的是 {@code CryptoUtil} 的 AES-256-GCM 密文
 * （前缀 {@code enc:v1:}），出参一律掩码，绝不回传明文。
 * 字段名带 {@code _enc} 后缀就是为了让这件事在实体层面一眼可见 ——
 * 拿到这个字段直接当密码用是错的，必须先解密。
 * </p>
 *
 * <h3>为什么用 enabled 而不是 deleted</h3>
 * <p>
 * 项目的 mybatis-plus 全局配置有 {@code logic-delete-field: deleted}，
 * 任何同名实体字段都会被静默加上 {@code AND deleted = 0}，包括 update 语句，
 * 导致匹配 0 行且不报错。
 * </p>
 */
@Data
@TableName("mail_account")
public class MailAccount {

    /** 账户来源：绑定外部邮箱（SMTP 发信 / IMAP 收信），需要授权码 */
    public static final String PROVIDER_IMAP_SMTP = "IMAP_SMTP";

    /** 账户来源：本系统域名邮箱（Cloudflare Email Routing 收信 + 中继发信），无需授权码 */
    public static final String PROVIDER_CLOUDFLARE = "CLOUDFLARE";

    /** 最后同步结果：成功 */
    public static final String SYNC_SUCCESS = "SUCCESS";

    /** 最后同步结果：失败 */
    public static final String SYNC_FAILED = "FAILED";

    /** 最后同步结果：认证失败（授权码错误或未开启 IMAP/SMTP 服务） */
    public static final String SYNC_AUTH_FAILED = "AUTH_FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户ID */
    private Long userId;

    /** 该账户的邮箱地址 */
    private String emailAddress;

    /**
     * 账户来源: IMAP_SMTP / CLOUDFLARE
     * <p>
     * 历史数据（以及任何未显式赋值的插入）都落到 {@code IMAP_SMTP} ——
     * 那是本字段存在之前唯一的一种账户。
     * </p>
     */
    private String providerType;

    /** 发件人显示名 */
    private String displayName;

    private String smtpHost;
    private Integer smtpPort;

    /** SMTP 是否使用 SSL: 1=SSL(465), 0=STARTTLS/明文(587) */
    private Integer smtpSsl;

    private String smtpUsername;

    /** SMTP 授权码（AES-GCM 密文，禁止直接使用） */
    private String smtpPasswordEnc;

    private String imapHost;
    private Integer imapPort;

    /** IMAP 是否使用 SSL: 1=SSL(993) */
    private Integer imapSsl;

    private String imapUsername;

    /** IMAP 授权码（AES-GCM 密文，禁止直接使用） */
    private String imapPasswordEnc;

    /** 是否启用: 1=启用, 0=停用 */
    private Integer enabled;

    /** 已同步到的最大 UID 水位线（增量拉取依据） */
    private Long imapLastUid;

    private LocalDateTime lastSyncTime;

    /** 最后同步结果: SUCCESS / FAILED / AUTH_FAILED */
    private String lastSyncStatus;

    /** 最后同步错误（截断后） */
    private String lastSyncError;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /**
     * 是否为本系统域名邮箱（Cloudflare Email Routing）。
     * <p>
     * 收信、发信两条路径都要按它分流，因此集中在这里判断一次 ——
     * 各处自己写 {@code equals(PROVIDER_CLOUDFLARE)} 迟早会漏掉某一处
     * 的 null 判断（历史数据该字段为 null）。
     * </p>
     */
    public boolean isCloudflareRouting() {
        return PROVIDER_CLOUDFLARE.equals(providerType);
    }

    // ==================== 非数据库字段 ====================

    /** 归属用户的邮箱（管理端列表展示用，避免前端再查一次用户） */
    @TableField(exist = false)
    private String ownerEmail;

    /** 本次同步新增的邮件数（手动同步的即时反馈，不落库） */
    @TableField(exist = false)
    private Integer syncedCount;
}

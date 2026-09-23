package com.mailsystem.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 邮件实体
 * <p>
 * 同一张表承载站内邮件与外部邮件（SMTP 外发 / IMAP 收信），由
 * {@link #direction} 区分：
 * </p>
 * <ul>
 *   <li>{@code INTERNAL} —— 发件人与收件人都是本站用户，{@link #senderId} 有值</li>
 *   <li>{@code EXTERNAL} —— 至少一端是外部地址。外部来信时 {@link #senderId} 为
 *       {@code null}（刻意不建"影子用户"，否则用户列表、登录、配额等所有以
 *       user 为中心的逻辑都会被污染），发件人见 {@link #externalFrom}</li>
 * </ul>
 * <p>
 * 因此<b>所有读取 {@code senderId} 的地方都必须做空值判断</b> ——
 * 它不再是非空字段。
 * </p>
 */
@Data
@TableName("mail")
public class Mail {

    /** 方向：站内 */
    public static final String DIRECTION_INTERNAL = "INTERNAL";

    /** 方向：含外部地址 */
    public static final String DIRECTION_EXTERNAL = "EXTERNAL";

    /** 外发状态：待发送 */
    public static final String EXTERNAL_STATUS_PENDING = "PENDING";

    /** 外发状态：已投递 */
    public static final String EXTERNAL_STATUS_SENT = "SENT";

    /** 外发状态：投递失败 */
    public static final String EXTERNAL_STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发件人用户ID；外部来信为 null */
    private Long senderId;

    /** 发件人邮箱（冗余字段，方便查询显示） */
    private String senderEmail;

    /** 收件人ID列表，逗号分隔 */
    private String receiverIds;

    /** 抄送人ID列表，逗号分隔 */
    private String ccIds;

    /** 邮件主题 */
    private String subject;

    /** 邮件正文 */
    private String body;

    /** 发送时间 */
    private LocalDateTime sendTime;

    /** 邮件状态: 1=正常, 0=已删除(发件人侧), 2=草稿 */
    private Integer status;

    /** 智能优先级评分（插件计算） */
    private Integer priority;

    /** 是否垃圾邮件: 0=否, 1=是 */
    private Integer isSpam;

    /** 智能摘要（插件生成） */
    private String summary;

    /** 智能分类标签（插件生成） */
    private String category;

    /** 方向: INTERNAL=站内, EXTERNAL=含外部地址 */
    private String direction;

    /** 外部发件人地址（外部来信时有值） */
    private String externalFrom;

    /** 外部收件人地址列表，逗号分隔 */
    private String externalTo;

    /** RFC5322 Message-ID，收信去重依据 */
    private String externalMsgId;

    /** 来源或使用的 mail_account.id */
    private Long accountId;

    /** IMAP UID，Message-ID 缺失时的兜底去重键 */
    private Long imapUid;

    /** 外发状态: PENDING/SENT/FAILED；无外部收件人时为 null */
    private String externalStatus;

    /** 外发失败原因（截断后） */
    private String externalError;

    /**
     * 是否对外部地址发过信（据此决定是否走 SMTP）。
     * 非数据库字段，由 externalTo 派生。
     */
    @TableField(exist = false)
    private Boolean hasExternalRecipients;

    /** 当前用户是否已读此邮件（非数据库字段，从 mail_status JOIN 查询） */
    @TableField(exist = false)
    private Integer isRead;

    /** 收件人昵称列表（非数据库字段，用于前端显示） */
    @TableField(exist = false)
    private String receiverNames;

    /** 抄送人昵称列表（非数据库字段，用于前端显示） */
    @TableField(exist = false)
    private String ccNames;
}

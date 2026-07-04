package com.mailsystem.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 邮件实体
 */
@Data
@TableName("mail")
public class Mail {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发件人用户ID */
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

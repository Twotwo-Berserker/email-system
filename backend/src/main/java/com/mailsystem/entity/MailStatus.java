package com.mailsystem.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 邮件状态实体（收件人/抄送人视角）
 */
@Data
@TableName("mail_status")
public class MailStatus {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 邮件ID */
    private Long mailId;

    /** 用户ID（收件人或抄送人） */
    private Long userId;

    /** 是否已读: 0=未读, 1=已读 */
    private Integer isRead;

    /** 是否已删除: 0=否, 1=是 */
    private Integer isDeleted;

    /** 同步状态: 0=未同步, 1=已同步 */
    private Integer syncStatus;

    /** 阅读时间 */
    private LocalDateTime readTime;

    /** 状态更新时间（用于增量同步） */
    private LocalDateTime updatedTime;
}

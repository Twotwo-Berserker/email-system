package com.mailsystem.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 邮件同步事件 — 用于增量同步
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncMailEvent {

    /** 邮件ID */
    private Long mailId;

    /** 事件类型: READ, DELETE, RESTORE, NEW */
    private String eventType;

    /** 事件发生时间 */
    private LocalDateTime eventTime;
}

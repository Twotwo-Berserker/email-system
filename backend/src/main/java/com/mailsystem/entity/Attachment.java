package com.mailsystem.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 附件实体
 */
@Data
@TableName("attachment")
public class Attachment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属邮件ID */
    private Long mailId;

    /** 原始文件名 */
    private String fileName;

    /** 服务器存储路径 */
    private String filePath;

    /** 文件大小（字节） */
    private Long fileSize;

    /** MIME类型 */
    private String contentType;

    /** 上传时间 */
    private LocalDateTime uploadTime;
}

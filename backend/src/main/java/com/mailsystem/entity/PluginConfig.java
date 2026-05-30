package com.mailsystem.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 插件配置实体
 */
@Data
@TableName("plugin_config")
public class PluginConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 插件名称 */
    private String pluginName;

    /** 是否启用: 0=禁用, 1=启用 */
    private Integer enabled;

    /** 插件描述 */
    private String description;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}

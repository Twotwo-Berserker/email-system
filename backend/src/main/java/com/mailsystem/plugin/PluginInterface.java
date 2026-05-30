package com.mailsystem.plugin;

import com.mailsystem.entity.Mail;

/**
 * 智能插件接口 — 所有插件必须实现此接口
 * 设计为可插拔组件，通过 PluginService 控制开关
 */
public interface PluginInterface {

    /**
     * 插件名称（唯一标识）
     */
    String getName();

    /**
     * 处理邮件（同步或异步）
     */
    void process(Mail mail);

    /**
     * 插件是否已启用
     */
    boolean isEnabled();
}

package com.mailsystem.service;

import com.mailsystem.entity.PluginConfig;
import java.util.List;

/**
 * 插件服务接口
 */
public interface PluginService {

    /**
     * 获取所有插件配置
     */
    List<PluginConfig> listPlugins();

    /**
     * 启用/禁用插件
     */
    PluginConfig togglePlugin(String pluginName, boolean enabled);

    /**
     * 检查指定插件是否启用
     */
    boolean isPluginEnabled(String pluginName);
}

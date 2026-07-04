package com.mailsystem.service;

import com.mailsystem.entity.PluginConfig;

import java.util.List;
import java.util.Map;

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

    /**
     * 获取 LLM 大模型配置
     */
    Map<String, Object> getLlmConfig();

    /**
     * 更新 LLM 大模型配置
     */
    void updateLlmConfig(String apiEndpoint, String apiKey, String modelName, Boolean enabled);
}

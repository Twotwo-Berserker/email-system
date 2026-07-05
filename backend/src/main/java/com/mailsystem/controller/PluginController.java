package com.mailsystem.controller;

import com.mailsystem.dto.ApiResponse;
import com.mailsystem.entity.PluginConfig;
import com.mailsystem.service.PluginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 插件控制器 — /plugin/*
 */
@RestController
@RequestMapping("/plugin")
public class PluginController {

    @Autowired
    private PluginService pluginService;

    /**
     * 获取所有插件列表及状态
     * GET /plugin/list
     */
    @GetMapping("/list")
    public ApiResponse<List<PluginConfig>> list() {
        List<PluginConfig> plugins = pluginService.listPlugins();
        return ApiResponse.ok(plugins);
    }

    /**
     * 启用/禁用插件
     * PUT /plugin/enable
     * Body: { "pluginName": "spamFilter", "enabled": true }
     */
    @PutMapping("/enable")
    public ApiResponse<PluginConfig> toggle(@RequestBody Map<String, Object> body) {
        String pluginName = (String) body.get("pluginName");
        boolean enabled = (boolean) body.get("enabled");
        if (pluginName == null || pluginName.isEmpty()) {
            return ApiResponse.error("插件名称不能为空");
        }
        PluginConfig config = pluginService.togglePlugin(pluginName, enabled);
        return ApiResponse.ok(enabled ? "插件已启用" : "插件已禁用", config);
    }

    // ==================== LLM大模型配置 ====================

    /**
     * 获取LLM配置
     * GET /plugin/llm/config
     */
    @GetMapping("/llm/config")
    public ApiResponse<Map<String, Object>> getLlmConfig() {
        Map<String, Object> config = pluginService.getLlmConfig();
        return ApiResponse.ok(config);
    }

    /**
     * 更新LLM配置
     * PUT /plugin/llm/configure
     * Body: { "apiEndpoint": "...", "apiKey": "...", "modelName": "...", "enabled": true }
     */
    @PutMapping("/llm/configure")
    public ApiResponse<Void> configureLlm(@RequestBody Map<String, Object> body) {
        String apiEndpoint = (String) body.get("apiEndpoint");
        String apiKey = (String) body.get("apiKey");
        String modelName = (String) body.get("modelName");
        Boolean enabled = body.get("enabled") != null ? (Boolean) body.get("enabled") : false;

        pluginService.updateLlmConfig(apiEndpoint, apiKey, modelName, enabled);
        return ApiResponse.ok("LLM配置已更新", null);
    }
}

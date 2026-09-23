package com.mailsystem.controller;

import com.mailsystem.dto.ApiResponse;
import com.mailsystem.entity.PluginConfig;
import com.mailsystem.service.PluginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 插件控制器 — /plugin/*
 * <p>
 * <b>整个 {@code /plugin/**} 由 {@code AdminInterceptor} 限制为管理员访问</b>
 * （见 WebConfig）。这些是系统级配置：插件总开关、全局 LLM 默认配置。
 * </p>
 * <p>
 * 修复的越权问题：原先 {@code GET /plugin/llm/config} 会把全局 API Key
 * 明文回传给<b>任何</b>已登录用户，{@code PUT /plugin/llm/configure}
 * 允许任何已登录用户覆盖它。
 * </p>
 * <p>
 * 普通用户配置自己的 Key 请走 {@code /user/llm-config}。
 * </p>
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
        if (pluginName == null || pluginName.isEmpty()) {
            return ApiResponse.error("插件名称不能为空");
        }
        // 原实现直接 (boolean) body.get("enabled")，字段缺失时会抛 NPE 变成 500
        Object enabledRaw = body.get("enabled");
        if (!(enabledRaw instanceof Boolean)) {
            return ApiResponse.error("enabled 必须为布尔值");
        }
        boolean enabled = (Boolean) enabledRaw;

        try {
            PluginConfig config = pluginService.togglePlugin(pluginName, enabled);
            return ApiResponse.ok(enabled ? "插件已启用" : "插件已禁用", config);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 系统默认 LLM 配置（仅管理员） ====================

    /**
     * 获取系统默认 LLM 配置。apiKey 为掩码，不回传明文。
     * GET /plugin/llm/config
     */
    @GetMapping("/llm/config")
    public ApiResponse<Map<String, Object>> getLlmConfig() {
        return ApiResponse.ok(pluginService.getLlmConfig());
    }

    /**
     * 更新系统默认 LLM 配置
     * PUT /plugin/llm/configure
     * Body: { "apiEndpoint": "...", "apiKey": "...", "modelName": "...", "enabled": true }
     * <p>
     * apiKey 为空或为掩码串时表示"不修改密钥"。
     * </p>
     */
    @PutMapping("/llm/configure")
    public ApiResponse<Void> configureLlm(@RequestBody Map<String, Object> body) {
        String apiEndpoint = (String) body.get("apiEndpoint");
        String apiKey = (String) body.get("apiKey");
        String modelName = (String) body.get("modelName");
        Boolean enabled = body.get("enabled") instanceof Boolean ? (Boolean) body.get("enabled") : null;

        try {
            pluginService.updateLlmConfig(apiEndpoint, apiKey, modelName, enabled);
            return ApiResponse.ok("LLM配置已更新", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }
}

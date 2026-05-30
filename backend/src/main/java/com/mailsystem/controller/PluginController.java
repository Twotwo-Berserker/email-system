package com.mailsystem.controller;

import com.mailsystem.dto.ApiResponse;
import com.mailsystem.entity.PluginConfig;
import com.mailsystem.service.PluginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
        try {
            String pluginName = (String) body.get("pluginName");
            boolean enabled = (boolean) body.get("enabled");

            if (pluginName == null || pluginName.isEmpty()) {
                return ApiResponse.error("插件名称不能为空");
            }

            PluginConfig config = pluginService.togglePlugin(pluginName, enabled);
            return ApiResponse.ok(enabled ? "插件已启用" : "插件已禁用", config);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }
}

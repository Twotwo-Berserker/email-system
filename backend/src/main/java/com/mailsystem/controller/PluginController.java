package com.mailsystem.controller;

import com.mailsystem.dto.ApiResponse;
import com.mailsystem.entity.PluginConfig;
import com.mailsystem.plugin.dynamic.DynamicPluginLoader;
import com.mailsystem.service.PluginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.*;

/**
 * 插件控制器 — /plugin/*
 */
@RestController
@RequestMapping("/plugin")
public class PluginController {

    @Autowired
    private PluginService pluginService;

    @Autowired
    private DynamicPluginLoader dynamicPluginLoader;

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

    // ==================== 动态JAR插件管理 ====================

    /**
     * 上传并加载JAR插件
     * POST /plugin/load
     */
    @PostMapping("/load")
    public ApiResponse<Map<String, Object>> loadPlugin(@RequestParam("file") MultipartFile file) {
        try {
            // 确保插件目录存在
            File pluginDir = new File("./plugins/");
            if (!pluginDir.exists()) {
                pluginDir.mkdirs();
            }

            // 保存JAR文件
            String fileName = file.getOriginalFilename();
            if (fileName == null || !fileName.endsWith(".jar")) {
                return ApiResponse.error("只支持JAR格式的插件文件");
            }
            File jarFile = new File(pluginDir, fileName);
            file.transferTo(jarFile);

            // 加载插件
            var plugin = dynamicPluginLoader.loadJar(jarFile);
            if (plugin != null) {
                Map<String, Object> result = new HashMap<>();
                result.put("name", plugin.getName());
                result.put("fileName", fileName);
                return ApiResponse.ok("插件加载成功", result);
            } else {
                return ApiResponse.error("JAR文件中未找到有效的插件实现");
            }
        } catch (Exception e) {
            return ApiResponse.error("插件加载失败: " + e.getMessage());
        }
    }

    /**
     * 卸载动态插件
     * DELETE /plugin/unload/{name}
     */
    @DeleteMapping("/unload/{name}")
    public ApiResponse<Void> unloadPlugin(@PathVariable String name) {
        boolean success = dynamicPluginLoader.unloadPlugin(name);
        if (success) {
            return ApiResponse.ok("插件已卸载", null);
        }
        return ApiResponse.error("插件不存在");
    }

    /**
     * 列出已加载的动态插件
     * GET /plugin/dynamic/list
     */
    @GetMapping("/dynamic/list")
    public ApiResponse<List<String>> listDynamicPlugins() {
        return ApiResponse.ok(dynamicPluginLoader.listLoadedPlugins());
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

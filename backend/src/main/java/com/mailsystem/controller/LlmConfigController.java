package com.mailsystem.controller;

import com.mailsystem.dto.ApiResponse;
import com.mailsystem.service.PluginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 用户级 LLM 配置 — /user/llm-config
 * <p>
 * 多租户 BYO-Key（自带密钥）：每个用户配置自己的端点/模型/密钥，
 * 分析自己收到的邮件时用自己的配置，与其他用户互相隔离。
 * </p>
 * <p>
 * 与 {@code /plugin/llm/*}（系统默认配置、仅管理员）的区别就在这里：
 * 本控制器只操作<b>当前登录用户自己</b>那一行，userId 取自 Token，
 * 不接受任何客户端传入的 userId —— 否则就变成"任意用户可改他人配置"。
 * </p>
 */
@RestController
@RequestMapping("/user/llm-config")
public class LlmConfigController {

    @Autowired
    private PluginService pluginService;

    /**
     * 获取自己的 LLM 配置。apiKey 为掩码。
     * GET /user/llm-config
     * <p>
     * 未单独配置时返回系统默认的内容，并带 {@code inheritedFromSystem=true}。
     * </p>
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> get(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return ApiResponse.ok(pluginService.getLlmConfigForUser(userId));
    }

    /**
     * 保存自己的 LLM 配置
     * PUT /user/llm-config
     * Body: { "apiEndpoint": "...", "apiKey": "...", "modelName": "...", "enabled": true }
     * <p>
     * apiKey 为空或为掩码串时表示"不修改密钥"。
     * </p>
     */
    @PutMapping
    public ApiResponse<Void> save(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return ApiResponse.error(401, "未登录");
        }
        String apiEndpoint = (String) body.get("apiEndpoint");
        String apiKey = (String) body.get("apiKey");
        String modelName = (String) body.get("modelName");
        Boolean enabled = body.get("enabled") instanceof Boolean ? (Boolean) body.get("enabled") : null;

        try {
            pluginService.updateLlmConfigForUser(userId, apiEndpoint, apiKey, modelName, enabled);
            return ApiResponse.ok("配置已保存", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 删除自己的 LLM 配置（回落到系统默认）
     * DELETE /user/llm-config
     */
    @DeleteMapping
    public ApiResponse<Void> remove(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        pluginService.deleteLlmConfigForUser(userId);
        return ApiResponse.ok("已恢复为系统默认配置", null);
    }
}

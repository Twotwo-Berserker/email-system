package com.mailsystem.service;

import com.mailsystem.entity.PluginConfig;

import java.util.List;
import java.util.Map;

/**
 * 插件与 LLM 配置服务接口
 * <p>
 * LLM 配置分两层：
 * <ul>
 *   <li><b>系统默认</b>（{@code llm_config.user_id IS NULL}）—— 仅管理员可改</li>
 *   <li><b>用户自带</b>（{@code user_id = ?}）—— 用户改自己的，互相不可见</li>
 * </ul>
 * 用户未配置时回落到系统默认。
 * </p>
 * <p>
 * 所有 {@code get*Config} 方法返回的 {@code apiKey} 都是<b>掩码</b>，
 * 明文只通过 {@link #resolveEffectiveConfigForUser} 暴露给服务端内部调用。
 * </p>
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

    // ==================== LLM 配置（展示用，apiKey 已掩码） ====================

    /**
     * 获取系统默认 LLM 配置（管理端）
     */
    Map<String, Object> getLlmConfig();

    /**
     * 获取指定用户的 LLM 配置；该用户未单独配置时返回系统默认的内容，
     * 并通过 {@code inheritedFromSystem=true} 告知前端这是继承来的。
     */
    Map<String, Object> getLlmConfigForUser(Long userId);

    // ==================== LLM 配置（内部使用，apiKey 为明文） ====================

    /**
     * 解析某用户实际生效的配置，{@code apiKey} 为<b>明文</b>。
     * <p>
     * <b>仅供服务端发起 LLM 调用使用，绝不能直接作为接口响应返回。</b>
     * </p>
     *
     * @return 配置；若该用户与系统默认都没有可用 Key，返回的 {@code apiKey} 为空串
     */
    Map<String, Object> resolveEffectiveConfigForUser(Long userId);

    // ==================== LLM 配置写入 ====================

    /**
     * 更新系统默认 LLM 配置（仅管理员）。
     * <p>
     * {@code apiKey} 为 null / 空串 / 掩码串时表示"不修改"，沿用已存储的密钥 ——
     * 否则前端把掩码原样回传就会把密钥写成 "****abcd"。
     * </p>
     */
    void updateLlmConfig(String apiEndpoint, String apiKey, String modelName, Boolean enabled);

    /**
     * 更新指定用户自己的 LLM 配置。语义同上。
     */
    void updateLlmConfigForUser(Long userId, String apiEndpoint, String apiKey,
                                String modelName, Boolean enabled);

    /**
     * 删除指定用户的 LLM 配置（回落到系统默认）
     */
    void deleteLlmConfigForUser(Long userId);
}

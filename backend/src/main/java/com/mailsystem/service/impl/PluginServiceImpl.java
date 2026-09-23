package com.mailsystem.service.impl;

import com.mailsystem.entity.PluginConfig;
import com.mailsystem.mapper.PluginConfigMapper;
import com.mailsystem.service.PluginService;
import com.mailsystem.util.CryptoUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件与 LLM 配置服务实现
 * <p>
 * 相较原实现的三处安全修复：
 * <ol>
 *   <li>{@code api_key} 以 AES-GCM 密文落库，不再明文存储</li>
 *   <li>所有对外的读接口返回<b>掩码</b>，不再明文回传密钥</li>
 *   <li>写入时把"未修改"（null/空/掩码原样回传）与"清空密钥"区分开，
 *       避免前端把掩码提交回来把库里的密钥写坏</li>
 * </ol>
 * </p>
 * <p>
 * 同时把原先硬编码的 {@code WHERE id = 1} 改为按 {@code user_id} 定位，
 * 系统默认配置为 {@code user_id IS NULL}、每用户一条。
 * </p>
 */
@Service
public class PluginServiceImpl implements PluginService {

    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "gpt-3.5-turbo";

    /** 掩码特征串。提交值里含它就视为"用户没有改动密钥" */
    private static final String MASK_MARKER = "****";

    @Autowired
    private PluginConfigMapper pluginConfigMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CryptoUtil cryptoUtil;

    // ==================== 插件开关 ====================

    @Override
    public List<PluginConfig> listPlugins() {
        return pluginConfigMapper.selectList(null);
    }

    @Override
    public PluginConfig togglePlugin(String pluginName, boolean enabled) {
        PluginConfig config = pluginConfigMapper.selectByPluginName(pluginName);
        if (config == null) {
            throw new RuntimeException("插件不存在: " + pluginName);
        }
        config.setEnabled(enabled ? 1 : 0);
        pluginConfigMapper.updateEnabled(pluginName, enabled ? 1 : 0);
        return pluginConfigMapper.selectByPluginName(pluginName);
    }

    @Override
    public boolean isPluginEnabled(String pluginName) {
        PluginConfig config = pluginConfigMapper.selectByPluginName(pluginName);
        return config != null && config.getEnabled() == 1;
    }

    // ==================== LLM 配置读取（展示用） ====================

    @Override
    public Map<String, Object> getLlmConfig() {
        Map<String, Object> row = selectSystemDefaultRow();
        if (row == null) {
            return defaultView(false);
        }
        return toView(row, false);
    }

    @Override
    public Map<String, Object> getLlmConfigForUser(Long userId) {
        Map<String, Object> own = userId == null ? null : selectUserRow(userId);
        if (own != null) {
            return toView(own, false);
        }
        // 用户未单独配置 —— 返回系统默认的内容，并标记为继承而来，
        // 前端据此提示"当前使用系统默认配置"
        Map<String, Object> system = selectSystemDefaultRow();
        if (system == null) {
            return defaultView(true);
        }
        return toView(system, true);
    }

    // ==================== LLM 配置读取（内部调用用，明文） ====================

    @Override
    public Map<String, Object> resolveEffectiveConfigForUser(Long userId) {
        Map<String, Object> row = userId == null ? null : selectUserRow(userId);

        // 用户自己的配置必须同时满足"已启用"和"有可用 Key"才算数，
        // 否则回落到系统默认 —— 否则一个配了 Key 但 enabled=0 的用户
        // 会把系统默认也一起屏蔽掉
        if (row != null && isUsable(row)) {
            return toPlainConfig(row);
        }

        Map<String, Object> system = selectSystemDefaultRow();
        if (system != null && isUsable(system)) {
            return toPlainConfig(system);
        }

        // 两者都不可用：返回一个 apiKey 为空的配置，由调用方走规则兜底
        Map<String, Object> empty = new HashMap<>();
        empty.put("apiEndpoint", DEFAULT_ENDPOINT);
        empty.put("apiKey", "");
        empty.put("modelName", DEFAULT_MODEL);
        empty.put("enabled", 0);
        return empty;
    }

    // ==================== LLM 配置写入 ====================

    @Override
    public void updateLlmConfig(String apiEndpoint, String apiKey, String modelName, Boolean enabled) {
        upsert(null, apiEndpoint, apiKey, modelName, enabled);
    }

    @Override
    public void updateLlmConfigForUser(Long userId, String apiEndpoint, String apiKey,
                                       String modelName, Boolean enabled) {
        if (userId == null) {
            throw new RuntimeException("用户ID不能为空");
        }
        upsert(userId, apiEndpoint, apiKey, modelName, enabled);
    }

    @Override
    public void deleteLlmConfigForUser(Long userId) {
        if (userId == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM llm_config WHERE user_id = ?", userId);
    }

    // ==================== 内部实现 ====================

    private Map<String, Object> selectSystemDefaultRow() {
        return queryOne("SELECT * FROM llm_config WHERE user_id IS NULL ORDER BY id ASC LIMIT 1");
    }

    private Map<String, Object> selectUserRow(Long userId) {
        return queryOne("SELECT * FROM llm_config WHERE user_id = ? ORDER BY id ASC LIMIT 1", userId);
    }

    private Map<String, Object> queryOne(String sql, Object... args) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            // 最可能的原因是尚未执行 migration_v3.sql（llm_config 没有 user_id 列）。
            // 原实现在这里静默建表并返回默认值，会把"结构未迁移"伪装成"没有配置"，
            // 因此这里把原因打出来。
            System.err.println("[PluginService] 读取 LLM 配置失败（请确认已执行 deploy/mysql/migration_v3.sql）: "
                    + e.getMessage());
            return null;
        }
    }

    /**
     * 行是否可用：已启用且密钥非空
     */
    private boolean isUsable(Map<String, Object> row) {
        Object enabled = row.get("enabled");
        boolean on = enabled instanceof Number && ((Number) enabled).intValue() == 1;
        if (!on) {
            return false;
        }
        Object key = row.get("api_key");
        return key != null && !key.toString().isEmpty();
    }

    /**
     * 转为内部使用的配置，{@code apiKey} 解密为<b>明文</b>
     * <p>
     * {@code credentialOwner} 表示<b>这枚密钥是谁的</b>：用户自己的配置为他的
     * {@code user_id}，系统默认为 {@code null}。调用方（分析管线）用它来分桶
     * 计数熔断与限流 —— 同一个 Key 应该共用一个桶，否则"系统默认 Key 失效"
     * 会因为每个用户各烧一轮失败而被放大 N 倍，
     * 而"某个用户自己的 Key 写错了"又应该是他自己的事。
     * </p>
     */
    private Map<String, Object> toPlainConfig(Map<String, Object> row) {
        Map<String, Object> config = new HashMap<>();
        config.put("apiEndpoint", str(row.get("api_endpoint"), DEFAULT_ENDPOINT));
        config.put("apiKey", decryptSafe(row.get("api_key")));
        config.put("modelName", str(row.get("model_name"), DEFAULT_MODEL));
        config.put("enabled", row.get("enabled"));
        config.put("credentialOwner", toLong(row.get("user_id")));
        return config;
    }

    private static Long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 转为前端展示用的配置，{@code apiKey} 为<b>掩码</b>。
     * <p>
     * 展示路径<b>不做解密</b>：有 {@code api_key_last4} 就直接拼掩码，
     * 历史明文行取尾4位，密文但缺 last4 时只给全掩码。
     * 这样接口响应里永远不会出现明文密钥。
     * </p>
     */
    private Map<String, Object> toView(Map<String, Object> row, boolean inherited) {
        Map<String, Object> config = new HashMap<>();
        config.put("apiEndpoint", str(row.get("api_endpoint"), DEFAULT_ENDPOINT));
        config.put("apiKey", displayMask(row));
        config.put("modelName", str(row.get("model_name"), DEFAULT_MODEL));
        config.put("enabled", row.get("enabled"));
        config.put("inheritedFromSystem", inherited);
        return config;
    }

    private Map<String, Object> defaultView(boolean inherited) {
        Map<String, Object> config = new HashMap<>();
        config.put("apiEndpoint", DEFAULT_ENDPOINT);
        config.put("apiKey", "");
        config.put("modelName", DEFAULT_MODEL);
        config.put("enabled", 0);
        config.put("inheritedFromSystem", inherited);
        return config;
    }

    private String displayMask(Map<String, Object> row) {
        Object last4 = row.get("api_key_last4");
        if (last4 != null && !last4.toString().isEmpty()) {
            return MASK_MARKER + last4;
        }
        Object key = row.get("api_key");
        if (key == null) {
            return "";
        }
        String stored = key.toString();
        if (stored.isEmpty()) {
            return "";
        }
        // 历史明文行可以直接取尾4位；密文行拿不到尾4位时只给全掩码
        return cryptoUtil.isEncrypted(stored)
                ? MASK_MARKER
                : MASK_MARKER + cryptoUtil.last4(stored);
    }

    private String decryptSafe(Object storedKey) {
        if (storedKey == null) {
            return "";
        }
        try {
            return cryptoUtil.decrypt(storedKey.toString());
        } catch (Exception e) {
            // 解密失败通常意味着 AES_SECRET_KEY 被换过。
            // 这里降级为"无可用 Key"，让分析走规则兜底，而不是让整个请求失败。
            System.err.println("[PluginService] LLM 密钥解密失败，将回退为无 Key（规则兜底）: " + e.getMessage());
            return "";
        }
    }

    /**
     * 写入或更新配置。
     *
     * @param userId null 表示系统默认配置
     */
    private void upsert(Long userId, String apiEndpoint, String apiKey, String modelName, Boolean enabled) {
        Map<String, Object> existing = userId == null ? selectSystemDefaultRow() : selectUserRow(userId);

        String finalCipher;
        String finalLast4;
        if (keepExistingKey(apiKey)) {
            // 未修改密钥 —— 沿用已存储的值，避免把掩码写进库里
            finalCipher = existing == null ? null : (String) existing.get("api_key");
            finalLast4 = existing == null ? null : (String) existing.get("api_key_last4");
        } else {
            finalCipher = cryptoUtil.encrypt(apiKey);
            finalLast4 = cryptoUtil.last4(apiKey);
        }

        String finalEndpoint = isBlank(apiEndpoint)
                ? (existing == null ? DEFAULT_ENDPOINT : str(existing.get("api_endpoint"), DEFAULT_ENDPOINT))
                : apiEndpoint.trim();
        String finalModel = isBlank(modelName)
                ? (existing == null ? DEFAULT_MODEL : str(existing.get("model_name"), DEFAULT_MODEL))
                : modelName.trim();
        // enabled 只有 null 才表示"不修改"——false 是有意义的值
        int finalEnabled;
        if (enabled != null) {
            finalEnabled = enabled ? 1 : 0;
        } else if (existing != null && existing.get("enabled") instanceof Number) {
            finalEnabled = ((Number) existing.get("enabled")).intValue();
        } else {
            finalEnabled = 0;
        }

        if (existing == null) {
            jdbcTemplate.update(
                    "INSERT INTO llm_config (user_id, api_endpoint, api_key, api_key_last4, model_name, enabled) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    userId, finalEndpoint, finalCipher, finalLast4, finalModel, finalEnabled);
        } else {
            jdbcTemplate.update(
                    "UPDATE llm_config SET api_endpoint = ?, api_key = ?, api_key_last4 = ?, "
                            + "model_name = ?, enabled = ? WHERE id = ?",
                    finalEndpoint, finalCipher, finalLast4, finalModel, finalEnabled, existing.get("id"));
        }
    }

    /**
     * 提交的密钥是否表示"不要修改"。
     * <p>
     * 前端展示的是掩码，用户只改端点/模型时会把掩码原样提交回来。
     * 若不加判断，库里存的密钥就会变成 "****abcd" 这种垃圾值，
     * 而且是<b>静默</b>发生的 —— 直到下次调用 LLM 才以 401 的形式暴露。
     * </p>
     */
    private boolean keepExistingKey(String apiKey) {
        return isBlank(apiKey) || apiKey.contains(MASK_MARKER);
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String str(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }
}

package com.mailsystem.service.impl;

import com.mailsystem.entity.PluginConfig;
import com.mailsystem.mapper.PluginConfigMapper;
import com.mailsystem.service.PluginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件服务实现
 */
@Service
public class PluginServiceImpl implements PluginService {

    @Autowired
    private PluginConfigMapper pluginConfigMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    @Override
    public Map<String, Object> getLlmConfig() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM llm_config WHERE id = 1");
            if (rows.isEmpty()) {
                return getDefaultLlmConfig();
            }
            Map<String, Object> row = rows.get(0);
            Map<String, Object> config = new HashMap<>();
            config.put("apiEndpoint", row.getOrDefault("api_endpoint", "https://api.openai.com/v1"));
            config.put("apiKey", row.getOrDefault("api_key", ""));
            config.put("modelName", row.getOrDefault("model_name", "gpt-3.5-turbo"));
            config.put("enabled", row.getOrDefault("enabled", 0));
            return config;
        } catch (Exception e) {
            // 表不存在时自动创建并返回默认配置
            ensureLlmConfigTable();
            return getDefaultLlmConfig();
        }
    }

    private Map<String, Object> getDefaultLlmConfig() {
        Map<String, Object> defaultConfig = new HashMap<>();
        defaultConfig.put("apiEndpoint", "https://api.openai.com/v1");
        defaultConfig.put("apiKey", "");
        defaultConfig.put("modelName", "gpt-3.5-turbo");
        defaultConfig.put("enabled", 0);
        return defaultConfig;
    }

    private void ensureLlmConfigTable() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS llm_config (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, " +
                "api_endpoint VARCHAR(512) NOT NULL DEFAULT 'https://api.openai.com/v1', " +
                "api_key VARCHAR(512) DEFAULT NULL, " +
                "model_name VARCHAR(128) NOT NULL DEFAULT 'gpt-3.5-turbo', " +
                "enabled TINYINT NOT NULL DEFAULT 0, " +
                "create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    @Override
    public void updateLlmConfig(String apiEndpoint, String apiKey, String modelName, Boolean enabled) {
        try {
            // 确保配置记录存在
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_config", Integer.class);
            if (count == null || count == 0) {
                jdbcTemplate.update(
                        "INSERT INTO llm_config (api_endpoint, api_key, model_name, enabled) VALUES (?, ?, ?, ?)",
                        apiEndpoint != null ? apiEndpoint : "https://api.openai.com/v1",
                        apiKey != null ? apiKey : "",
                        modelName != null ? modelName : "gpt-3.5-turbo",
                        enabled != null && enabled ? 1 : 0);
            } else {
                jdbcTemplate.update(
                        "UPDATE llm_config SET api_endpoint = ?, api_key = ?, model_name = ?, enabled = ? WHERE id = 1",
                        apiEndpoint,
                        apiKey,
                        modelName,
                        enabled != null && enabled ? 1 : 0);
            }
        } catch (Exception e) {
            // 表不存在时自动创建并重试
            ensureLlmConfigTable();
            jdbcTemplate.update(
                    "INSERT INTO llm_config (api_endpoint, api_key, model_name, enabled) VALUES (?, ?, ?, ?)",
                    apiEndpoint != null ? apiEndpoint : "https://api.openai.com/v1",
                    apiKey != null ? apiKey : "",
                    modelName != null ? modelName : "gpt-3.5-turbo",
                    enabled != null && enabled ? 1 : 0);
        }
    }
}

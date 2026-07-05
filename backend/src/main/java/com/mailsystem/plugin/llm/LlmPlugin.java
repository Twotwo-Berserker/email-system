package com.mailsystem.plugin.llm;

import com.mailsystem.entity.Mail;
import com.mailsystem.mapper.MailMapper;
import com.mailsystem.plugin.PluginInterface;
import com.mailsystem.service.PluginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * LLM大模型插件 — 调用第三方AI API进行智能分析
 * 支持两种 API 格式：
 *   - OpenAI Chat Completions（/v1/chat/completions）— 默认，适用于 OpenAI、DeepSeek 等
 *   - Anthropic Messages（/v1/messages）— 仅当主机名为 *.anthropic.com 时使用
 * <p>
 * 配置来源：从 llm_config 数据库表读取（由前端 Settings 页面管理）
 * </p>
 */
@Component
public class LlmPlugin implements PluginInterface {

    @Autowired
    private PluginService pluginService;

    @Autowired
    private MailMapper mailMapper;

    private final RestTemplate restTemplate = new RestTemplate();

    /** 缓存的 LLM 配置（30秒刷新一次） */
    private volatile Map<String, Object> cachedLlmConfig;
    private volatile long lastConfigLoadTime = 0;
    private static final long CONFIG_CACHE_MS = 30_000;

    @Override
    public String getName() {
        return "llmAnalyzer";
    }

    @Override
    @Async
    public void process(Mail mail) {
        if (!isEnabled()) return;

        Map<String, Object> config = getLlmConfig();
        if (config == null) return;

        String apiKey = (String) config.get("apiKey");
        String apiEndpoint = (String) config.get("apiEndpoint");
        String modelName = (String) config.get("modelName");

        if (apiKey == null || apiKey.isEmpty()) return;
        if (apiEndpoint == null || apiEndpoint.isEmpty()) return;

        try {
            String body = mail.getBody();
            if (body == null || body.isEmpty()) return;

            String systemPrompt = "你是一个邮件摘要助手。请用一句话总结以下邮件内容（不超过200字），"
                    + "只返回摘要内容，不要加任何前缀、后缀或额外说明。"
                    + "如果是中文邮件用中文回复，英文邮件用英文回复。";

            String summary;
            if (isAnthropicEndpoint(apiEndpoint)) {
                summary = callAnthropicApi(apiEndpoint, apiKey, modelName, body, systemPrompt);
            } else {
                summary = callOpenAiApi(apiEndpoint, apiKey, modelName, body, systemPrompt);
            }

            if (summary != null && !summary.isEmpty()) {
                mailMapper.updateSummary(mail.getId(), summary.trim());
                mail.setSummary(summary.trim());
                System.out.println("[LlmPlugin] 邮件#" + mail.getId() + " LLM摘要: " + summary.trim());
            }

        } catch (Exception e) {
            System.err.println("[LlmPlugin] LLM调用失败: " + e.getMessage());
        }
    }

    @Override
    public boolean isEnabled() {
        Map<String, Object> config = getLlmConfig();
        if (config == null) return false;

        Object enabled = config.get("enabled");
        boolean isEnabled = (enabled instanceof Number) && ((Number) enabled).intValue() == 1;
        if (!isEnabled) return false;

        String apiKey = (String) config.get("apiKey");
        return apiKey != null && !apiKey.isEmpty();
    }

    // ==================== API 格式检测 ====================

    /**
     * 判断是否为 Anthropic Messages API 端点
     * 只有当主机名明确是 api.anthropic.com 时才使用 Anthropic 格式
     * DeepSeek、OpenAI 等提供商均使用 OpenAI Chat Completions 兼容格式
     */
    private boolean isAnthropicEndpoint(String endpoint) {
        if (endpoint == null) return false;
        try {
            String host = new java.net.URL(endpoint).getHost();
            return host != null && host.endsWith("anthropic.com");
        } catch (Exception e) {
            // URL 解析失败，回退到检查路径中的 "anthropic" 关键字
            return endpoint.toLowerCase().contains("anthropic");
        }
    }

    // ==================== OpenAI Chat Completions API ====================

    @SuppressWarnings("unchecked")
    private String callOpenAiApi(String apiEndpoint, String apiKey, String modelName,
                                 String content, String systemPrompt) {
        String truncated = content.length() > 4000 ? content.substring(0, 4000) : content;

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelName);

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", "以下是邮件正文：\n\n" + truncated);
        messages.add(userMsg);

        requestBody.put("messages", messages);
        requestBody.put("max_tokens", 300);
        requestBody.put("temperature", 0.3);

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        org.springframework.http.HttpEntity<Map<String, Object>> entity =
                new org.springframework.http.HttpEntity<>(requestBody, headers);

        String url = buildApiUrl(apiEndpoint, "/chat/completions");

        Map<String, Object> response = restTemplate.postForObject(url, entity, Map.class);
        if (response != null) {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                if (message != null) {
                    Object contentObj = message.get("content");
                    return contentObj != null ? contentObj.toString().trim() : null;
                }
            }
        }
        return null;
    }

    // ==================== Anthropic Messages API ====================

    @SuppressWarnings("unchecked")
    private String callAnthropicApi(String apiEndpoint, String apiKey, String modelName,
                                    String content, String systemPrompt) {
        String truncated = content.length() > 4000 ? content.substring(0, 4000) : content;

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelName);
        requestBody.put("max_tokens", 300);
        requestBody.put("system", systemPrompt);

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", "以下是邮件正文：\n\n" + truncated);
        messages.add(userMsg);

        requestBody.put("messages", messages);

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        org.springframework.http.HttpEntity<Map<String, Object>> entity =
                new org.springframework.http.HttpEntity<>(requestBody, headers);

        String url = buildApiUrl(apiEndpoint, "/messages");

        Map<String, Object> response = restTemplate.postForObject(url, entity, Map.class);
        if (response != null) {
            // Anthropic 响应格式: { "content": [{ "type": "text", "text": "..." }] }
            List<Map<String, Object>> contentList = (List<Map<String, Object>>) response.get("content");
            if (contentList != null && !contentList.isEmpty()) {
                Map<String, Object> firstBlock = contentList.get(0);
                Object text = firstBlock.get("text");
                return text != null ? text.toString().trim() : null;
            }
        }
        return null;
    }

    // ==================== 工具方法 ====================

    /**
     * 构建完整 API URL
     * 去掉尾部斜杠和已有的目标后缀（防重复），然后拼接目标后缀
     */
    private String buildApiUrl(String baseUrl, String suffix) {
        String url = baseUrl.replaceAll("/+$", "");
        if (url.endsWith(suffix)) {
            url = url.substring(0, url.length() - suffix.length());
        }
        return url + suffix;
    }

    /**
     * 从 llm_config 数据库表获取 LLM 配置（带缓存）
     */
    private Map<String, Object> getLlmConfig() {
        long now = System.currentTimeMillis();
        if (cachedLlmConfig != null && (now - lastConfigLoadTime) < CONFIG_CACHE_MS) {
            return cachedLlmConfig;
        }
        try {
            cachedLlmConfig = pluginService.getLlmConfig();
            lastConfigLoadTime = now;
        } catch (Exception e) {
            System.err.println("[LlmPlugin] 读取LLM配置失败: " + e.getMessage());
        }
        return cachedLlmConfig;
    }
}

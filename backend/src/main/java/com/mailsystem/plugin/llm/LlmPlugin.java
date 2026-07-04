package com.mailsystem.plugin.llm;

import com.mailsystem.entity.Mail;
import com.mailsystem.plugin.PluginInterface;
import com.mailsystem.service.PluginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * LLM大模型插件 — 调用第三方AI API进行智能分析
 * 支持 OpenAI 兼容接口（DeepSeek、通义千问等）
 */
@Component
public class LlmPlugin implements PluginInterface {

    @Autowired
    private PluginService pluginService;

    @Value("${llm.api-endpoint:https://api.openai.com/v1}")
    private String apiEndpoint;

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.model-name:gpt-3.5-turbo}")
    private String modelName;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String getName() {
        return "llmAnalyzer";
    }

    @Override
    @Async
    public void process(Mail mail) {
        if (!isEnabled()) return;
        if (apiKey == null || apiKey.isEmpty()) return;

        try {
            // 生成更准确的摘要
            String summary = callLlm(mail.getBody(),
                    "请用一句话总结以下邮件内容（不超过200字），只返回摘要内容不要额外说明：");
            if (summary != null && !summary.isEmpty()) {
                mail.setSummary(summary);
            }

            // 情感分析
            String sentiment = callLlm(mail.getBody(),
                    "请分析以下邮件的语气和情感倾向，返回一个词：positive/negative/neutral/urgent：");
            // sentiment 暂存不进数据库（Mail 实体无此字段），可在前端展示
            System.out.println("[LlmPlugin] 邮件#" + mail.getId() + " 情感分析: " + sentiment);

        } catch (Exception e) {
            System.err.println("[LlmPlugin] LLM调用失败: " + e.getMessage());
        }
    }

    @Override
    public boolean isEnabled() {
        if (apiKey == null || apiKey.isEmpty()) return false;
        return pluginService.isPluginEnabled("llmAnalyzer");
    }

    /**
     * 调用 LLM API
     */
    private String callLlm(String content, String systemPrompt) {
        if (content == null || content.isEmpty()) return null;

        // 截断过长内容
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
        userMsg.put("content", truncated);
        messages.add(userMsg);

        requestBody.put("messages", messages);
        requestBody.put("max_tokens", 300);
        requestBody.put("temperature", 0.3);

        // 设置请求头
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        org.springframework.http.HttpEntity<Map<String, Object>> entity =
                new org.springframework.http.HttpEntity<>(requestBody, headers);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    apiEndpoint + "/chat/completions", entity, Map.class);

            if (response != null) {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    if (message != null) {
                        return (String) message.get("content");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[LlmPlugin] API请求异常: " + e.getMessage());
        }
        return null;
    }
}

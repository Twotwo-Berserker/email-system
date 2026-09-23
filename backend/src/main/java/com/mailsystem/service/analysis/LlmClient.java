package com.mailsystem.service.analysis;

import com.mailsystem.entity.LlmCallLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM HTTP 客户端 —— 只负责"发一次请求、把响应原样带回来"。
 *
 * <h3>超时不是可选项</h3>
 * <p>
 * 原实现的 {@code new RestTemplate()} 没有任何超时。分析跑在有界线程池里，
 * 一次上游挂起就会永久占住一个线程；几次之后整个分析池被占满，
 * <b>所有邮件的分类都会停摆</b>，而日志上什么也看不到（线程都阻塞在 read 上）。
 * 因此 connect/read 超时从这里注入，且没有默认值为"无超时"的退路。
 * </p>
 *
 * <h3>非 2xx 不抛异常</h3>
 * <p>
 * 默认的 {@code DefaultResponseErrorHandler} 会在 401/429/500 时抛
 * {@code HttpStatusCodeException}，那样就拿不到"是哪个状态码"这个最关键的信息，
 * 只剩下一句被包装过的 message。这里换成一个永不判定为错误的 handler，
 * 让状态码与响应体都能正常返回，由调用方按需处理。
 * </p>
 *
 * <h3>不重试网络层错误</h3>
 * <p>
 * 超时与 5xx 一律不重试：分析是后台异步的，重试只会让已经拥塞的上游更慢，
 * 而且失败后本来就有规则兜底，用户不会看到空白。唯一的重试发生在
 * <b>解析层</b>（见 {@code MailAnalysisServiceImpl.callLlm}）——
 * 那次重试是为了纠正"格式不对"而不是"网络不通"。
 * </p>
 */
@Component
public class LlmClient {

    /** 识别 Anthropic Messages API 的判据 */
    private static final String PROVIDER_ANTHROPIC = "ANTHROPIC";
    private static final String PROVIDER_OPENAI_COMPAT = "OPENAI_COMPAT";

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** 输出上限。结构化 JSON 结论很短，300 足够且能压缩长尾延迟 */
    private static final int MAX_TOKENS = 800;

    /** 温度压低：分类/垃圾判定要的是稳定复现，不是创造力 */
    private static final double TEMPERATURE = 0.1;

    @Value("${app.llm.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${app.llm.read-timeout-ms:12000}")
    private int readTimeoutMs;

    private final RestTemplate restTemplate;

    public LlmClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 先设一次默认值，真实值在首次调用时从 @Value 字段同步过来
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(12000);
        this.restTemplate = new RestTemplate(factory);
        this.restTemplate.setErrorHandler(new NeverFailErrorHandler());
    }

    /**
     * 发起一次调用。
     *
     * @param config       生效配置（{@code apiEndpoint}/{@code apiKey}/{@code modelName}）
     * @param systemPrompt 系统提示词
     * @param userContent  用户消息（已裁剪的邮件正文）
     * @param jsonMode     是否要求上游强制返回 JSON 对象。
     *                     Anthropic Messages API 没有这个参数，会被忽略；
     *                     OpenAI 兼容端点则通过 {@code response_format} 生效。
     */
    public LlmCallOutcome call(Map<String, Object> config, String systemPrompt,
                               String userContent, boolean jsonMode) {
        String endpoint = str(config.get("apiEndpoint"));
        String apiKey = str(config.get("apiKey"));
        String model = str(config.get("modelName"));

        if (endpoint.isEmpty()) {
            return LlmCallOutcome.failure(LlmCallLog.STATUS_SKIPPED_NO_KEY, "NO_ENDPOINT", "未配置 API 端点");
        }
        if (apiKey.isEmpty()) {
            return LlmCallOutcome.failure(LlmCallLog.STATUS_SKIPPED_NO_KEY, "NO_KEY", "未配置 API Key");
        }

        boolean anthropic = isAnthropicEndpoint(endpoint);
        String provider = anthropic ? PROVIDER_ANTHROPIC : PROVIDER_OPENAI_COMPAT;

        // 超时可能被配置改过，每次同步 —— RestTemplate 的工厂是可变对象，
        // 而 @Value 注入发生在构造之后，不这样做会一直用构造时的默认值
        applyTimeouts();

        Map<String, Object> requestBody = anthropic
                ? buildAnthropicBody(model, systemPrompt, userContent)
                : buildOpenAiBody(model, systemPrompt, userContent, jsonMode);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (anthropic) {
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", ANTHROPIC_VERSION);
        } else {
            headers.setBearerAuth(apiKey);
        }

        String url = buildApiUrl(endpoint, anthropic ? "/messages" : "/chat/completions");

        long started = System.currentTimeMillis();
        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(
                    url, new HttpEntity<>(requestBody, headers), String.class);
        } catch (ResourceAccessException e) {
            long elapsed = System.currentTimeMillis() - started;
            boolean timeout = isTimeout(e);
            LlmCallOutcome outcome = LlmCallOutcome.failure(
                    timeout ? LlmCallLog.STATUS_TIMEOUT : LlmCallLog.STATUS_HTTP_ERROR,
                    timeout ? "TIMEOUT" : "CONNECT_FAILED",
                    rootMessage(e));
            outcome.setLatencyMs((int) Math.min(elapsed, Integer.MAX_VALUE));
            outcome.setProvider(provider);
            return outcome;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - started;
            LlmCallOutcome outcome = LlmCallOutcome.failure(
                    LlmCallLog.STATUS_HTTP_ERROR, "REQUEST_FAILED", rootMessage(e));
            outcome.setLatencyMs((int) Math.min(elapsed, Integer.MAX_VALUE));
            outcome.setProvider(provider);
            return outcome;
        }

        int elapsed = (int) Math.min(System.currentTimeMillis() - started, Integer.MAX_VALUE);
        LlmCallOutcome outcome = new LlmCallOutcome();
        outcome.setLatencyMs(elapsed);
        outcome.setProvider(provider);
        outcome.setHttpStatus(response.getStatusCodeValue());

        if (!response.getStatusCode().is2xxSuccessful()) {
            outcome.setStatus(LlmCallLog.STATUS_HTTP_ERROR);
            outcome.setErrorCode("HTTP_" + response.getStatusCodeValue());
            outcome.setErrorMessage(truncate(response.getBody(), 400));
            return outcome;
        }

        String body = response.getBody();
        if (body == null || body.isEmpty()) {
            outcome.setStatus(LlmCallLog.STATUS_PARSE_ERROR);
            outcome.setErrorCode("EMPTY_BODY");
            outcome.setErrorMessage("上游返回空响应体");
            return outcome;
        }

        Map<String, Object> parsed = JsonSupport.parseObject(body);
        if (parsed == null) {
            outcome.setStatus(LlmCallLog.STATUS_PARSE_ERROR);
            outcome.setErrorCode("RESPONSE_NOT_JSON");
            outcome.setErrorMessage(truncate(body, 400));
            return outcome;
        }

        readUsage(parsed, anthropic, outcome);

        String text = anthropic ? extractAnthropicText(parsed) : extractOpenAiText(parsed);
        if (text == null || text.trim().isEmpty()) {
            outcome.setStatus(LlmCallLog.STATUS_PARSE_ERROR);
            outcome.setErrorCode("NO_CONTENT");
            outcome.setErrorMessage("响应中没有可用的文本内容");
            return outcome;
        }

        outcome.setText(text);
        outcome.setStatus(LlmCallLog.STATUS_SUCCESS);
        return outcome;
    }

    private void applyTimeouts() {
        if (restTemplate.getRequestFactory() instanceof SimpleClientHttpRequestFactory) {
            SimpleClientHttpRequestFactory factory =
                    (SimpleClientHttpRequestFactory) restTemplate.getRequestFactory();
            factory.setConnectTimeout(connectTimeoutMs);
            factory.setReadTimeout(readTimeoutMs);
        }
    }

    private Map<String, Object> buildOpenAiBody(String model, String systemPrompt,
                                                String userContent, boolean jsonMode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userContent));
        body.put("messages", messages);

        body.put("max_tokens", MAX_TOKENS);
        body.put("temperature", TEMPERATURE);

        if (jsonMode) {
            // 第一级容错：让上游自己保证"只输出 JSON"。
            // 并非所有 OpenAI 兼容端点都实现了这个参数 —— 不支持时通常忽略它，
            // 宽松处理，因此后续的解析层容错仍然是必需的
            Map<String, String> format = new HashMap<>();
            format.put("type", "json_object");
            body.put("response_format", format);
        }
        return body;
    }

    private Map<String, Object> buildAnthropicBody(String model, String systemPrompt,
                                                   String userContent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", MAX_TOKENS);
        body.put("temperature", TEMPERATURE);
        body.put("system", systemPrompt);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(message("user", userContent));
        body.put("messages", messages);
        return body;
    }

    private static Map<String, String> message(String role, String content) {
        Map<String, String> msg = new HashMap<>();
        msg.put("role", role);
        msg.put("content", content == null ? "" : content);
        return msg;
    }

    @SuppressWarnings("unchecked")
    private String extractOpenAiText(Map<String, Object> response) {
        Object choicesObj = response.get("choices");
        if (!(choicesObj instanceof List) || ((List<?>) choicesObj).isEmpty()) {
            return null;
        }
        Object first = ((List<?>) choicesObj).get(0);
        if (!(first instanceof Map)) {
            return null;
        }
        Object message = ((Map<String, Object>) first).get("message");
        if (!(message instanceof Map)) {
            return null;
        }
        Object content = ((Map<String, Object>) message).get("content");
        return content == null ? null : content.toString();
    }

    @SuppressWarnings("unchecked")
    private String extractAnthropicText(Map<String, Object> response) {
        Object contentObj = response.get("content");
        if (!(contentObj instanceof List)) {
            return null;
        }
        // content 是块数组，可能有多个块（含 thinking 块）。
        // 拼接所有 type=text 的块，而不是只取第 0 块 ——
        // 只取第 0 块在开启扩展思考的模型上会拿到空内容
        StringBuilder sb = new StringBuilder();
        for (Object block : (List<Object>) contentObj) {
            if (!(block instanceof Map)) {
                continue;
            }
            Map<String, Object> b = (Map<String, Object>) block;
            Object type = b.get("type");
            if (type != null && !"text".equals(type.toString())) {
                continue;
            }
            Object text = b.get("text");
            if (text != null) {
                sb.append(text);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 读取 token 用量。
     * <p>
     * 两家的字段名不同：OpenAI 是 {@code prompt_tokens}/{@code completion_tokens}，
     * Anthropic 是 {@code input_tokens}/{@code output_tokens}。
     * 部分兼容端点干脆不返回 usage，因此全部按可缺失处理。
     * </p>
     */
    private void readUsage(Map<String, Object> response, boolean anthropic, LlmCallOutcome outcome) {
        Object usageObj = response.get("usage");
        if (!(usageObj instanceof Map)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) usageObj;

        Integer in = toInt(anthropic ? usage.get("input_tokens") : usage.get("prompt_tokens"));
        Integer out = toInt(anthropic ? usage.get("output_tokens") : usage.get("completion_tokens"));
        Integer total = toInt(usage.get("total_tokens"));

        outcome.setPromptTokens(in);
        outcome.setCompletionTokens(out);
        if (total != null) {
            outcome.setTotalTokens(total);
        } else if (in != null || out != null) {
            // 上游没给总数就自己加，否则统计页的 token 消耗会因上游差异而缺一块
            outcome.setTotalTokens((in == null ? 0 : in) + (out == null ? 0 : out));
        }
    }

    /**
     * 判断是否 Anthropic Messages API。
     * <p>
     * 与 {@code LlmPlugin} 的判据保持一致：只有主机名确实是 anthropic.com 才用
     * Anthropic 格式。DeepSeek、通义、月之暗面等虽然协议不同，但都兼容
     * OpenAI Chat Completions，因此不能靠"非 OpenAI 即 Anthropic"来猜。
     * </p>
     */
    public static boolean isAnthropicEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) {
            return false;
        }
        try {
            String host = URI.create(endpoint).getHost();
            return host != null && host.endsWith("anthropic.com");
        } catch (Exception e) {
            // URL 解析失败（例如用户填了不带 scheme 的地址）时退回关键字判断
            return endpoint.toLowerCase().contains("anthropic");
        }
    }

    /**
     * 只保留主机名，供落库用。
     * <p>
     * 不存完整 URL：部分网关把凭据放在查询串里，落全量 URL 等于把密钥写进数据库。
     * </p>
     */
    public static String endpointHost(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) {
            return null;
        }
        try {
            return URI.create(endpoint).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 拼接完整的 API URL：去掉尾部斜杠、避免把已存在的后缀重复拼一次。
     * <p>
     * 用户既可能填 {@code https://api.openai.com/v1}，也可能直接填
     * {@code https://api.openai.com/v1/chat/completions}。两种都要能用。
     * </p>
     */
    static String buildApiUrl(String baseUrl, String suffix) {
        String url = baseUrl.replaceAll("/+$", "");
        if (url.endsWith(suffix)) {
            url = url.substring(0, url.length() - suffix.length());
        }
        return url + suffix;
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && msg.toLowerCase().contains("timed out")) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return (msg == null || msg.isEmpty()) ? root.getClass().getSimpleName() : msg;
    }

    private static Integer toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 永不判定为错误的错误处理器。
     * <p>
     * 目的是让 4xx/5xx 也以正常的 {@code ResponseEntity} 返回，
     * 从而拿到状态码与响应体用于落库与排查。
     * </p>
     */
    private static class NeverFailErrorHandler implements ResponseErrorHandler {
        @Override
        public boolean hasError(ClientHttpResponse response) throws IOException {
            return false;
        }

        @Override
        public void handleError(ClientHttpResponse response) throws IOException {
            // 不会被调用：hasError 恒为 false
        }
    }
}

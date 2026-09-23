package com.mailsystem.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailsystem.dto.OutboundMessage;
import com.mailsystem.service.OutboundRelayService;
import com.mailsystem.util.MailConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目级发信中继实现 —— Resend HTTP API 与 SMTP 中继两条通道
 *
 * <h3>为什么 Resend 走 HTTP 而不是 SMTP</h3>
 * <p>
 * Resend 也提供 SMTP 接口，但很多云主机（含部分国内厂商）默认封禁出站的
 * 25/465/587 端口，而 443 永远开着。既然它提供了 HTTP API，就没有理由
 * 让用户先去申请解封端口。
 * </p>
 *
 * <h3>超时为什么复用 app.smtp.*</h3>
 * <p>
 * 那是"外发一次网络往返"的预算，与通道无关。分开配会让两条通道在同一台
 * 机器上出现"SMTP 十五秒就放弃、HTTP 卡了两分钟"的不一致 ——
 * 而调用方（{@code MailDeliveryListener}）只按一次投递的超时来设计。
 * </p>
 */
@Service
public class OutboundRelayServiceImpl implements OutboundRelayService {

    /** 通道名：不发送 */
    public static final String TRANSPORT_NONE = "none";

    /** 通道名：Resend HTTP API */
    public static final String TRANSPORT_RESEND = "resend";

    /** 通道名：SMTP 中继 */
    public static final String TRANSPORT_SMTP = "smtp";

    private static final String DEFAULT_RESEND_ENDPOINT = "https://api.resend.com/emails";

    @Value("${app.outbound.transport:none}")
    private String transport;

    @Value("${app.outbound.resend.api-key:}")
    private String resendApiKey;

    @Value("${app.outbound.resend.endpoint:" + DEFAULT_RESEND_ENDPOINT + "}")
    private String resendEndpoint;

    @Value("${app.outbound.smtp.host:}")
    private String relayHost;

    @Value("${app.outbound.smtp.port:587}")
    private int relayPort;

    /** 1=SSL(465), 0=STARTTLS(587) */
    @Value("${app.outbound.smtp.ssl:0}")
    private int relaySsl;

    @Value("${app.outbound.smtp.username:}")
    private String relayUsername;

    @Value("${app.outbound.smtp.password:}")
    private String relayPassword;

    @Value("${app.smtp.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${app.smtp.read-timeout-ms:20000}")
    private int readTimeoutMs;

    @Autowired
    private MailConnectionFactory connectionFactory;

    @Autowired
    private ObjectMapper objectMapper;

    /** 懒建：RestTemplate 的构造会初始化连接工厂，而多数部署用不到它 */
    private volatile RestTemplate restTemplate;

    // ==================== 可用性 ====================

    @Override
    public boolean isAvailable() {
        if (TRANSPORT_RESEND.equalsIgnoreCase(transport)) {
            return !isBlank(resendApiKey);
        }
        if (TRANSPORT_SMTP.equalsIgnoreCase(transport)) {
            return !isBlank(relayHost);
        }
        return false;
    }

    @Override
    public String transportName() {
        return isBlank(transport) ? TRANSPORT_NONE : transport.toLowerCase();
    }

    // ==================== 投递 ====================

    @Override
    public void send(OutboundMessage message) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException(describeUnavailable());
        }
        if (TRANSPORT_RESEND.equalsIgnoreCase(transport)) {
            sendViaResend(message);
        } else {
            sendViaSmtpRelay(message);
        }
    }

    /** 未配置时的说明。刻意讲清楚"缺哪一项、去哪儿补"，而不是一句"中继不可用" */
    private String describeUnavailable() {
        if (TRANSPORT_RESEND.equalsIgnoreCase(transport)) {
            return "发信中继未配置完整：通道为 resend，但缺少 RESEND_API_KEY";
        }
        if (TRANSPORT_SMTP.equalsIgnoreCase(transport)) {
            return "发信中继未配置完整：通道为 smtp，但缺少 RELAY_SMTP_HOST";
        }
        return "本系统未配置发信中继（OUTBOUND_TRANSPORT=none），"
                + "本域邮箱暂时只能收信不能发信。详见 Cloudflare.md 的「发信」一节";
    }

    // ==================== 通道一：Resend ====================

    private void sendViaResend(OutboundMessage message) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", message.fromHeader());
        payload.put("to", message.getTo());
        payload.put("subject", message.getSubject() == null ? "" : message.getSubject());
        // text 而非 html：库里的正文本来就是纯文本（HTML 在收信时已被转成纯文本），
        // 用 html 字段发出去会让 < > 被当成标签吞掉
        payload.put("text", message.getBody() == null ? "" : message.getBody());

        if (!message.getAttachments().isEmpty()) {
            List<Map<String, String>> attachments = new ArrayList<>();
            for (OutboundMessage.OutboundAttachment att : message.getAttachments()) {
                Map<String, String> item = new HashMap<>();
                item.put("filename", att.getFileName());
                item.put("content", Base64.getEncoder().encodeToString(att.getData()));
                attachments.add(item);
            }
            payload.put("attachments", attachments);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(resendApiKey);

        ResponseEntity<String> response;
        try {
            response = restTemplate().postForEntity(resendEndpoint,
                    new HttpEntity<>(objectMapper.writeValueAsString(payload), headers), String.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // Resend 把失败原因放在响应体里（如"domain is not verified"），
            // 只报 HTTP 状态码用户没法排查，必须把这段原文带出去
            throw new RuntimeException("Resend 拒绝了本次投递（HTTP " + e.getRawStatusCode()
                    + "）：" + extractResendError(e.getResponseBodyAsString()));
        }

        if (response.getStatusCode() != HttpStatus.OK) {
            throw new RuntimeException("Resend 投递失败：HTTP " + response.getStatusCodeValue()
                    + " " + extractResendError(response.getBody()));
        }
    }

    /** 从 Resend 的错误响应里取出可读信息，取不到就原样返回（截断） */
    private String extractResendError(String body) {
        if (body == null || body.isEmpty()) {
            return "（无响应内容）";
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode message = node.get("message");
            if (message != null && !message.isNull()) {
                return message.asText();
            }
            JsonNode error = node.get("error");
            if (error != null && !error.isNull()) {
                return error.isTextual() ? error.asText() : error.toString();
            }
        } catch (Exception e) {
            // 不是 JSON：多半是网关返回的 HTML 错误页，原样带出去更有用
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    /**
     * 懒初始化并缓存。
     * <p>
     * 必须显式设超时：{@code RestTemplate} 默认用的是 JDK 的
     * {@code HttpURLConnection}，而它的 connectTimeout/readTimeout 默认为
     * <b>无限</b> —— 上游不响应时，投递线程会一直挂着。
     * </p>
     */
    private RestTemplate restTemplate() {
        RestTemplate cached = restTemplate;
        if (cached == null) {
            synchronized (this) {
                cached = restTemplate;
                if (cached == null) {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(connectTimeoutMs);
                    factory.setReadTimeout(readTimeoutMs);
                    cached = new RestTemplate(factory);
                    restTemplate = cached;
                }
            }
        }
        return cached;
    }

    // ==================== 通道二：SMTP 中继 ====================

    private void sendViaSmtpRelay(OutboundMessage message) throws Exception {
        JavaMailSenderImpl sender = connectionFactory.buildSender(
                relayHost, relayPort, relaySsl == 1,
                isBlank(relayUsername) ? null : relayUsername, relayPassword,
                connectTimeoutMs, readTimeoutMs);

        MimeMessage mime = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
        helper.setFrom(message.getFromAddress(),
                isBlank(message.getFromName()) ? null : message.getFromName());
        helper.setTo(message.getTo().toArray(new String[0]));
        helper.setSubject(message.getSubject() == null ? "" : message.getSubject());
        helper.setText(message.getBody() == null ? "" : message.getBody(), false);

        for (OutboundMessage.OutboundAttachment att : message.getAttachments()) {
            helper.addAttachment(att.getFileName(),
                    new org.springframework.core.io.ByteArrayResource(att.getData()));
        }

        sender.send(mime);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}

package com.mailsystem.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailsystem.dto.CustomProtocolEnvelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 自定义协议过滤器
 * 当 protocol.custom.enabled=true 时，自动包装/解包 CustomProtocolEnvelope
 */
@Component
public class ProtocolFilter extends OncePerRequestFilter {

    @Value("${protocol.custom.enabled:false}")
    private boolean enabled;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        // 包装响应以便读取内容
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        filterChain.doFilter(request, responseWrapper);

        // 将 ApiResponse 包装进 CustomProtocolEnvelope
        byte[] content = responseWrapper.getContentAsByteArray();
        if (content.length > 0) {
            try {
                Object originalResponse = objectMapper.readValue(content, Object.class);

                CustomProtocolEnvelope envelope = new CustomProtocolEnvelope();
                envelope.setFunctionCode("MAIL_RESPONSE");
                envelope.setTimestamp(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                envelope.setPayload(originalResponse);

                String envelopeJson = objectMapper.writeValueAsString(envelope);
                responseWrapper.resetBuffer();
                responseWrapper.getOutputStream().write(envelopeJson.getBytes());
            } catch (Exception e) {
                // 解析失败时回退，写出原始内容
                responseWrapper.resetBuffer();
                responseWrapper.getOutputStream().write(content);
            }
        }

        responseWrapper.copyBodyToResponse();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // WebSocket 升级请求不经过此过滤器
        String upgrade = request.getHeader("Upgrade");
        return !enabled || "websocket".equalsIgnoreCase(upgrade);
    }
}

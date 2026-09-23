package com.mailsystem.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket + STOMP 配置
 * 用于实时邮件推送通知
 * <p>
 * 入站通道上挂了 {@link WebSocketAuthInterceptor} 做认证与订阅授权 ——
 * HTTP 层的 {@code JwtInterceptor} 管不到这条路径（浏览器的 WebSocket
 * 握手持带不了 Authorization 头），因此 {@code /ws/**} 在 WebConfig 里被放行，
 * 由 STOMP 层的拦截器守门。
 * </p>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Autowired
    private WebSocketAuthInterceptor webSocketAuthInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 客户端订阅前缀（服务端 → 客户端推送）
        registry.enableSimpleBroker("/topic");
        // 客户端发送消息前缀（客户端 → 服务端）
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // WebSocket 连接端点，支持 SockJS 降级
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    /**
     * 入站通道挂认证拦截器：CONNECT 校验 Token，SUBSCRIBE 校验订阅目标。
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketAuthInterceptor);
    }
}

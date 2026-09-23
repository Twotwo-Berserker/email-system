package com.mailsystem.config;

import com.mailsystem.service.TokenBlacklistService;
import com.mailsystem.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSocket 认证与订阅授权 —— STOMP 层的入口检查。
 *
 * <h3>为什么不能只靠 HTTP 层的 {@link JwtInterceptor}</h3>
 * <p>
 * 浏览器的 WebSocket / SockJS 握手是<b>普通 GET 请求，带不了 Authorization 头</b>
 * （SockJS 走 XHR 或 iframe，没有任何地方能塞自定义头）。因此 HTTP 层的
 * JwtInterceptor 对这一条路径只有两个选择：放行（等于不认证）或拒绝
 * （WebSocket 永远连不上，静默退化成 30 秒轮询）。
 * </p>
 * <p>
 * 真正的认证点只能是 STOMP 的 CONNECT 帧 —— 那一帧的 header 由客户端代码自由设置，
 * 可以携带 Token。所以 {@code /ws/**} 从 HTTP 层放行，由本拦截器守门。
 * </p>
 *
 * <h3>只认证 CONNECT 还不够：必须同时校验订阅目标</h3>
 * <p>
 * 推送目的地是 {@code /topic/user/{userId}}，一个<b>公开的 broker 主题</b>。
 * 只要连接建立成功，任何人都能 SUBSCRIBE 到 {@code /topic/user/1} 收走别人的
 * 新邮件通知（含发件人与主题）。仅校验"你是谁"挡不住这个 ——
 * 必须校验"你要订阅的主题是不是你自己的"。见 {@link #authorizeSubscribe}。
 * </p>
 *
 * <h3>与 HTTP 层一致的吊销检查</h3>
 * <p>
 * 登录态吊销（登出、账号被禁用、密码被重置）在 HTTP 层由 JwtInterceptor 逐请求检查。
 * WebSocket 只在建立连接时检查一次，因此一个被禁用的账号在已有连接上
 * 仍能继续收推送 —— 这是长连接的固有限制。连接建立时把吊销状态查一遍，
 * 至少保证"禁用后新发起的连接"进不来；已建立的连接则依赖前端在收到
 * 401 后重连失败而断开。
 * </p>
 */
@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    /** 只允许订阅自己的推送主题，且不接受任何多余路径段 */
    private static final Pattern USER_TOPIC = Pattern.compile("^/topic/user/(\\d+)$");

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command) || StompCommand.STOMP.equals(command)) {
            authenticate(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            authorizeSubscribe(accessor);
        }
        return message;
    }

    /**
     * 校验 CONNECT 帧里的 Token，并把 userId 记进会话属性。
     * <p>
     * 校验失败一律抛异常：Spring 会回一个 ERROR 帧并关闭连接。这里不能"只记日志
     * 然后放行" —— 放行会让未认证的连接留在 broker 上，而它想订阅什么就订阅什么。
     * </p>
     */
    private void authenticate(StompHeaderAccessor accessor) {
        // 依次尝试三种写法：标准头、全小写（部分客户端会规范化 header 名）、
        // 以及只放裸 token 的写法。都是实际会遇到的形式，不是穷举猜测
        String token = accessor.getFirstNativeHeader("Authorization");
        if (token == null) {
            token = accessor.getFirstNativeHeader("authorization");
        }
        if (token == null) {
            token = accessor.getFirstNativeHeader("token");
        }
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("WebSocket 连接被拒绝：缺少认证 Token");
        }
        String trimmed = token.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            trimmed = trimmed.substring(7).trim();
        }

        if (!jwtUtil.validateToken(trimmed)) {
            throw new IllegalArgumentException("WebSocket 连接被拒绝：Token 无效或已过期");
        }
        if (tokenBlacklistService.isBlacklisted(jwtUtil.getJtiFromToken(trimmed))) {
            throw new IllegalArgumentException("WebSocket 连接被拒绝：登录状态已失效");
        }

        Long userId = jwtUtil.getUserIdFromToken(trimmed);
        if (userId == null) {
            throw new IllegalArgumentException("WebSocket 连接被拒绝：Token 中缺少用户标识");
        }
        if (tokenBlacklistService.isUserRevoked(userId, jwtUtil.getIssuedAtFromToken(trimmed))) {
            throw new IllegalArgumentException("WebSocket 连接被拒绝：账号状态已变更");
        }

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null) {
            sessionAttributes.put(SESSION_USER_ID, userId);
        }
    }

    /**
     * 订阅目标必须恰好是当前连接所属用户的推送主题。
     * <p>
     * 白名单而不是黑名单：只有 {@code /topic/user/{自己}} 放行，其余一律拒绝。
     * 反过来的写法（"拒绝 /topic/user/{别人}"）会在将来新增一个
     * {@code /topic/admin/**} 之类的主题时自动放行 —— 而那个主题大概率更敏感。
     * </p>
     */
    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        Object sessionUserId = sessionUserId(accessor);

        if (sessionUserId == null) {
            // 没有经过认证的 CONNECT 就订阅，说明客户端绕过了协议顺序
            throw new IllegalArgumentException("WebSocket 订阅被拒绝：连接未认证");
        }
        if (destination == null) {
            throw new IllegalArgumentException("WebSocket 订阅被拒绝：缺少订阅目标");
        }

        Matcher matcher = USER_TOPIC.matcher(destination);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("WebSocket 订阅被拒绝：不允许订阅 " + destination);
        }
        if (!String.valueOf(sessionUserId).equals(matcher.group(1))) {
            // 这条日志值得留：它是"有人在尝试读别人的通知"的直接证据
            System.err.println("[WebSocket] 用户#" + sessionUserId
                    + " 试图订阅他人的推送主题 " + destination + "，已拒绝");
            throw new IllegalArgumentException("WebSocket 订阅被拒绝：不能订阅其他用户的主题");
        }
    }

    /**
     * 取当前会话的 userId。
     * <p>
     * 先看 STOMP 会话属性（CONNECT 时写入），再回落到 Spring 的
     * {@code user} Principal —— 后者是另一种等价的身份载体，
     * 两条路都留着以免将来加了 {@code HandshakeHandler} 后这里突然取不到值。
     * </p>
     */
    private Object sessionUserId(StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null && sessionAttributes.get(SESSION_USER_ID) != null) {
            return sessionAttributes.get(SESSION_USER_ID);
        }
        return accessor.getUser() == null ? null : accessor.getUser().getName();
    }

    /** 会话属性键；由本类写入、由本类读取，不外泄 */
    public static final String SESSION_USER_ID = "wsUserId";
}

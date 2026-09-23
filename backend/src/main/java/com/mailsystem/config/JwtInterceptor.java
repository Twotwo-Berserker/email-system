package com.mailsystem.config;

import com.mailsystem.service.TokenBlacklistService;
import com.mailsystem.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * JWT 认证拦截器
 * 从 Authorization Header 中提取 Token 并校验
 * <p>
 * 四类校验：签名与有效期（{@link JwtUtil#validateToken}）、是否已被登出吊销
 * （按 jti）、是否已被用户级吊销（账号禁用 / 密码重置）、角色。角色校验不在这里
 * 做 —— 那需要一次数据库查询，只在 /admin/** 与 /plugin/** 上由
 * {@link AdminInterceptor} 承担，避免给所有请求都加一次查库。
 * </p>
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // OPTIONS 预检请求放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        if (token == null || token.isEmpty()) {
            return writeUnauthorized(response, "未登录或Token已过期");
        }

        if (!jwtUtil.validateToken(token)) {
            return writeUnauthorized(response, "Token无效或已过期");
        }

        // 已登出吊销的 Token 即使签名有效也必须拒绝
        if (tokenBlacklistService.isBlacklisted(jwtUtil.getJtiFromToken(token))) {
            return writeUnauthorized(response, "登录状态已失效，请重新登录");
        }

        // 将用户信息存入 request attribute
        Long userId = jwtUtil.getUserIdFromToken(token);
        String email = jwtUtil.getEmailFromToken(token);

        // 账号被禁用 / 密码被管理员重置后，此前签发的 Token 立即失效。
        // 不做这一步的话，"禁用"只挡得住下一次登录，已登录的会话要等到
        // Token 自然过期（jwt.expiration）才失效 —— 管理员会以为已经踢下线了。
        if (tokenBlacklistService.isUserRevoked(userId, jwtUtil.getIssuedAtFromToken(token))) {
            return writeUnauthorized(response, "账号状态已变更，请重新登录");
        }
        request.setAttribute("userId", userId);
        request.setAttribute("email", email);

        return true;
    }

    private boolean writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(401);
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}");
        return false;
    }
}

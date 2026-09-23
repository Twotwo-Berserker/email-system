package com.mailsystem.config;

import com.mailsystem.entity.User;
import com.mailsystem.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 管理员权限拦截器
 * <p>
 * 修复核查中发现的越权问题：原先 {@code /plugin/llm/config} 与
 * {@code /plugin/llm/configure} 没有任何权限校验，任何已登录用户都能
 * 读取全局 LLM API Key（明文回传）并覆盖它。
 * </p>
 * <p>
 * 注册在 {@link JwtInterceptor} <b>之后</b>，因此可以直接从 request attribute
 * 取 userId，无需重复解析 Token。拦截器按注册顺序执行，这个先后关系是依赖。
 * </p>
 * <p>
 * 角色不放进 JWT：放进去虽然省一次查库，但角色变更（降级、禁用）要等
 * Token 过期才生效。这里每次查库，保证管理权限的收回是立即的。
 * </p>
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    @Autowired
    private UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        Object userIdAttr = request.getAttribute("userId");
        if (!(userIdAttr instanceof Long)) {
            return writeForbidden(response, 401, "未登录或Token已过期");
        }

        User user = userMapper.selectById((Long) userIdAttr);
        if (user == null) {
            return writeForbidden(response, 401, "用户不存在");
        }

        // 被禁用的账号即使持有有效 Token 也不能访问管理接口
        if (user.getStatus() != null && user.getStatus() == User.STATUS_DISABLED) {
            return writeForbidden(response, 403, "账号已被禁用");
        }

        if (!user.isAdmin()) {
            return writeForbidden(response, 403, "需要管理员权限");
        }

        // 供控制器使用，避免再查一次库
        request.setAttribute("role", user.getRole());
        return true;
    }

    private boolean writeForbidden(HttpServletResponse response, int code, String message) throws Exception {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(code);
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}");
        return false;
    }
}

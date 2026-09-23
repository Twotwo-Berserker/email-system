package com.mailsystem.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Nonnull;

/**
 * Web配置 — CORS + JWT拦截器 + 管理员权限拦截器
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    @Nonnull
    private JwtInterceptor jwtInterceptor;

    @Autowired
    @Nonnull
    private AdminInterceptor adminInterceptor;

    /**
     * 配置CORS跨域
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 注册拦截器。
     * <p>
     * <b>顺序是依赖关系，不可调换：</b>AdminInterceptor 直接读取
     * JwtInterceptor 写入的 {@code userId} request attribute，
     * 因此必须注册在它之后（拦截器按注册顺序执行）。
     * </p>
     * <p>
     * {@code /plugin/**} 整体挂管理员权限：这些接口是<b>系统级</b>配置
     * （插件总开关、全局 LLM 配置）。其中
     * {@code GET /plugin/llm/config} 原先把 API Key 明文回传给任何登录用户，
     * {@code PUT /plugin/llm/configure} 允许任何登录用户覆盖它 ——
     * 整体挂载可以避免将来新增子路径时漏配。
     * 普通用户自己的 LLM 配置走 {@code /user/llm-config}。
     * </p>
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/user/login",
                        "/user/register",
                        "/health",
                        "/error",
                        // WebSocket/SockJS 握手是普通 GET，浏览器塞不进 Authorization 头，
                        // 因此这一层只能放行 —— 否则 WebSocket 永远连不上，只能退化成
                        // 30 秒轮询。真正的认证在 STOMP 的 CONNECT 帧上，见
                        // WebSocketAuthInterceptor（含订阅目标授权，防止订阅他人的主题）
                        "/ws/**",
                        // Cloudflare Email Worker 的来信投递入口。它同样拿不到用户
                        // Token —— 调用方是 Cloudflare 的边缘节点。放行的前提是
                        // InboundMailController 自己做了 HMAC 签名校验，
                        // 且默认关闭（app.inbound.enabled=false 时直接 503）
                        "/inbound/**"
                );

        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/admin/**", "/plugin/**")
                .excludePathPatterns("/error");
    }
}

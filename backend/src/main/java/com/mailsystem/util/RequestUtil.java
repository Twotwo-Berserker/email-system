package com.mailsystem.util;

import javax.servlet.http.HttpServletRequest;

/**
 * 请求工具
 */
public final class RequestUtil {

    /** 反向代理传递真实客户端IP的标准头，按优先级排列 */
    private static final String[] IP_HEADERS = {
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP"
    };

    private RequestUtil() {
    }

    /**
     * 取客户端真实IP。
     * <p>
     * 本项目的部署形态是 Nginx 反代后端，直连时 {@code getRemoteAddr()} 拿到的
     * 是 Nginx 容器地址（恒定），登录审计会失去意义，因此优先读代理头。
     * </p>
     * <p>
     * 注意：{@code X-Forwarded-For} 是可以被客户端伪造的。这里只用于审计展示，
     * 不用于任何访问控制决策 —— 若将来要基于IP做风控，必须改成
     * "只信任来自已知代理的那一跳"。
     * </p>
     */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        for (String header : IP_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isEmpty() && !"unknown".equalsIgnoreCase(value)) {
                // X-Forwarded-For 可能是 "client, proxy1, proxy2"，取第一跳
                int comma = value.indexOf(',');
                String ip = (comma > 0 ? value.substring(0, comma) : value).trim();
                if (!ip.isEmpty()) {
                    return truncate(ip);
                }
            }
        }
        return truncate(request.getRemoteAddr());
    }

    /** user.last_login_ip 是 VARCHAR(64)，超长会被数据库截断报错，这里先截 */
    private static String truncate(String ip) {
        if (ip == null) {
            return null;
        }
        return ip.length() > 64 ? ip.substring(0, 64) : ip;
    }
}

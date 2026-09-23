package com.mailsystem.util;

import io.jsonwebtoken.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 工具类 — 生成、校验、解析 Token
 * <p>
 * Token 携带 {@code jti}（JWT ID），用于登出时精确吊销：
 * 无状态 JWT 本身无法撤销，原实现的"登出"只是前端删除 localStorage，
 * Token 在 24 小时有效期内仍可继续使用。有了 jti 才能把它写进 Redis 黑名单。
 * </p>
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    /**
     * 生成 JWT Token
     */
    public String generateToken(Long userId, String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("email", email);

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(email)
                .setId(UUID.randomUUID().toString().replace("-", ""))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(SignatureAlgorithm.HS256, secret)
                .compact();
    }

    /**
     * 从 Token 中解析用户ID
     */
    public Long getUserIdFromToken(String token) {
        try {
            Claims claims = parseToken(token);
            return claims != null ? Long.valueOf(claims.get("userId").toString()) : null;
        } catch (Exception e) {
            System.err.println("[JwtUtil] 解析userId失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从 Token 中解析邮箱
     */
    public String getEmailFromToken(String token) {
        try {
            Claims claims = parseToken(token);
            return claims != null ? claims.getSubject() : null;
        } catch (Exception e) {
            System.err.println("[JwtUtil] 解析email失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从 Token 中解析 jti（吊销标识）。
     * 解析失败返回 null —— 调用方据此跳过黑名单检查而非拒绝请求，
     * 因为一个连 jti 都解析不出的 Token 也过不了 {@link #validateToken}。
     */
    public String getJtiFromToken(String token) {
        try {
            Claims claims = parseToken(token);
            return claims != null ? claims.getId() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Token 的签发时间（epoch 毫秒）。解析失败或无 iat 返回 0。
     * <p>
     * 用于"账号被禁用/密码被重置后，此前签发的 Token 立即失效"这一判断：
     * 与 Redis 中的吊销时间戳比较，签发早于吊销时刻的 Token 一律拒绝。
     * </p>
     * <p>
     * 返回 0 时调用方应<b>跳过</b>该判断而不是拒绝 —— 本项目签发的 Token
     * 一定带 iat（见 {@link #generateToken}），而伪造一个不带 iat 的 Token
     * 过不了签名校验，所以"取不到签发时间"不构成可利用的绕过。
     * </p>
     */
    public long getIssuedAtFromToken(String token) {
        try {
            Claims claims = parseToken(token);
            Date issuedAt = claims.getIssuedAt();
            return issuedAt == null ? 0 : issuedAt.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Token 剩余有效期（毫秒）。已过期或解析失败返回 0。
     * <p>
     * 用作黑名单条目的 TTL —— 没有必要在 Token 自然过期后还留着黑名单记录。
     * </p>
     */
    public long getRemainingValidityMs(String token) {
        try {
            Claims claims = parseToken(token);
            Date exp = claims.getExpiration();
            if (exp == null) {
                return 0;
            }
            long remaining = exp.getTime() - System.currentTimeMillis();
            return Math.max(remaining, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 校验 Token 是否有效
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            System.err.println("[JwtUtil] Token校验失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 解析 Token Claims（不吞异常，让调用方处理）
     */
    private Claims parseToken(String token) {
        return Jwts.parser()
                .setSigningKey(secret)
                .parseClaimsJws(token)
                .getBody();
    }
}

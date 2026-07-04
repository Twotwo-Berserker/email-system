package com.mailsystem.util;

import io.jsonwebtoken.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类 — 生成、校验、解析 Token
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

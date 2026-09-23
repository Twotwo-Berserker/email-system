package com.mailsystem.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 密码哈希工具 —— BCrypt 存储 + 历史 SHA-256 透明兼容
 *
 * <h3>为什么要做双算法兼容</h3>
 * <p>
 * 原实现是<b>无盐单次 SHA-256</b>（{@code Base64(SHA256(password))}）：
 * 无盐意味着相同密码产生相同哈希，可直接用彩虹表批量还原；
 * 单次意味着 GPU 每秒可尝试数十亿次。这两个缺陷叠加，
 * 一旦数据库泄露，绝大多数用户密码会立刻被还原。
 * </p>
 * <p>
 * 但直接在库里做一次性迁移是做不到的：SHA-256 不可逆，无法从旧哈希推导出
 * BCrypt 哈希。因此采用<b>登录时透明重哈希</b>：
 * 旧哈希仍可校验通过，校验成功后立刻用原密码重写为 BCrypt。
 * 用户无感，且活跃用户会在首次登录后自动完成升级。
 * </p>
 * <p>
 * 代价是：长期不登录的账号会一直停留在弱哈希上。可在管理端按
 * {@code password NOT LIKE '$2%'} 查出这些账号并强制重置。
 * </p>
 */
@Component
public class PasswordUtil {

    /** BCrypt 哈希的识别前缀（$2a$ / $2b$ / $2y$） */
    private static final String BCRYPT_PREFIX = "$2";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * 生成 BCrypt 哈希（用于注册、改密、管理员重置）
     */
    public String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * 校验密码。自动识别存量算法：
     * <ul>
     *   <li>BCrypt 开头 → 交给 BCrypt 校验</li>
     *   <li>否则 → 按历史 SHA-256 校验</li>
     * </ul>
     */
    public boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null || storedHash.isEmpty()) {
            return false;
        }
        if (storedHash.startsWith(BCRYPT_PREFIX)) {
            try {
                return encoder.matches(rawPassword, storedHash);
            } catch (IllegalArgumentException e) {
                // 哈希串格式损坏，视为校验失败而不是抛异常
                return false;
            }
        }
        // 历史无盐 SHA-256
        return constantTimeEquals(legacySha256(rawPassword), storedHash);
    }

    /**
     * 该哈希是否需要升级为 BCrypt
     */
    public boolean needsRehash(String storedHash) {
        return storedHash == null || !storedHash.startsWith(BCRYPT_PREFIX);
    }

    /**
     * 历史算法：Base64(SHA-256(password))，无盐。
     * 仅为兼容存量数据而保留，新密码一律不再使用。
     */
    private String legacySha256(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 定长比较，避免通过响应时间差逐字节猜测哈希。
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}

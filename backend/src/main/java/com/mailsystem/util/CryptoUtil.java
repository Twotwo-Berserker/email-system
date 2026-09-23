package com.mailsystem.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭据加解密工具（AES-256-GCM）
 * <p>
 * 用于两类敏感数据的落库加密：
 * </p>
 * <ul>
 *   <li>{@code mail_account.smtp_password_enc} / {@code imap_password_enc} —— 邮箱授权码</li>
 *   <li>{@code llm_config.api_key} —— 大模型 API 密钥</li>
 * </ul>
 *
 * <h3>密文格式</h3>
 * <pre>
 *   enc:v1:&lt;Base64(IV ‖ CipherText‖Tag)&gt;
 * </pre>
 * <p>
 * 带前缀是为了支持<b>透明迁移</b>：历史数据中 {@code llm_config.api_key} 是明文，
 * {@link #decrypt} 遇到不带前缀的值会原样返回，因此老数据无需一次性刷库，
 * 下次写入时自然变成密文。
 * </p>
 *
 * <h3>密钥缺失时刻意快速失败</h3>
 * <p>
 * 若 {@code app.crypto.aes-key} 为空就启动，只有两种可能：要么给一个硬编码默认密钥
 * （等价于不加密，且极易被带进生产），要么用随机临时密钥（重启后历史凭据全部无法解密，
 * 且是静默的数据损坏）。两者都不可接受，因此这里在启动阶段直接抛出异常，
 * 并在报错信息里给出生成命令。
 * </p>
 */
@Component
public class CryptoUtil {

    /** 密文前缀，同时承载版本号，便于将来轮换算法 */
    private static final String PREFIX = "enc:v1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;          // GCM 推荐 96 bit
    private static final int TAG_LENGTH_BIT = 128;
    private static final int AES_KEY_BYTES = 32;      // AES-256

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.crypto.aes-key:}")
    private String configuredKey;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        if (configuredKey == null || configuredKey.trim().isEmpty()) {
            throw new IllegalStateException(
                    "缺少配置 app.crypto.aes-key（环境变量 AES_SECRET_KEY）。\n" +
                    "该密钥用于加密邮箱授权码与 LLM API Key，不允许使用默认值或缺省。\n" +
                    "请生成一个 32 字节密钥并写入 .env：\n" +
                    "  openssl rand -base64 32\n" +
                    "  AES_SECRET_KEY=<上述输出>");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(configuredKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "app.crypto.aes-key 不是合法的 Base64。请用 `openssl rand -base64 32` 重新生成。", e);
        }
        if (keyBytes.length != AES_KEY_BYTES) {
            throw new IllegalStateException(
                    "app.crypto.aes-key 解码后长度为 " + keyBytes.length + " 字节，要求 " + AES_KEY_BYTES + " 字节（AES-256）。\n" +
                    "请用 `openssl rand -base64 32` 重新生成。");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 加密。空值直接返回 null，避免在库里产生 "enc:v1:..." 形式的空串。
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // 不把明文写进异常信息
            throw new IllegalStateException("凭据加密失败: " + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 解密。
     * <p>
     * 对不带 {@value #PREFIX} 前缀的历史明文数据，原样返回——这样老数据无需刷库即可继续可用。
     * </p>
     */
    public String decrypt(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (!value.startsWith(PREFIX)) {
            // 历史明文，透明放行（下次写入时会转成密文）
            return value;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if (combined.length <= IV_LENGTH) {
                throw new IllegalArgumentException("密文长度异常");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BIT, iv));

            byte[] plain = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 最常见原因：AES_SECRET_KEY 被更换过。此处不打印密文本身。
            throw new IllegalStateException(
                    "凭据解密失败，通常意味着 AES_SECRET_KEY 与加密时不一致，需重新录入凭据", e);
        }
    }

    /**
     * 判断是否为加密值
     */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * 掩码回显 —— 用于接口返回，避免把明文密钥发给前端。
     * <pre>
     *   "sk-proj-abcdefghijklmn"  ->  "sk-p****klmn"
     *   "abcd"                    ->  "****"      （过短，全部掩掉）
     *   null / ""                 ->  ""          （未配置）
     * </pre>
     */
    public String mask(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    /**
     * 从明文凭据派生掩码，供"保存时同时落一个明文尾 4 位用于回显"的场景使用。
     * 避免为了回显而把密文解密。
     */
    public String last4(String value) {
        if (value == null || value.length() < 4) {
            return "";
        }
        return value.substring(value.length() - 4);
    }
}

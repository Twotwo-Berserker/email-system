package com.mailsystem.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 入站 Webhook 的签名校验（HMAC-SHA256）
 *
 * <h3>为什么需要它</h3>
 * <p>
 * {@code POST /inbound/cloudflare} 是一个<b>匿名可达</b>的写接口 —— 它必须匿名，
 * 因为调用方是 Cloudflare 的边缘节点，没法持有本系统的用户 JWT。但一个匿名
 * 写接口如果不校验来源，任何人都能往任意用户的收件箱里塞邮件，而邮件内容
 * 随后会被送进 LLM 分析（花的是用户的 token 额度）。
 * </p>
 * <p>
 * 因此共享密钥是必需的。它与 {@code JwtInterceptor} 是两套独立机制：
 * 前者认"人"，后者认"机器"。
 * </p>
 *
 * <h3>签名内容里为什么要带时间戳</h3>
 * <p>
 * 只签报文体的话，这个签名是<b>永久有效</b>的 —— 任何一次抓包（或者日志里的
 * 一条记录）都能被原样重放，反复往收件箱里投同一封信。把时间戳并入待签名内容
 * 并限制它的新鲜度，重放窗口就收敛到 {@link #MAX_SKEW_SECONDS} 秒。
 * </p>
 *
 * <h3>为什么用 MessageDigest.isEqual 而不是 String.equals</h3>
 * <p>
 * 后者是短路比较，逐字节相同时才继续下一位 —— 攻击者能通过测量响应时间
 * 逐字节猜出正确的签名。{@code MessageDigest.isEqual} 在 JDK 中实现为
 * 常数时间比较，不会泄露"猜对了多少位"。
 * </p>
 */
public final class HmacVerifier {

    /** 允许的时间偏差：签名时间距现在超过它就判定为过期或伪造 */
    public static final long MAX_SKEW_SECONDS = 300L;

    private static final String ALGORITHM = "HmacSHA256";

    /** 签名头的前缀，用于将来换算法时并存（{@code v2=...}） */
    public static final String SIGNATURE_PREFIX = "v1=";

    private HmacVerifier() {
    }

    /**
     * 计算 {@code HMAC-SHA256(secret, timestamp + "." + body)} 的十六进制小写表示。
     * <p>
     * 时间戳参与进来是为了防重放（见类注释），分隔符 {@code .} 不能省：
     * 少了它，{@code timestamp="12", body="3..."} 与 {@code timestamp="1", body="23..."}
     * 会算出同一个待签名串，签名本身就成了歧义的。
     * </p>
     *
     * @param body 原始请求体（<b>必须是字节</b>，不能先反序列化再重新序列化 ——
     *             否则字段顺序与空白字符的差异会让签名对不上）
     */
    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            byte[] digest = mac.doFinal(body);
            return toHex(digest);
        } catch (Exception e) {
            // 算法必然存在、密钥必然非空（调用方已校验），到这里说明运行环境异常
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    /**
     * 校验签名是否有效。
     *
     * @param submitted 请求头里带 {@code v1=} 前缀的签名值
     * @return 签名正确且时间戳在允许偏差内
     */
    public static boolean verify(String secret, String timestamp, byte[] body, String submitted) {
        if (secret == null || secret.isEmpty() || submitted == null || timestamp == null) {
            return false;
        }
        if (!isFresh(timestamp)) {
            return false;
        }

        String value = submitted.startsWith(SIGNATURE_PREFIX)
                ? submitted.substring(SIGNATURE_PREFIX.length())
                : submitted;

        String expected = sign(secret, timestamp, body);
        // 常数时间比较，见类注释
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                value.getBytes(StandardCharsets.UTF_8));
    }

    /** 时间戳是否为合法的 Unix 秒且落在允许的偏差窗口内 */
    public static boolean isFresh(String timestamp) {
        try {
            long seconds = Long.parseLong(timestamp.trim());
            long now = System.currentTimeMillis() / 1000L;
            return Math.abs(now - seconds) <= MAX_SKEW_SECONDS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}

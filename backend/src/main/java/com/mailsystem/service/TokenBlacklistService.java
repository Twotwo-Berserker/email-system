package com.mailsystem.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * JWT 吊销名单
 * <p>
 * 无状态 JWT 的固有缺陷：签发后无法撤回。原实现的"退出登录"只清了前端
 * localStorage，Token 在剩余有效期内仍能通过校验，被窃取后无法止损。
 * </p>
 * <p>
 * 这里用 Redis 存已吊销的 {@code jti}，TTL 设为该 Token 的剩余有效期 ——
 * 反正过了有效期校验本来就会失败，没必要让黑名单无限增长。
 * </p>
 *
 * <h3>Redis 不可用时选择"放行"而不是"全拒"</h3>
 * <p>
 * {@link #isBlacklisted} 与 {@link #isUserRevoked} 在 Redis 异常时返回 false
 * （视为未吊销），并打印告警。这是一个刻意的取舍：若改为"全拒"，一次 Redis
 * 抖动会让所有在线用户被登出，而放行的代价仅仅是"登出后旧 Token 在剩余有效期
 * 内仍可用"这一既有缺陷继续存在（不会比改动前更糟）。若该部署对吊销有强要求，
 * 应改为全拒并接受可用性下降。
 * </p>
 *
 * <h3>两种吊销粒度</h3>
 * <ul>
 *   <li><b>按 jti</b>（{@link #blacklist}）—— 单个 Token 登出，精确到一次会话</li>
 *   <li><b>按用户</b>（{@link #revokeAllForUser}）—— 账号被禁用或密码被管理员
 *       重置时，一次性作废该用户<i>此前签发的全部 Token</i></li>
 * </ul>
 * <p>
 * 按用户的实现是存一个"吊销时刻"时间戳，校验时与 Token 的 {@code iat} 比较：
 * 签发早于该时刻的一律拒绝。这样不必维护每个用户的 Token 列表，
 * 且 TTL 设为 Token 最长有效期后，到期的旧 Token 本来也过不了有效期校验。
 * </p>
 */
@Service
public class TokenBlacklistService {

    private static final String KEY_PREFIX = "jwt:blacklist:";

    private static final String USER_REVOKE_PREFIX = "jwt:user-revoked:";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 将 Token 加入吊销名单
     *
     * @param jti   Token 的 JWT ID
     * @param ttlMs 剩余有效期（毫秒）；<=0 表示已过期，无需记录
     */
    public void blacklist(String jti, long ttlMs) {
        if (jti == null || jti.isEmpty() || ttlMs <= 0) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue()
                    .set(KEY_PREFIX + jti, "1", Duration.ofMillis(ttlMs));
        } catch (Exception e) {
            // 登出流程不应因缓存故障而失败
            System.err.println("[TokenBlacklist] 写入吊销名单失败 jti=" + jti + ": " + e.getMessage());
        }
    }

    /**
     * 判断 Token 是否已被吊销。Redis 异常时返回 false（放行），理由见类注释。
     */
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isEmpty()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(KEY_PREFIX + jti));
        } catch (Exception e) {
            System.err.println("[TokenBlacklist] 查询吊销名单失败，本次放行 jti=" + jti + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 作废某用户此前签发的全部 Token（账号被禁用、密码被管理员重置时调用）。
     *
     * @param userId 用户 ID
     * @param ttlMs  保留该吊销标记多久。传 Token 的最长有效期即可 ——
     *               更早签发的 Token 届时已自然过期，标记无需再留
     */
    public void revokeAllForUser(Long userId, long ttlMs) {
        if (userId == null || ttlMs <= 0) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    USER_REVOKE_PREFIX + userId,
                    String.valueOf(System.currentTimeMillis()),
                    Duration.ofMillis(ttlMs));
            System.out.println("[TokenBlacklist] 已作废用户#" + userId + " 的全部历史 Token");
        } catch (Exception e) {
            System.err.println("[TokenBlacklist] 写作用户级吊销标记失败 userId=" + userId + ": " + e.getMessage());
        }
    }

    /**
     * 该 Token 是否签发自用户级吊销时刻之前（即已被作废）。
     *
     * @param issuedAtMs Token 的签发时间。&lt;= 0 表示取不到签发时间，
     *                   此时返回 false（不拒绝），理由见 {@code JwtUtil#getIssuedAtFromToken}
     */
    public boolean isUserRevoked(Long userId, long issuedAtMs) {
        if (userId == null || issuedAtMs <= 0) {
            return false;
        }
        try {
            String revokedAt = stringRedisTemplate.opsForValue().get(USER_REVOKE_PREFIX + userId);
            if (revokedAt == null) {
                return false;
            }
            return issuedAtMs <= Long.parseLong(revokedAt);
        } catch (Exception e) {
            System.err.println("[TokenBlacklist] 查询用户级吊销标记失败，本次放行 userId=" + userId + ": " + e.getMessage());
            return false;
        }
    }
}

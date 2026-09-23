package com.mailsystem.service.analysis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM 调用熔断器 —— <b>按用户（即按凭据）独立计数</b>。
 *
 * <h3>为什么不是全局一个熔断器</h3>
 * <p>
 * 最直接的实现是一个全局失败计数，但那会带来一个很糟的故障模式：
 * 某个用户填错了 API Key，他的每次分析都 401，连错 5 次就把全局熔断器打开，
 * <b>所有其他用户的分类也跟着停摆</b>。一个人的配置错误演变成了全站故障。
 * </p>
 * <p>
 * 因此状态按 {@code userId} 分桶（用系统默认 Key 时归到 {@code 0} 号桶）。
 * 一个坏 Key 只会让那个用户走规则兜底，其他人的主路径不受影响。
 * </p>
 *
 * <h3>为什么不用半开状态</h3>
 * <p>
 * 常见实现有"熔断 → 半开试一次 → 成功则闭合"的三态。这里刻意只做两态：
 * 熔断期内直接走兜底，到期后<b>自然恢复尝试</b>。理由是分析是后台异步的、
 * 每次调用都有规则兜底，探测请求带来的收益（提前几十秒恢复）不值得多一套状态机。
 * 唯一的效果差异是：如果上游仍然不可用，恢复后会再连错 N 次才重新熔断 ——
 * 代价是 N 次快速失败，可以接受。
 * </p>
 *
 * <h3>它不是线程安全的替代品</h3>
 * <p>
 * 熔断器只做"减少无效请求"这一件事，<b>不</b>保证请求数量的正确性：
 * 多个分析线程可能同时判断"未熔断"并同时发出请求。这是刻意的 ——
 * 用锁把它做成精确的会引入争用，而多发出几个请求的代价远小于让分析线程排队。
 * </p>
 */
@Component
public class LlmCircuitBreaker {

    /** 使用系统默认 Key 时的归属桶 */
    public static final long SYSTEM_BUCKET = 0L;

    @Value("${app.llm.circuit-breaker-threshold:5}")
    private int threshold;

    @Value("${app.llm.circuit-breaker-open-seconds:60}")
    private int openSeconds;

    private final Map<Long, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * 该用户当前是否处于熔断中
     */
    public boolean isOpen(Long userId) {
        Bucket bucket = buckets.get(bucketKey(userId));
        if (bucket == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (bucket.openUntilMillis > now) {
            return true;
        }
        // 熔断期已过：清零后重新开始计数，避免一恢复就因累计的历史失败再次熔断
        if (bucket.openUntilMillis != 0L) {
            bucket.openUntilMillis = 0L;
            bucket.consecutiveFailures.set(0);
        }
        return false;
    }

    /**
     * 熔断剩余时间（毫秒），未熔断时返回 0。供日志与监控展示。
     */
    public long openRemainingMs(Long userId) {
        Bucket bucket = buckets.get(bucketKey(userId));
        if (bucket == null) {
            return 0L;
        }
        long remaining = bucket.openUntilMillis - System.currentTimeMillis();
        return Math.max(remaining, 0L);
    }

    /**
     * 记录一次成功：连续失败计数清零。
     */
    public void recordSuccess(Long userId) {
        Bucket bucket = buckets.get(bucketKey(userId));
        if (bucket != null) {
            bucket.consecutiveFailures.set(0);
            bucket.openUntilMillis = 0L;
        }
    }

    /**
     * 记录一次失败，达到阈值即打开熔断。
     *
     * @return 本次失败后是否触发了熔断（供日志用）
     */
    public boolean recordFailure(Long userId) {
        Bucket bucket = buckets.computeIfAbsent(bucketKey(userId), k -> new Bucket());
        int failures = bucket.consecutiveFailures.incrementAndGet();
        if (failures >= threshold) {
            bucket.openUntilMillis = System.currentTimeMillis() + openSeconds * 1000L;
            // 计数回到阈值以下一点，避免熔断窗口结束后第一次失败立刻又打开：
            // 恢复后应该有几"次"尝试的机会，而不是一次就重新熔断
            bucket.consecutiveFailures.set(threshold - 1);
            return true;
        }
        return false;
    }

    /** 当前连续失败次数，供管理端监控 */
    public int failureCount(Long userId) {
        Bucket bucket = buckets.get(bucketKey(userId));
        return bucket == null ? 0 : bucket.consecutiveFailures.get();
    }

    private static Long bucketKey(Long userId) {
        return userId == null ? SYSTEM_BUCKET : userId;
    }

    private static class Bucket {
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        /** 熔断截止时间戳（毫秒）；0 表示未熔断 */
        volatile long openUntilMillis = 0L;
    }
}

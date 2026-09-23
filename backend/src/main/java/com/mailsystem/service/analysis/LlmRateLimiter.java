package com.mailsystem.service.analysis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM 调用限流 —— 每用户每分钟的硬上限。
 *
 * <h3>为什么需要它（熔断器不够）</h3>
 * <p>
 * 熔断器处理的是"上游坏了"，限流处理的是"我们发得太多"。两者防的不是一件事：
 * </p>
 * <ul>
 *   <li>用户 A 的 Key 有效、上游一切正常，但他绑定的邮箱被灌了一千封邮件 ——
 *       一千次分析会把他的配额和账单瞬间打穿，而熔断器<b>什么都不会做</b>，
 *       因为每一次调用都成功了</li>
 *   <li>短时间大量并发还会触发上游自己的 429，反而让正常请求也失败</li>
 * </ul>
 *
 * <h3>固定窗口，不是滑动窗口</h3>
 * <p>
 * 用"当前分钟编号 + 计数器"，跨分钟即清零。固定窗口的已知缺陷是边界效应：
 * 在 12:00:59 和 12:01:00 各发满一轮，两秒内实际发出 2×limit 次请求。
 * 这里接受这个缺陷 —— 限流的目标是"防止量级失控"，而滑动窗口需要记录
 * 每个请求的时间戳或使用 Redis，对这个后台异步管线来说是不成比例的复杂度。
 * </p>
 *
 * <h3>被限流的邮件不会丢结论</h3>
 * <p>
 * 超限时调用方会走规则兜底并记 {@code RATE_LIMITED}，邮件照常有分类和摘要，
 * 只是质量降到规则水平。{@code llm_call_log} 里 {@code REJECTED} 的数量
 * 因此是值得盯的指标：它长期非零意味着限流阈值配得太低。
 * </p>
 */
@Component
public class LlmRateLimiter {

    /** 使用系统默认 Key 时的归属桶 */
    private static final long SYSTEM_BUCKET = LlmCircuitBreaker.SYSTEM_BUCKET;

    @Value("${app.llm.rate-limit-per-minute:20}")
    private int limitPerMinute;

    private final Map<Long, Window> windows = new ConcurrentHashMap<>();

    /**
     * 尝试占用一次配额。
     *
     * @return true 表示允许本次调用；false 表示已达上限，调用方应走规则兜底
     */
    public boolean tryAcquire(Long userId) {
        if (limitPerMinute <= 0) {
            // 配成 0 或负数视为"关闭限流"。
            // 注意这与"限流为 0 次"是相反的语义，因此下面单独说明
            return true;
        }
        long currentMinute = System.currentTimeMillis() / 60_000L;
        Window window = windows.computeIfAbsent(bucketKey(userId), k -> new Window());

        synchronized (window) {
            if (window.minute != currentMinute) {
                window.minute = currentMinute;
                window.count.set(0);
            }
            if (window.count.get() >= limitPerMinute) {
                return false;
            }
            window.count.incrementAndGet();
            return true;
        }
    }

    /**
     * 当前这一分钟已用掉的配额，供监控展示。
     */
    public int usedThisMinute(Long userId) {
        Window window = windows.get(bucketKey(userId));
        if (window == null) {
            return 0;
        }
        long currentMinute = System.currentTimeMillis() / 60_000L;
        return window.minute == currentMinute ? window.count.get() : 0;
    }

    /**
     * 配置的每分钟上限，供错误信息与监控展示。
     */
    public int getLimitPerMinute() {
        return limitPerMinute;
    }

    private static Long bucketKey(Long userId) {
        return userId == null ? SYSTEM_BUCKET : userId;
    }

    private static class Window {
        volatile long minute = -1L;
        final AtomicInteger count = new AtomicInteger(0);
    }
}

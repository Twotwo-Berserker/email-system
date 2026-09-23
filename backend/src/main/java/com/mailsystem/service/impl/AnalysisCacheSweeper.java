package com.mailsystem.service.impl;

import com.mailsystem.service.MailCacheService;
import com.mailsystem.websocket.MailNotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分析完成后的缓存驱逐与推送合并器。
 *
 * <h3>为什么不能分析完一封就立刻驱逐</h3>
 * <p>
 * 驱逐列表缓存用的是 {@code KEYS mail:list:{userId}:*}，它对整个 Redis 实例
 * 是 O(N) 的<b>阻塞</b>操作。分析是按收件人 fan-out 的：一封信发给 20 个人就是
 * 20 次驱逐；一次 IMAP 同步拉回 50 封邮件就是上千次。把这些合并成
 * "每个用户每个周期一次"，是让 {@code KEYS} 可用的前提。
 * </p>
 *
 * <h3>合并的是"同一用户的多封邮件"，不是"一封邮件的多个收件人"</h3>
 * <p>
 * 收件人各不相同，各自都要驱逐自己那份缓存，这一层合并不掉。
 * 真正的收益场景是<b>突发</b>：一个用户同时收到多封邮件（群发、邮件列表、
 * 一次同步拉回一批）时，这些邮件只产生一次 {@code KEYS} 与一次 WebSocket 推送。
 * </p>
 *
 * <h3>代价：推送晚了一个周期</h3>
 * <p>
 * 收件人最多晚 {@value #FLUSH_INTERVAL_MS} 毫秒看到新结论。这是刻意的取舍 ——
 * 分析本身就要几秒，再晚两秒对体验没有影响，而每次都立刻驱逐会让
 * Redis 在高并发分析时被反复阻塞。
 * </p>
 *
 * <h3>为什么不用 {@code @Scheduled} 之外的方式</h3>
 * <p>
 * 分析线程只需要把"我完成了"记下来就能返回，不做任何阻塞调用；
 * 驱逐与推送都在调度线程上串行完成，天然没有并发问题。
 * </p>
 */
@Component
public class AnalysisCacheSweeper {

    /** 合并周期（毫秒）。够短到用户感觉不到，够长到能合掉一次突发的邮件 */
    private static final int FLUSH_INTERVAL_MS = 2000;

    /** 单次推送最多携带的邮件 id 数，避免一条 WebSocket 消息过大 */
    private static final int MAX_MAIL_IDS_PER_PUSH = 50;

    @Autowired
    private MailCacheService cacheService;

    @Autowired
    private MailNotificationService notificationService;

    /**
     * 待处理的用户 → 邮件 id 集合。
     * <p>
     * 用 {@link ConcurrentHashMap} 做外层：分析线程可能并发写入。
     * 内层用 {@link LinkedHashSet} 并由 {@code synchronized} 保护 ——
     * 内层集合会被两个线程同时读写（分析与调度），仅靠并发容器不够。
     * </p>
     */
    private final Map<Long, Set<Long>> pending = new ConcurrentHashMap<>();

    /**
     * 记录"某用户的某封邮件分析完成"。
     * <p>
     * 本方法<b>不做任何阻塞操作</b>，可以在分析线程上安全调用。
     * </p>
     */
    public void schedule(Long userId, Long mailId) {
        if (userId == null) {
            return;
        }
        Set<Long> mailIds = pending.computeIfAbsent(userId, k -> new LinkedHashSet<>());
        synchronized (mailIds) {
            mailIds.add(mailId);
        }
    }

    /**
     * 周期性清空待处理队列：每个用户驱逐一次缓存、推送一次通知。
     * <p>
     * 先把当前队列整体换出来再处理，这样处理期间新产生的记录会进入新的集合，
     * 不会被本次一并清掉（否则那部分通知就丢了）。
     * </p>
     */
    @Scheduled(fixedDelay = FLUSH_INTERVAL_MS)
    public void flush() {
        if (pending.isEmpty()) {
            return;
        }

        Map<Long, Set<Long>> batch = new LinkedHashMap<>();
        for (Long userId : new ArrayList<>(pending.keySet())) {
            Set<Long> mailIds = pending.remove(userId);
            if (mailIds == null) {
                continue;
            }
            synchronized (mailIds) {
                if (!mailIds.isEmpty()) {
                    batch.put(userId, mailIds);
                }
            }
        }

        for (Map.Entry<Long, Set<Long>> entry : batch.entrySet()) {
            Long userId = entry.getKey();
            try {
                // 缓存里存的是"分析完成前"的分类与摘要，必须驱逐，
                // 否则收件人最多 5 分钟（LIST_TTL）看不到新结论
                cacheService.evictAll(userId);
                notificationService.notifyUser(userId, "ANALYSIS_DONE", payload(entry.getValue()));
            } catch (Exception e) {
                // 一个用户推送失败不能让整批用户都收不到通知
                System.err.println("[AnalysisCacheSweeper] 用户 " + userId
                        + " 的缓存驱逐/推送失败: " + e.getMessage());
            }
        }
    }

    /**
     * 构造推送负载。
     * <p>
     * {@code mailIds} 让前端可以只刷新受影响的列表项；超过上限时截断，
     * 前端按"收到 ANALYSIS_DONE 就重新拉取当前列表"处理也能正确工作。
     * </p>
     */
    private Map<String, Object> payload(Set<Long> mailIds) {
        List<Long> ids = new ArrayList<>(mailIds);
        boolean truncated = ids.size() > MAX_MAIL_IDS_PER_PUSH;
        if (truncated) {
            ids = ids.subList(0, MAX_MAIL_IDS_PER_PUSH);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("mailIds", ids);
        data.put("count", mailIds.size());
        data.put("truncated", truncated);
        return data;
    }
}

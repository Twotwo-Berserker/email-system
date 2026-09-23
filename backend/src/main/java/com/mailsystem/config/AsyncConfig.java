package com.mailsystem.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步任务配置 — 有界线程池
 * <p>
 * 原实现是空 body，导致 {@code @Async} 退化为 Spring 默认的
 * {@code SimpleAsyncTaskExecutor}：<b>无界、每次调用新建一个线程</b>。
 * 在按收件人 fan-out LLM 调用的分析管线中，这会在一封群发邮件上
 * 瞬间创建几十上百个线程，每个线程持有 HTTP 连接与数据库连接，
 * 构成对自身数据库的拒绝服务。因此必须显式设界。
 * </p>
 *
 * <h3>关于线程池参数（不是拍脑袋的数字）</h3>
 * <pre>
 *   corePoolSize ≈ ceil(N × L / SLO)
 *      N   = 单次群发的典型收件人数 ≈ 50
 *      L   = LLM 调用 p95 延迟        ≈ 5s
 *      SLO = 可接受的完成分析时长      ≈ 60s
 *      → ceil(50 × 5 / 60) = 5 ≈ 4~5，取 4
 *   maxPoolSize = 2 × core，作为突发泄压阀
 *   queueCapacity = 64 —— 见下方警告
 * </pre>
 *
 * <p>
 * <b>为什么队列只有 64 而不是 200：</b>{@link ThreadPoolExecutor} 的扩容规则是
 * <i>先入队、队列满了才扩容到 maxPoolSize</i>。若队列设为 200，则线程数会一直停在
 * corePoolSize=4，直到积压 200 个任务，{@code maxPoolSize} 形同虚设。
 * 队列设小，maxPoolSize 才真正生效。总在途容量 = 64 + 8 ≈ 72，
 * 足以吸收一次 50 人群发，同时在真正过载时触发拒绝。
 * </p>
 *
 * <p>
 * <b>为什么用 AbortPolicy 而不是 CallerRunsPolicy：</b>
 * CallerRunsPolicy 会让被拒绝的任务在<b>调用者线程</b>上执行。这里的调用者
 * 是 HTTP 请求线程，而任务体是一次 LLM 网络调用——于是线程池过载会直接转化为
 * 一个耗时 12 秒的 {@code POST /mail/send}。这是 CallerRunsPolicy 最常见的误用。
 * 正确做法是 AbortPolicy + 在分发处显式 catch {@link java.util.concurrent.RejectedExecutionException}，
 * 就地跑一次<b>纯 CPU、无网络</b>的规则兜底（见 AnalysisDispatcher）。
 * 50 个收件人全部被拒绝的极端情况下，也只增加约 200ms 响应时间，
 * 且每个收件人都能拿到一份标注 {@code source='RULE'} 的完整结果。
 * </p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 邮件分析线程池。
     * <p>
     * <b>刻意同时注册 {@code taskExecutor} 这个 bean 名：</b>
     * Spring 的 {@code AsyncExecutionAspectSupport} 在解析不带限定符的
     * {@code @Async} 时，是按<b>类型</b>查找 {@code TaskExecutor} 的；一旦容器里
     * 存在多个该类型的 bean 且没有一个叫 {@code taskExecutor}，它会放弃解析并
     * <b>静默退回</b> {@code SimpleAsyncTaskExecutor}——即回到无界线程模型。
     * 留一个名字给它，既避免了这个陷阱，也让既有的裸 {@code @Async}
     * （如各规则插件）从无界池迁移到有界池。
     * </p>
     */
    @Bean(name = {"analysisExecutor", "taskExecutor"})
    public ThreadPoolTaskExecutor analysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(64);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("mail-analysis-");

        // 过载时快速失败，由分发处就地走规则兜底（见类注释）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

        // 优雅关闭：等待在途分析完成，否则任务被丢弃、
        // mail_intelligence_result 会残留 RUNNING 状态行
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();
        return executor;
    }

    /**
     * IMAP 收信同步线程池 —— 与邮件分析池分离。
     * <p>
     * 两种工作负载的失败模式不同：分析是"短时 CPU + 一次 HTTP"，
     * 收信是"长时网络 IO + MIME 解析 + 附件落盘"。若共用线程池，
     * 一次大邮箱首次同步会占满线程，把分析任务全部挤到拒绝路径上。
     * </p>
     * <p>
     * 刻意保持很小的规模：同一个邮箱账户不允许并发拉取
     * （IMAP UID 水位线的读-改-写不是原子的），因此并发度按账户数而非 CPU 数设定。
     * </p>
     */
    @Bean("mailSyncExecutor")
    public ThreadPoolTaskExecutor mailSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("mail-sync-");
        // 同步任务可以等待：晚几秒同步没有用户可感知的代价，
        // 而丢弃一轮同步会让收信延迟一个完整轮询周期
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    /**
     * 外部邮件投递线程池（SMTP 外发）。
     * <p>
     * 与分析池的关键区别在处理拒绝的策略上，这里<b>刻意用
     * {@link ThreadPoolExecutor.CallerRunsPolicy}</b>（与分析池的 AbortPolicy 相反）：
     * </p>
     * <ul>
     *   <li>分析任务被拒绝的代价是"这次分析退回规则兜底"——结果仍然完整，用户无感</li>
     *   <li>投递任务被拒绝的代价是"这封信永远发不出去"——且发送方看到的提示
     *       仍是"发送成功"（站内投递确实成功了），属于静默丢信</li>
     * </ul>
     * <p>
     * 因此这里宁愿让请求线程自己把信发出去（响应慢几秒），也不能丢。
     * 队列给到 100，正常规模下不会走到 CallerRuns 分支。
     * </p>
     */
    @Bean("mailDeliveryExecutor")
    public ThreadPoolTaskExecutor mailDeliveryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("mail-deliver-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅关闭：等在途投递完成，否则用户点了发送却什么都没发生
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 自动探测线程池（一键绑定时的 SMTP / IMAP 连通性探测）。
     * <p>
     * 单独一个池的理由是<b>并发模型与前三个都不同</b>：这里的任务是
     * "用户正阻塞等着结果的探测"，每个任务都要跟外部服务器做多轮握手，
     * 每个 URL 上跑着的探测数量就是同时在点"绑定"的用户数 —— 通常是个位数。
     * </p>
     * <p>
     * 池子刻意开得很小（2 个线程）并且用 {@link ThreadPoolExecutor.CallerRunsPolicy}：
     * 探测是用户主动触发的、有明确上限的动作，被拒绝的代价是"这一次绑定卡住"，
     * 让提交者自己跑掉比静默丢弃好。若把它挂到收信池上，
     * 几个用户同时绑定的探测会占满线程，把定时收信全部推迟 —— 那才是真正的故障。
     * </p>
     */
    @Bean("mailProbeExecutor")
    public ThreadPoolTaskExecutor mailProbeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(32);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("mail-probe-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 探测本身有 4~6 秒的超时，不会长时间滞留，无需等待关闭
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}

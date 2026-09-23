package com.mailsystem.service.impl;

import com.mailsystem.dto.MailAnalysisView;
import com.mailsystem.entity.LlmCallLog;
import com.mailsystem.entity.Mail;
import com.mailsystem.entity.MailIntelligenceResult;
import com.mailsystem.entity.UserFeedback;
import com.mailsystem.mapper.LlmCallLogMapper;
import com.mailsystem.mapper.MailIntelligenceResultMapper;
import com.mailsystem.mapper.MailMapper;
import com.mailsystem.mapper.UserFeedbackMapper;
import com.mailsystem.service.MailAnalysisService;
import com.mailsystem.service.PluginService;
import com.mailsystem.service.PromptTemplateService;
import com.mailsystem.service.analysis.AnalysisContent;
import com.mailsystem.service.analysis.AnalysisResponseParser;
import com.mailsystem.service.analysis.AnalysisResult;
import com.mailsystem.service.analysis.JsonSupport;
import com.mailsystem.service.analysis.LlmCallOutcome;
import com.mailsystem.service.analysis.LlmCircuitBreaker;
import com.mailsystem.service.analysis.LlmClient;
import com.mailsystem.service.analysis.LlmRateLimiter;
import com.mailsystem.service.analysis.RuleAnalyzer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 邮件智能分析服务实现。
 *
 * <h3>一次分析的完整步骤</h3>
 * <ol>
 *   <li>算内容指纹，与已有结论比对 —— 内容没变就不重复花钱</li>
 *   <li>把结果行置为 {@code RUNNING}（{@code beginRun}，同时累加 {@code revision}）</li>
 *   <li>解析生效配置，按 <b>无 Key → 熔断 → 限流 → 调用</b> 的顺序决策</li>
 *   <li>调用失败时用规则插件产出兜底结论，邮件永远不会没有分类</li>
 *   <li>写结论（{@code complete}）、写调用日志（{@code llm_call_log}）</li>
 *   <li>登记缓存驱逐与推送，由 {@link AnalysisCacheSweeper} 合并后异步执行</li>
 * </ol>
 *
 * <h3>为什么整个类没有 {@code @Transactional}</h3>
 * <p>
 * 一次分析跨越一次可能长达 12 秒的 HTTP 调用。把它包进事务，意味着一个数据库
 * 连接会被占住整个网络往返时间 —— 分析是按收件人 fan-out 的，几个人同时收到
 * 一封邮件就能把连接池抽干，而且事务超时会把已经成功的 LLM 调用一起回滚掉，
 * 用户付了 token 却什么都没得到。
 * </p>
 * <p>
 * 因此这里的每一步都是独立的短事务（由 MyBatis 自动提交），中途失败留下的
 * {@code RUNNING} 行由 {@link #reclaimOrphans()} 兜底，而不是靠事务回滚。
 * </p>
 *
 * <h3>为什么单个收件人失败不影响其他人</h3>
 * <p>
 * { user1 有 Key、user2 没有 } 是常态。若第一个人失败就中断，后面的人会
 * 一直停在"未分析"。因此 {@link #analyze} 对每个收件人单独 try/catch。
 * </p>
 */
@Service
public class MailAnalysisServiceImpl implements MailAnalysisService {

    /**
     * 分析管线版本，落进 {@code mail_intelligence_result.pipeline_version}。
     * <p>
     * 改动 Prompt 以外的分析逻辑（换模型调用方式、改合并策略、改字段含义）时
     * 递增它。存量行不会自动重跑 —— 版本号的存在是为了让"这一行的结论是哪一代
     * 管线产出的"可查，而不是一个自动迁移开关。
     * </p>
     */
    public static final String PIPELINE_VERSION = "p1";

    /** 解析失败时的合成错误码（与 {@code llm_call_log.error_code} 同值） */
    private static final String ERROR_PARSE_FAILED = "PARSE_FAILED";

    /** 管线自身异常（非上游问题）的错误码 */
    private static final String ERROR_INTERNAL = "INTERNAL";

    /** 重试时追加的提醒 —— 只针对"格式不对"，不针对"网络不通" */
    private static final String RETRY_REMINDER =
            "\n\n注意：你上一次的回复不是合法的 JSON 对象。"
            + "请只输出一个 JSON 对象，不要任何解释文字、不要 Markdown 代码块。";

    /** 上游回显的 Bearer 凭据 */
    private static final Pattern BEARER_PATTERN =
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]{6,}");

    /** 常见厂商的密钥前缀 */
    private static final Pattern API_KEY_PATTERN =
            Pattern.compile("(?i)\\b(sk|api|key)[-_][A-Za-z0-9._\\-]{8,}");

    @Autowired
    private MailMapper mailMapper;

    @Autowired
    private MailIntelligenceResultMapper resultMapper;

    @Autowired
    private LlmCallLogMapper callLogMapper;

    @Autowired
    private UserFeedbackMapper feedbackMapper;

    @Autowired
    private PluginService pluginService;

    @Autowired
    private PromptTemplateService promptTemplateService;

    @Autowired
    private RuleAnalyzer ruleAnalyzer;

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private LlmCircuitBreaker circuitBreaker;

    @Autowired
    private LlmRateLimiter rateLimiter;

    @Autowired
    private AnalysisCacheSweeper sweeper;

    @Value("${app.analysis.max-content-chars:3000}")
    private int maxContentChars;

    /**
     * 孤儿行判定时长（分钟）。
     * <p>
     * 必须显著大于 read timeout（默认 12 秒），否则一个正常但较慢的分析会被
     * 误判成孤儿并标成 FAILED —— 而它稍后会自己写回结论，用户会看到"失败"
     * 闪一下又变正常。留 10 分钟是数量级上的余量。
     * </p>
     */
    @Value("${app.analysis.orphan-cutoff-minutes:10}")
    private int orphanCutoffMinutes;

    // ==================== 对外入口 ====================

    @Override
    public void analyze(Long mailId, List<Long> recipientUserIds) {
        if (mailId == null || recipientUserIds == null || recipientUserIds.isEmpty()) {
            return;
        }
        Mail mail = mailMapper.selectById(mailId);
        if (mail == null) {
            // 事务提交后触发，正常不会走到这里。真走到了说明事件与数据不一致，
            // 属于必须留痕的异常
            System.err.println("[MailAnalysis] 邮件 " + mailId + " 不存在，跳过分析");
            return;
        }

        // traceId 在这里生成而不是每个收件人各生成一个：
        // 一封信发给 20 个人，这 20 条调用日志应该能被一次查出来
        String traceId = newTraceId();
        for (Long userId : recipientUserIds) {
            if (userId == null) {
                continue;
            }
            try {
                analyzeOne(mail, userId, false, traceId);
            } catch (Exception e) {
                System.err.println("[MailAnalysis] 邮件 " + mailId + " 对用户 "
                        + userId + " 分析失败: " + e.getMessage());
            }
        }
    }

    @Override
    public boolean reanalyze(Long mailId, Long userId) {
        if (mailId == null || userId == null) {
            return false;
        }
        // 与自动分析不同，这里要校验"这封邮件确实跟这个用户有关" ——
        // 自动分析的调用方（发信/收信流程）已经确定了收件人，而重跑是用户
        // 从接口发起的，不校验就等于允许任何登录用户给任意邮件塞一份分析结果
        Mail mail = mailMapper.selectDetailForUser(mailId, userId);
        if (mail == null) {
            return false;
        }
        return analyzeOne(mail, userId, true, newTraceId());
    }

    @Override
    public void analyzeWithRulesOnly(Long mailId, List<Long> recipientUserIds) {
        if (mailId == null || recipientUserIds == null || recipientUserIds.isEmpty()) {
            return;
        }
        Mail mail = mailMapper.selectById(mailId);
        if (mail == null) {
            return;
        }
        for (Long userId : recipientUserIds) {
            if (userId == null) {
                continue;
            }
            try {
                rulesOnly(mail, userId);
            } catch (Exception e) {
                System.err.println("[MailAnalysis] 过载兜底失败 mail=" + mailId
                        + " user=" + userId + ": " + e.getMessage());
            }
        }
    }

    /**
     * 单封 × 单收件人的纯规则兜底（无网络、无熔断、无限流判断）。
     * <p>
     * 这里<b>不</b>检查幂等：调用它的场景是"线程池拒绝了一个本该跑的分析"，
     * 说明这封邮件还没有结论。而多跑一次规则的代价是几十毫秒的 CPU，
     * 远小于"因为一次哈希比对失误而永远没有分类"的代价。
     * </p>
     */
    private void rulesOnly(Mail mail, Long userId) {
        Long mailId = mail.getId();
        String hash = AnalysisContent.hash(mail);
        long started = System.currentTimeMillis();

        resultMapper.beginRun(mailId, userId, MailIntelligenceResult.STATUS_RUNNING, hash);

        AnalysisResult result =
                ruleAnalyzer.analyze(mail, MailIntelligenceResult.ERROR_OVERLOADED);
        MailIntelligenceResult entity = result.toEntity(mailId, userId, PIPELINE_VERSION,
                (int) (System.currentTimeMillis() - started));
        entity.setIndicators(JsonSupport.toJson(result.getIndicators()));
        entity.setActions(JsonSupport.toJson(result.getActions()));
        entity.setContentHash(hash);
        resultMapper.complete(entity);

        // 过载时不写 llm_call_log：这一次根本没有发起 LLM 调用，
        // 记一行 REJECTED 会把"上游失败率"和"我们自己扛不住"混在一个数字里
        sweeper.schedule(userId, mailId);
    }

    @Override
    public MailAnalysisView viewFor(Long mailId, Long userId) {
        if (mailId == null || userId == null) {
            return MailAnalysisView.from(null, null);
        }
        MailIntelligenceResult result = resultMapper.selectOne(mailId, userId);
        UserFeedback feedback = feedbackMapper.selectOne(mailId, userId);
        return MailAnalysisView.from(result, feedback);
    }

    // ==================== 单封 × 单收件人 ====================

    /**
     * @param force 为 true 时跳过"内容未变则不重跑"的检查（用户手动点击重跑）
     * @return 是否真的跑了一次分析；因内容未变而跳过时返回 false
     */
    private boolean analyzeOne(Mail mail, Long userId, boolean force, String traceId) {
        Long mailId = mail.getId();
        String hash = AnalysisContent.hash(mail);

        // 1. 幂等：同一份内容已经得出过结论就不重复花钱。
        //    只比 DONE 的行 —— RUNNING 行说明上次没跑完（JVM 退出、超时），
        //    那种情况应该重跑而不是继续等
        if (!force && hash != null) {
            MailIntelligenceResult existing = resultMapper.selectOne(mailId, userId);
            if (existing != null
                    && MailIntelligenceResult.STATUS_DONE.equals(existing.getStatus())
                    && hash.equals(existing.getContentHash())) {
                return false;
            }
        }

        long started = System.currentTimeMillis();

        // 2. 置为 RUNNING。刻意不先清空结论列：读取端的 COALESCE 会在结论列为
        //    NULL 时回落到 mail 表的规则兜底值，因此这里即使把上一轮的结论留着
        //    也不会被读到 —— 反而是"分析中还能看到上次的分类"更符合直觉
        resultMapper.beginRun(mailId, userId, MailIntelligenceResult.STATUS_RUNNING, hash);

        try {
            return runPipeline(mail, userId, hash, traceId, started);
        } catch (Exception e) {
            // 管线自己抛异常（代码缺陷、结果序列化失败）时必须把行从 RUNNING
            // 里放出来，否则用户要等到孤儿回收（默认 10 分钟）才看到结果
            System.err.println("[MailAnalysis] 管线异常 mail=" + mailId + " user=" + userId
                    + ": " + e.getMessage());
            completeFailure(mailId, userId, ERROR_INTERNAL, e.getMessage(),
                    (int) (System.currentTimeMillis() - started));
            return false;
        }
    }

    /**
     * 决策并执行：LLM 优先，任何一步不成立就退到规则。
     *
     * @return 恒为 true（跳过的情况已在调用方处理）
     */
    private boolean runPipeline(Mail mail, Long userId, String hash,
                                String traceId, long started) {
        Long mailId = mail.getId();
        Map<String, Object> config = pluginService.resolveEffectiveConfigForUser(userId);
        String apiKey = str(config.get("apiKey"));

        // 熔断与限流按"凭据的归属"分桶，不是按收件人 ——
        // 同一个 Key 出错应该一起熔断，而不是每个用它的用户各烧一轮
        Long credentialOwner = toLong(config.get("credentialOwner"));

        AnalysisResult result;
        LlmCallOutcome outcome;

        if (apiKey.isEmpty()) {
            // 没配 Key 是最常见的"降级"，属于预期内，不值得记成错误
            result = ruleAnalyzer.analyze(mail, MailIntelligenceResult.ERROR_NO_KEY);
            outcome = LlmCallOutcome.failure(LlmCallLog.STATUS_SKIPPED_NO_KEY,
                    MailIntelligenceResult.ERROR_NO_KEY, "未配置 LLM API Key，使用规则兜底");
        } else if (circuitBreaker.isOpen(credentialOwner)) {
            long remaining = circuitBreaker.openRemainingMs(credentialOwner);
            result = ruleAnalyzer.analyze(mail, MailIntelligenceResult.ERROR_CIRCUIT_OPEN);
            outcome = LlmCallOutcome.failure(LlmCallLog.STATUS_REJECTED,
                    MailIntelligenceResult.ERROR_CIRCUIT_OPEN,
                    "熔断中，剩余 " + (remaining / 1000) + " 秒");
        } else if (!rateLimiter.tryAcquire(credentialOwner)) {
            result = ruleAnalyzer.analyze(mail, MailIntelligenceResult.ERROR_RATE_LIMITED);
            outcome = LlmCallOutcome.failure(LlmCallLog.STATUS_REJECTED,
                    MailIntelligenceResult.ERROR_RATE_LIMITED,
                    "已达每用户每分钟 " + rateLimiter.getLimitPerMinute() + " 次的上限");
        } else {
            // 前三个分支都已经把规则结论算好了 —— 规则插件里有正则与遍历，
            // 不便宜，因此这里只在这条真正调用 LLM 的分支里才走"成功则用 LLM、
            // 失败则退规则"的判断，不重复计算
            PromptTemplateService.ActivePrompt prompt = promptTemplateService.active();
            if (prompt.isBuiltIn()) {
                System.out.println("[MailAnalysis] 未从数据库取到生效 Prompt，"
                        + "使用内置兜底 " + prompt.getVersion());
            }
            outcome = callLlm(mail, config, prompt.getContent(), apiKey);
            AnalysisResult parsed =
                    resolveLlmResult(outcome, config, credentialOwner, prompt.getVersion());
            result = parsed != null
                    ? parsed
                    : ruleAnalyzer.analyze(mail, outcome.getErrorCode());
        }

        int latencyMs = (int) (System.currentTimeMillis() - started);

        MailIntelligenceResult entity =
                result.toEntity(mailId, userId, PIPELINE_VERSION, latencyMs);
        // 列表本身不知道用什么 ObjectMapper，序列化留给这里做
        entity.setIndicators(JsonSupport.toJson(result.getIndicators()));
        entity.setActions(JsonSupport.toJson(result.getActions()));
        entity.setContentHash(hash);
        resultMapper.complete(entity);

        writeCallLog(mailId, userId, config, outcome, traceId, apiKey);

        if (!result.getWarnings().isEmpty()) {
            System.out.println("[MailAnalysis] mail=" + mailId + " user=" + userId
                    + " 解析告警: " + result.getWarnings());
        }

        // 6. 缓存驱逐与推送交给合并器，本方法立即返回
        sweeper.schedule(userId, mailId);
        return true;
    }

    /**
     * 调 LLM 并在"格式不对"时重试一次。
     *
     * <h3>只有解析失败才重试</h3>
     * <p>
     * 超时与 5xx 不重试：那说明上游有问题，重试只会让已经拥塞的上游更慢，
     * 而且失败后本来就有规则兜底。而"模型返回了一段带前言的文字"是一次
     * <b>可修复</b>的失败 —— 补一句"只输出 JSON"往往就好了，成本一次调用，
     * 收益是这封邮件从规则质量提升到模型质量。
     * </p>
     * <p>
     * 重试成功时 {@code attempt} 记为 2，落进调用日志。这个数字长期偏高说明
     * Prompt 需要改，或者该换一个更听话的模型。
     * </p>
     */
    private LlmCallOutcome callLlm(Mail mail, Map<String, Object> config,
                                   String systemPrompt, String apiKey) {
        String content = AnalysisContent.forLlm(mail, maxContentChars);

        LlmCallOutcome first = llmClient.call(config, systemPrompt, content, true);
        if (!first.isSuccess()) {
            return first;
        }
        if (AnalysisResponseParser.parse(first.getText()) != null) {
            return first;
        }

        LlmCallOutcome second = llmClient.call(config, systemPrompt, content + RETRY_REMINDER, true);
        if (second.isSuccess() && AnalysisResponseParser.parse(second.getText()) != null) {
            second.setAttempt(2);
            return second;
        }

        // 两次都拿不到合法 JSON。把 status 从 SUCCESS 改成 PARSE_ERROR ——
        // HTTP 层面确实成功了，但"LLM 阶段没有产出可用结论"才是这一行要表达的
        // 事实，否则管理端会把它算进成功率，而实际上是降级了
        second.setStatus(LlmCallLog.STATUS_PARSE_ERROR);
        second.setAttempt(2);
        second.setErrorCode(ERROR_PARSE_FAILED);
        second.setErrorMessage("模型两次都未返回合法 JSON，已降级为规则结论");
        return second;
    }

    /**
     * 从调用结果里取出 LLM 结论；没拿到可用结论时返回 null。
     * <p>
     * 成功或失败都顺便维护熔断器计数 —— 这是唯一知道"这次到底成没成"的地方。
     * </p>
     */
    private AnalysisResult resolveLlmResult(LlmCallOutcome outcome,
                                            Map<String, Object> config,
                                            Long credentialOwner,
                                            String promptVersion) {
        if (outcome == null) {
            return null;
        }
        if (!outcome.isSuccess()) {
            // 只有"真的发出了请求"才算一次失败。无 Key / 熔断 / 限流这三种
            // 都没有到达上游，把它们记成失败会让一个配错 Key 的用户
            // 在限流期间不断刷新熔断窗口，永远出不来
            if (!LlmCallLog.STATUS_SKIPPED_NO_KEY.equals(outcome.getStatus())
                    && !LlmCallLog.STATUS_REJECTED.equals(outcome.getStatus())) {
                circuitBreaker.recordFailure(credentialOwner);
            }
            return null;
        }

        AnalysisResult parsed = AnalysisResponseParser.parse(outcome.getText());
        if (parsed == null) {
            // 走到这里说明 callLlm 的重试逻辑被绕过了，保守起见仍按失败处理
            circuitBreaker.recordFailure(credentialOwner);
            return null;
        }

        circuitBreaker.recordSuccess(credentialOwner);
        parsed.setSource(MailIntelligenceResult.SOURCE_LLM);
        parsed.setModelName(str(config.get("modelName")));
        parsed.setProvider(outcome.getProvider());
        // 只有 LLM 结论才带 Prompt 版本。规则路径留 null，否则管理端
        // 按 prompt_version 分组的采纳率会混进一堆规则结论，
        // 而"哪个版本的 Prompt 更好"这个问题就答不出来了
        parsed.setPromptVersion(promptVersion);
        return parsed;
    }

    // ==================== 落库辅助 ====================

    /**
     * 写调用日志。
     * <p>
     * {@code user_id} 记的是<b>这封邮件归属的收件人</b>，不是密钥的归属者。
     * 管理端要回答的问题是"谁在用 LLM、花了多少、失败了多少"，
     * 而系统默认 Key 被所有未自配的用户共用 —— 若按密钥归属记，
     * 这一大批用户会被压成一行 NULL，恰好把最需要看的信息丢掉。
     * </p>
     */
    private void writeCallLog(Long mailId, Long userId, Map<String, Object> config,
                              LlmCallOutcome outcome, String traceId, String apiKey) {
        try {
            LlmCallLog log = new LlmCallLog();
            log.setTraceId(traceId);
            log.setMailId(mailId);
            log.setUserId(userId);
            log.setProvider(outcome.getProvider());
            // 只存主机名：部分网关把凭据放在查询串里
            log.setEndpointHost(LlmClient.endpointHost(str(config.get("apiEndpoint"))));
            log.setModelName(str(config.get("modelName")));
            log.setStatus(outcome.getStatus());
            log.setHttpStatus(outcome.getHttpStatus());
            log.setLatencyMs(outcome.getLatencyMs());
            log.setPromptTokens(outcome.getPromptTokens());
            log.setCompletionTokens(outcome.getCompletionTokens());
            log.setTotalTokens(outcome.getTotalTokens());
            log.setAttempt(outcome.getAttempt());
            log.setErrorCode(truncate(outcome.getErrorCode(), 64));
            log.setErrorMessage(sanitizeError(outcome.getErrorMessage(), apiKey));
            log.setPipelineVersion(PIPELINE_VERSION);
            log.setCreateTime(LocalDateTime.now());
            callLogMapper.insert(log);
        } catch (Exception e) {
            // 监控数据写不进去不该让已经完成的分类失败
            System.err.println("[MailAnalysis] 写调用日志失败 mail=" + mailId + ": " + e.getMessage());
        }
    }

    /**
     * 把结果行标成失败，供管线自身异常时收尾。
     * <p>
     * {@code source} 刻意留 <b>null</b> 而不是 {@code RULE}：这一行没有任何结论，
     * 标成 RULE 会让管理端把"管线崩了"统计成"走了一次规则兜底"，
     * 两个完全不同的问题会长得一样。读取端看到 source 为 null 会回落到
     * {@code mail} 表上的规则值，界面依然有分类可用。
     * </p>
     */
    private void completeFailure(Long mailId, Long userId, String errorCode,
                                 String message, int latencyMs) {
        try {
            MailIntelligenceResult entity = new MailIntelligenceResult();
            entity.setMailId(mailId);
            entity.setUserId(userId);
            entity.setStatus(MailIntelligenceResult.STATUS_FAILED);
            entity.setErrorCode(errorCode);
            entity.setIndicators(JsonSupport.toJson(Collections.emptyList()));
            entity.setActions(JsonSupport.toJson(Collections.emptyList()));
            entity.setLatencyMs(latencyMs);
            resultMapper.complete(entity);
            System.err.println("[MailAnalysis] mail=" + mailId + " user=" + userId
                    + " 标记为 FAILED(" + errorCode + "): " + message);
        } catch (Exception e) {
            // 连失败都写不进去就只剩日志了，交给孤儿回收
            System.err.println("[MailAnalysis] 写失败状态也失败了 mail=" + mailId
                    + ": " + e.getMessage());
        }
    }

    /**
     * 错误信息脱敏。
     * <p>
     * 上游的错误响应体常常回显请求头（含 {@code Authorization}）或完整请求体。
     * 落库前先抹掉密钥本身，再按常见前缀做一次兜底特征擦除，最后截断到列宽 ——
     * 顺序不能反：先截断会让长密钥的尾部留在库里。
     * </p>
     */
    private String sanitizeError(String message, String apiKey) {
        if (message == null) {
            return null;
        }
        String cleaned = message;
        if (apiKey != null && !apiKey.isEmpty()) {
            cleaned = cleaned.replace(apiKey, "***");
        }
        cleaned = BEARER_PATTERN.matcher(cleaned).replaceAll("Bearer ***");
        cleaned = API_KEY_PATTERN.matcher(cleaned).replaceAll("***");
        return truncate(cleaned, LlmCallLog.MAX_ERROR_CHARS);
    }

    // ==================== 定时维护 ====================

    /**
     * 回收孤儿行：JVM 在分析中途退出会留下永远停留的 {@code PENDING/RUNNING} 行，
     * 界面上表现为"分析中"永不结束。
     * <p>
     * 判据是 {@code COALESCE(update_time, create_time)} 早于阈值 ——
     * 用 {@code COALESCE} 是因为新插入的行 {@code update_time} 为 NULL
     * （列定义只有 {@code ON UPDATE}），直接比 {@code update_time} 会永远为假。
     * </p>
     * <p>
     * 标成 {@code FAILED} 而不是删除：留下行才能让"这封邮件为什么没有结论"
     * 可查，删掉就只剩一个空白的分类栏。用户可以手动重跑。
     * </p>
     */
    @Scheduled(fixedDelayString = "${app.analysis.orphan-scan-interval-ms:600000}")
    public void reclaimOrphans() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(orphanCutoffMinutes);
            int reclaimed = resultMapper.reclaimOrphans(cutoff);
            if (reclaimed > 0) {
                System.out.println("[MailAnalysis] 回收孤儿分析行 " + reclaimed + " 条");
            }
        } catch (Exception e) {
            // 表还没建（未执行 migration_v3.sql）时这里会一直失败，
            // 但那是部署问题，不该让定时任务抛栈把日志刷屏
            System.err.println("[MailAnalysis] 孤儿行回收失败: " + e.getMessage());
        }
    }

    // ==================== 小工具 ====================

    /**
     * 生成一次分析的跟踪 ID。
     * <p>
     * 用 UUID 去掉连字符正好 32 位，与 {@code trace_id CHAR(32)} 对齐。
     * 不用"mailId + 时间戳"这类可读形式：trace_id 是用来 JOIN 回查的键，
     * 可读性没有价值，而碰撞会让两封邮件的调用记录串在一起。
     * </p>
     */
    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static Long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}

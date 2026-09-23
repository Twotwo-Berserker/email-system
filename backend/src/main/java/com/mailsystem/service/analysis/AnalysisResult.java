package com.mailsystem.service.analysis;

import com.mailsystem.entity.MailIntelligenceResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 分析结论 —— LLM 与规则兜底共用的内部契约。
 *
 * <h3>为什么需要这一层</h3>
 * <p>
 * LLM 与规则两条路径产出的东西结构完全不同（一个是 JSON，一个是几个插件的
 * 副作用），但下游要的是一模一样的东西。若各自直接写库，就会出现"LLM 路径写了
 * {@code confidence}、规则路径忘了写"这类字段级的不一致，而且很难发现。
 * 统一收敛到本类之后，落库只有一条代码路径。
 * </p>
 *
 * <h3>所有字段都有默认值</h3>
 * <p>
 * 这不是为了方便，而是<b>结构性要求</b>：LLM 返回的 JSON 可能缺字段、
 * 字段类型不对、或整体解析失败。任何一处缺失都必须能落到一个安全默认值上，
 * 而不是让整条分析管线抛出异常 —— 界面上宁可显示"其他 / 无摘要"，
 * 也不能显示一个空白的分类栏。
 * </p>
 */
public class AnalysisResult {

    public static final String RISK_LOW = MailIntelligenceResult.RISK_LOW;
    public static final String RISK_MEDIUM = MailIntelligenceResult.RISK_MEDIUM;
    public static final String RISK_HIGH = MailIntelligenceResult.RISK_HIGH;

    /** 分类默认值 */
    public static final String CATEGORY_OTHER = "其他";

    /**
     * 分类白名单。
     * <p>
     * 与 {@code prompt_template} 里的枚举、以及前端纠正下拉框保持同一份清单。
     * 三者若不一致，会出现"LLM 给出 A、用户只能选 B、库里存了 C"的混乱。
     * </p>
     * <p>
     * {@code 验证码} 只有 LLM 会给（规则没有对应关键词集），这是刻意的：
     * 验证码邮件的特征是语义的（"你的验证码是 123456"），关键词表很不稳。
     * </p>
     */
    public static final Set<String> CATEGORIES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "工作", "个人", "财务", "通知", "推广",
                    "社交", "安全", "教育", "验证码", "其他")));

    /** 单个 indicator / action 的长度上限（与 Prompt 里的要求一致，超出即截断） */
    public static final int MAX_LIST_ITEM_CHARS = 120;

    /** indicators / actions 各自最多保留的条数 */
    public static final int MAX_LIST_ITEMS = 8;

    private boolean spam;
    private int spamScore;
    private int priority = 50;
    private String risk = RISK_LOW;
    private String category = CATEGORY_OTHER;
    private String summary;

    private List<String> indicators = new ArrayList<>();
    private List<String> actions = new ArrayList<>();

    private BigDecimal confidence;

    // ==================== 元信息 ====================

    /** 结论来源: LLM / RULE */
    private String source;

    private String modelName;

    /** OPENAI_COMPAT / ANTHROPIC */
    private String provider;

    /** 结论所用的 Prompt 版本（规则兜底时为 null） */
    private String promptVersion;

    /** 降级/失败原因码；顺利走完 LLM 时为 null */
    private String errorCode;

    /** 解析过程中发现的问题，仅用于日志，不影响结论 */
    private final List<String> warnings = new ArrayList<>();

    // ==================== 构造 ====================

    /** 一个中性的起点：不垃圾、中优先级、低风险、归入"其他" */
    public static AnalysisResult neutral() {
        return new AnalysisResult();
    }

    /** 规则兜底结论 */
    public static AnalysisResult rule(String errorCode) {
        AnalysisResult result = new AnalysisResult();
        result.source = MailIntelligenceResult.SOURCE_RULE;
        result.errorCode = errorCode;
        return result;
    }

    // ==================== 规整 ====================

    /**
     * 把所有字段夹到合法范围。
     * <p>
     * 在<b>写入前</b>统一调用一次，而不是在每个解析分支里各夹一遍 ——
     * 后者只要漏掉一处，越界值就会落库（{@code spam_score} 列是 TINYINT/INT，
     * 但 {@code priority = 9999} 会直接毁掉优先级排序的语义）。
     * </p>
     */
    public AnalysisResult sanitize() {
        priority = clamp(priority, 0, 100);
        spamScore = clamp(spamScore, 0, 100);

        String normalizedRisk = risk == null ? null : risk.trim().toUpperCase();
        if (!RISK_HIGH.equals(normalizedRisk)
                && !RISK_MEDIUM.equals(normalizedRisk)
                && !RISK_LOW.equals(normalizedRisk)) {
            // 风险等级是安全信号，非法值不能悄悄变成"低风险"，
            // 因此回落到中档并留痕，由管理端日志暴露上游的不稳定
            if (normalizedRisk != null && !normalizedRisk.isEmpty()) {
                warnings.add("risk 取值非法，已回落到 MEDIUM: " + risk);
            }
            normalizedRisk = RISK_MEDIUM;
        }
        risk = normalizedRisk;

        category = normalizeCategory(category);
        summary = truncate(summary, 1000);
        indicators = normalizeList(indicators);
        actions = normalizeList(actions);

        if (confidence != null) {
            // 置信度允许缺失，但给了就必须在 [0,1]
            double v = confidence.doubleValue();
            if (v < 0 || v > 1) {
                warnings.add("confidence 越界，已置空: " + confidence);
                confidence = null;
            }
        }
        return this;
    }

    /**
     * 分类规整。
     * <p>
     * 白名单外的取值<b>不丢弃</b>：LLM 可能给出一个合理的、我们没枚举到的标签
     * （比如"政务"）。把它强行改成"其他"会丢掉真实信息，而分类列是
     * {@code VARCHAR(64)}、展示上也无害，因此保留原值并截断。
     * 只有空值才回落到"其他"。
     * </p>
     */
    private String normalizeCategory(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return CATEGORY_OTHER;
        }
        String trimmed = truncate(raw.trim(), 64);
        if (!CATEGORIES.contains(trimmed)) {
            warnings.add("category 不在白名单内，按原值保留: " + trimmed);
        }
        return trimmed;
    }

    /**
     * 数组字段规整：去掉 null/空白项、逐项截断、去重、限量。
     * <p>
     * 去重是必要的 —— LLM 很爱把同一条依据换个措辞说两遍，
     * 而界面上重复的条目看起来像是渲染出了问题。
     * </p>
     */
    private List<String> normalizeList(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            cleaned.add(truncate(trimmed, MAX_LIST_ITEM_CHARS));
            if (cleaned.size() >= MAX_LIST_ITEMS) {
                break;
            }
        }
        return new ArrayList<>(cleaned);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 转到可落库的实体。
     * <p>
     * {@code indicators}/{@code actions} 由调用方序列化成 JSON 字符串后填入
     * （列表本身不知道该用什么 ObjectMapper）。
     * </p>
     */
    public MailIntelligenceResult toEntity(Long mailId, Long userId,
                                           String pipelineVersion, Integer latencyMs) {
        sanitize();
        MailIntelligenceResult entity = new MailIntelligenceResult();
        entity.setMailId(mailId);
        entity.setUserId(userId);
        entity.setStatus(MailIntelligenceResult.STATUS_DONE);
        entity.setSource(source);
        entity.setCategory(category);
        entity.setIsSpam(spam ? 1 : 0);
        entity.setPriority(priority);
        entity.setRiskLevel(risk);
        entity.setSpamScore(spamScore);
        entity.setConfidence(confidence);
        entity.setSummary(summary);
        entity.setModelName(modelName);
        entity.setProvider(provider);
        entity.setPipelineVersion(pipelineVersion);
        entity.setPromptVersion(promptVersion);
        entity.setLatencyMs(latencyMs);
        entity.setErrorCode(errorCode);
        return entity;
    }

    // ==================== getter / setter ====================

    public boolean isSpam() {
        return spam;
    }

    public void setSpam(boolean spam) {
        this.spam = spam;
    }

    public int getSpamScore() {
        return spamScore;
    }

    public void setSpamScore(int spamScore) {
        this.spamScore = spamScore;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getRisk() {
        return risk;
    }

    public void setRisk(String risk) {
        this.risk = risk;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<String> getIndicators() {
        return indicators;
    }

    public void setIndicators(List<String> indicators) {
        this.indicators = indicators;
    }

    public List<String> getActions() {
        return actions;
    }

    public void setActions(List<String> actions) {
        this.actions = actions;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void addWarning(String warning) {
        this.warnings.add(warning);
    }
}

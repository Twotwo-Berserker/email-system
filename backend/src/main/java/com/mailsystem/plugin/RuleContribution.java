package com.mailsystem.plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个规则插件对分析结论的贡献 —— <b>纯计算结果，不带任何副作用</b>。
 *
 * <h3>为什么需要这个类</h3>
 * <p>
 * 改造前的插件签名是 {@code void process(Mail)}，每个插件自己去写库
 * （{@code mailMapper.updateCategory/markSpam/updatePriority/updateSummary}）。
 * 这带来三个问题，都在改用本类之后消失：
 * </p>
 * <ol>
 *   <li><b>并发写竞态</b>：{@code SummaryPlugin} 与 {@code LlmPlugin} 都写
 *       {@code mail.summary}，且都标了 {@code @Async}，谁后写谁赢</li>
 *   <li><b>结果被丢弃</b>：{@code LinkDetectionPlugin} 算出威胁列表却无处可放，
 *       最终只是拼了个字符串又扔掉（原代码 :102-109 的注释写着"与摘要插件协同使用"，
 *       但那个协同从未实现）。现在它有 {@link #indicators} 可放</li>
 *   <li><b>无法多收件人</b>：写 {@code mail} 表意味着结论是"每封邮件一份"，
 *       而实际需要的是"每个收件人一份"</li>
 * </ol>
 * <p>
 * 所有字段都可为 null，表示"本插件对这项没有意见"。合并逻辑见
 * {@code RuleAnalyzer}。
 * </p>
 */
public class RuleContribution {

    /** 分类标签；null 表示不表态 */
    private String category;

    /** 是否垃圾: 1=是, 0=否, null=不表态 */
    private Integer isSpam;

    /** 垃圾程度 0-100；null 表示不表态 */
    private Integer spamScore;

    /** 优先级 0-100；null 表示不表态 */
    private Integer priority;

    /** 摘要；null 表示不表态 */
    private String summary;

    /** 风险等级 LOW/MEDIUM/HIGH；null 表示不表态 */
    private String riskLevel;

    /** 判定依据（会带上插件名作为前缀合并进最终结果） */
    private final List<String> indicators = new ArrayList<>();

    // ==================== 便捷构造 ====================

    public static RuleContribution category(String category) {
        RuleContribution c = new RuleContribution();
        c.category = category;
        return c;
    }

    public static RuleContribution spam(Integer isSpam, Integer spamScore) {
        RuleContribution c = new RuleContribution();
        c.isSpam = isSpam;
        c.spamScore = spamScore;
        return c;
    }

    public static RuleContribution priority(Integer priority) {
        RuleContribution c = new RuleContribution();
        c.priority = priority;
        return c;
    }

    public static RuleContribution summary(String summary) {
        RuleContribution c = new RuleContribution();
        c.summary = summary;
        return c;
    }

    public static RuleContribution risk(String riskLevel) {
        RuleContribution c = new RuleContribution();
        c.riskLevel = riskLevel;
        return c;
    }

    // ==================== 链式写法（让各插件的 contribute 读起来像一句话） ====================

    public RuleContribution withCategory(String category) {
        this.category = category;
        return this;
    }

    public RuleContribution withSpam(Integer isSpam, Integer spamScore) {
        this.isSpam = isSpam;
        this.spamScore = spamScore;
        return this;
    }

    public RuleContribution withPriority(Integer priority) {
        this.priority = priority;
        return this;
    }

    public RuleContribution withSummary(String summary) {
        this.summary = summary;
        return this;
    }

    public RuleContribution withRisk(String riskLevel) {
        this.riskLevel = riskLevel;
        return this;
    }

    public RuleContribution withIndicator(String indicator) {
        if (indicator != null && !indicator.trim().isEmpty()) {
            this.indicators.add(indicator.trim());
        }
        return this;
    }

    // ==================== getter / setter ====================

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getIsSpam() {
        return isSpam;
    }

    public void setIsSpam(Integer isSpam) {
        this.isSpam = isSpam;
    }

    public Integer getSpamScore() {
        return spamScore;
    }

    public void setSpamScore(Integer spamScore) {
        this.spamScore = spamScore;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public List<String> getIndicators() {
        return indicators;
    }

    /** 本插件是否对结论有任何贡献 */
    public boolean isEmpty() {
        return category == null && isSpam == null && spamScore == null
                && priority == null && summary == null && riskLevel == null
                && indicators.isEmpty();
    }
}

package com.mailsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 邮件智能分析结果 —— <b>按收件人一行</b>（{@code uk_mail_user(mail_id, user_id)}）。
 *
 * <h3>为什么结果不写回 mail 表</h3>
 * <p>
 * 同一封邮件可以有多个收件人，而每人用的是<b>自己的 LLM Key</b>，
 * 分析结论（分类/摘要/风险）可能不同。若把结论写回 {@code mail} 的
 * {@code category}/{@code summary} 这类单值列，多收件人场景下就是
 * last-writer-wins —— A 的摘要会覆盖 B 的，且无人能察觉。
 * </p>
 * <p>
 * 因此这里的行才是权威数据源。{@code mail} 表上那四个同名旧列<b>不删除</b>，
 * 它们在读取时通过 {@code COALESCE} 充当过渡值与规则兜底值
 * （见 {@code MailMapper} 的查询）：分析尚未完成或分析失败时，
 * 界面回落到旧列而不是显示空白。
 * </p>
 *
 * <h3>用户纠正与机器结论分列存放</h3>
 * <p>
 * {@code overrideCategory}/{@code overrideIsSpam} 存用户的纠正结果，
 * 与机器结论（{@code category}/{@code isSpam}）分开。这样：
 * </p>
 * <ul>
 *   <li>管理员能对比"机器说 X、用户纠正为 Y"，这正是反馈汇总要的原始数据</li>
 *   <li>重跑分析不会抹掉用户的纠正</li>
 *   <li>用户可以撤销自己的纠正（把 override 置回 null）而无损机器结论</li>
 * </ul>
 */
@Data
@TableName("mail_intelligence_result")
public class MailIntelligenceResult {

    // ==================== 状态 ====================

    /** 已排队，尚未开始 */
    public static final String STATUS_PENDING = "PENDING";

    /** 分析进行中 */
    public static final String STATUS_RUNNING = "RUNNING";

    /** 分析完成（无论结论来自 LLM 还是规则） */
    public static final String STATUS_DONE = "DONE";

    /** 分析失败（理论上不应出现：失败会降级为规则结论而不是标记失败） */
    public static final String STATUS_FAILED = "FAILED";

    // ==================== 结论来源 ====================

    /** 结论来自 LLM */
    public static final String SOURCE_LLM = "LLM";

    /** 结论来自 Java 规则兜底 */
    public static final String SOURCE_RULE = "RULE";

    // ==================== 风险等级 ====================

    public static final String RISK_LOW = "LOW";
    public static final String RISK_MEDIUM = "MEDIUM";
    public static final String RISK_HIGH = "HIGH";

    /** 规则兜底按分数推出的风险等级阈值：>= 70 为高 */
    public static final int RISK_HIGH_SCORE = 70;

    /** 规则兜底按分数推出的风险等级阈值：>= 40 为中 */
    public static final int RISK_MEDIUM_SCORE = 40;

    // ==================== 失败原因码 ====================

    /** 无可用 Key，直接走规则 */
    public static final String ERROR_NO_KEY = "NO_KEY";

    /** 熔断开启中，未发起请求 */
    public static final String ERROR_CIRCUIT_OPEN = "CIRCUIT_OPEN";

    /** 触发每分钟调用上限 */
    public static final String ERROR_RATE_LIMITED = "RATE_LIMITED";

    /**
     * 分析线程池已满，任务被拒绝，就地走了纯规则的兜底。
     * <p>
     * 与 {@link #ERROR_RATE_LIMITED} 分开：限流是"我们主动限制对上游的调用量"，
     * 过载是"我们自己的处理能力不够了"。前者调阈值即可，后者要扩容或降载，
     * 混在一起会让运维看不出该做哪件事。
     * </p>
     */
    public static final String ERROR_OVERLOADED = "OVERLOADED";

    /** JVM 重启导致 PENDING/RUNNING 行成为孤儿，被定时任务回收 */
    public static final String ERROR_ORPHANED = "ORPHANED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long mailId;

    /** 归属收件人 —— 结论是按人算的，不是按邮件算的 */
    private Long userId;

    private String status;

    /** LLM / RULE */
    private String source;

    /** 机器分类结论 */
    private String category;

    /** 机器垃圾判定: 0=否, 1=是 */
    private Integer isSpam;

    /** 机器优先级评分 0-100 */
    private Integer priority;

    /** 风险等级 LOW/MEDIUM/HIGH */
    private String riskLevel;

    /** 垃圾程度评分 0-100（比布尔的 isSpam 更细，供管理端统计用） */
    private Integer spamScore;

    /** 模型置信度 0-1 */
    private BigDecimal confidence;

    /** 判定依据，JSON 数组的字符串形式 */
    private String indicators;

    /** 建议动作，JSON 数组的字符串形式 */
    private String actions;

    private String summary;

    /** 用户纠正后的分类；未被纠正时为 null */
    private String overrideCategory;

    /** 用户纠正后的垃圾判定；未被纠正时为 null */
    private Integer overrideIsSpam;

    /** 实际调用的模型名（规则兜底时为 null） */
    private String modelName;

    /** OPENAI_COMPAT / ANTHROPIC */
    private String provider;

    /** 分析管线版本，用于将来对比不同管线版本的准确率 */
    private String pipelineVersion;

    /** 本次使用的 Prompt 模板版本 */
    private String promptVersion;

    /** 主题+正文的 SHA-256，用于判断正文是否变了、能否复用旧结论 */
    private String contentHash;

    /** 重跑次数 */
    private Integer revision;

    /** 分析耗时（毫秒） */
    private Integer latencyMs;

    /** 失败/降级原因码 */
    private String errorCode;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 用户实际看到的分类：纠正值优先，否则机器结论。
     * <p>非数据库列，供服务层判断"是否需要纠正"用。</p>
     */
    public String effectiveCategory() {
        return overrideCategory != null ? overrideCategory : category;
    }

    /**
     * 用户实际看到的垃圾判定：纠正值优先，否则机器结论。
     */
    public Integer effectiveIsSpam() {
        return overrideIsSpam != null ? overrideIsSpam : isSpam;
    }
}

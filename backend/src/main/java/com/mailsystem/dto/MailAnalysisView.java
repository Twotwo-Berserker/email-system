package com.mailsystem.dto;

import com.mailsystem.entity.MailIntelligenceResult;
import com.mailsystem.entity.UserFeedback;
import com.mailsystem.service.analysis.AnalysisResult;
import com.mailsystem.service.analysis.JsonSupport;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 邮件的智能分析面板（详情页用）。
 *
 * <h3>为什么单独开一个接口，而不是塞进 Mail 实体</h3>
 * <p>
 * 列表页需要的是"分类 / 垃圾 / 优先级 / 摘要"四个字段，这四个已经由
 * {@code MailMapper} 的 {@code COALESCE} 覆盖到 {@code Mail} 上了，前端零改动。
 * 而详情页要展示的是一整块东西：来源、风险、置信度、依据列表、建议动作、
 * 模型名、Prompt 版本、分析状态、失败原因、以及"机器的结论是什么"
 * （反馈界面要拿它跟用户的纠正做对比）。
 * </p>
 * <p>
 * 把这些塞进 {@code Mail} 会让每一次列表查询都多带十个字段与大段 JSON，
 * 而列表页一个都用不上。因此分成两个接口：列表走 {@code Mail}（已覆盖），
 * 详情页额外调一次本接口。
 * </p>
 *
 * <h3>展示值已经把用户的纠正算进去了</h3>
 * <p>
 * {@code category}/{@code isSpam} 是<b>生效值</b>（有纠正就用纠正）；
 * {@code machineCategory}/{@code machineIsSpam} 是<b>机器的原始结论</b>。
 * 反馈界面需要两者都有：用户点开纠正框时，得看到"机器说的是什么"，
 * 否则改完就再也想不起来原来是什么了，也没法判断这次纠正是否必要。
 * </p>
 */
@Data
public class MailAnalysisView {

    private Long mailId;

    /** PENDING / RUNNING / DONE / FAILED；没有结果行时为 null */
    private String status;

    /** LLM / RULE */
    private String source;

    // ==================== 生效值（已合入用户纠正） ====================

    private String category;
    private Integer isSpam;

    // ==================== 机器结论（未合入纠正） ====================

    private String machineCategory;
    private Integer machineIsSpam;

    /** 用户纠正过的分类；没纠正过为 null */
    private String overrideCategory;

    private Integer overrideIsSpam;

    // ==================== 结论细节 ====================

    private Integer priority;
    private String riskLevel;
    private Integer spamScore;
    private BigDecimal confidence;
    private String summary;

    /** 判定依据（已从 JSON 解析成列表） */
    private List<String> indicators = new ArrayList<>();

    /** 建议动作 */
    private List<String> actions = new ArrayList<>();

    // ==================== 元信息 ====================

    private String modelName;
    private String promptVersion;
    private String pipelineVersion;

    /** 分析耗时（毫秒） */
    private Integer latencyMs;

    /** 降级/失败原因码；顺利走完 LLM 时为 null */
    private String errorCode;

    /** 分析完成时间（行更新时间）；未完成时为 null */
    private LocalDateTime analyzedAt;

    /** 重跑次数 */
    private Integer revision;

    // ==================== 当前用户的反馈 ====================

    /** 当前用户已提交的反馈；没提交过为 null */
    private UserFeedback.UserFeedbackView feedback;

    /** 可供前端纠正用的分类清单 —— 与 Prompt 枚举、服务端白名单同源 */
    private List<String> categories;

    /**
     * 从结果行构造展示视图。
     * <p>
     * 没有结果行（还没分析 / 该用户从未触发过分析）时返回一个
     * {@code status=null} 的空视图，而不是 null：前端只需处理"字段为空"，
     * 不必再处理"整个对象为空"，少一个分支就少一个空指针。
     * </p>
     */
    public static MailAnalysisView from(MailIntelligenceResult result,
                                        UserFeedback feedback) {
        MailAnalysisView view = new MailAnalysisView();
        view.setCategories(new ArrayList<>(AnalysisResult.CATEGORIES));
        if (result != null) {
            view.setMailId(result.getMailId());
            view.setStatus(result.getStatus());
            view.setSource(result.getSource());
            view.setMachineCategory(result.getCategory());
            view.setMachineIsSpam(result.getIsSpam());
            view.setOverrideCategory(result.getOverrideCategory());
            view.setOverrideIsSpam(result.getOverrideIsSpam());
            // 生效值：有纠正用纠正，否则用机器结论
            view.setCategory(result.effectiveCategory());
            view.setIsSpam(result.effectiveIsSpam());
            view.setPriority(result.getPriority());
            view.setRiskLevel(result.getRiskLevel());
            view.setSpamScore(result.getSpamScore());
            view.setConfidence(result.getConfidence());
            view.setSummary(result.getSummary());
            view.setIndicators(JsonSupport.readStringList(result.getIndicators()));
            view.setActions(JsonSupport.readStringList(result.getActions()));
            view.setModelName(result.getModelName());
            view.setPromptVersion(result.getPromptVersion());
            view.setPipelineVersion(result.getPipelineVersion());
            view.setLatencyMs(result.getLatencyMs());
            view.setErrorCode(result.getErrorCode());
            view.setRevision(result.getRevision());
            // 结论写完就成了"分析时间"。update_time 在结论写入时由
            // ON UPDATE CURRENT_TIMESTAMP 刷新，因此它正是完成时刻
            view.setAnalyzedAt(result.getUpdateTime());
        }
        view.setFeedback(feedback == null ? null : feedback.toView());
        return view;
    }
}

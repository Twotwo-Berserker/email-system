package com.mailsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 邮件分析人工反馈（每用户每封一条，可覆盖更新）
 *
 * <h3>为什么要把"反馈时的机器结论"冗余存下来</h3>
 * <p>
 * {@code sourceAtFeedback}/{@code categoryAtFeedback}/{@code isSpamAtFeedback}/
 * {@code modelName} 都是反馈发生那一刻机器结论的<b>快照</b>。
 * 不这么做、只在统计时回查 {@code mail_intelligence_result} 的话，
 * 该行后来被重跑覆盖（换了模型、调了 Prompt、重试成功）之后，
 * 历史反馈就会被拿去和<b>新的</b>机器结论比较 —— 准确率失真且无法追溯。
 * </p>
 * <p>
 * 快照是"用户当时在界面上看到的是什么"，这才是反馈真正针对的对象。
 * </p>
 *
 * <h3>本表不驱动自动学习</h3>
 * <p>
 * 按既定范围，反馈只用于<b>存储 + 管理员汇总统计</b>。这里不做在线学习、
 * 不自动调整阈值、不回写模型。{@code override*} 字段只影响该用户自己
 * 看到的那封邮件的分类，是"人工纠正"而非"训练样本"。
 * </p>
 */
@Data
@TableName("user_feedback")
public class UserFeedback {

    /** 认可机器的结论 */
    public static final String TYPE_AGREE = "AGREE";

    /** 纠正机器的结论 */
    public static final String TYPE_DISAGREE = "DISAGREE";

    /** 备注长度上限，与列定义一致 */
    public static final int MAX_COMMENT_CHARS = 512;

    /** 纠正后的分类长度上限，与列定义一致 */
    public static final int MAX_CATEGORY_CHARS = 64;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long mailId;

    /** 反馈人 —— 反馈是对"我这份分析结论"的评价，因此必须带 userId */
    private Long userId;

    /** AGREE / DISAGREE */
    private String feedbackType;

    /** 用户给出的正确分类；只认可不纠正时为 null */
    private String correctedCategory;

    /** 用户给出的正确垃圾判定；未纠正时为 null */
    private Integer correctedSpam;

    /** 备注 */
    private String comment;

    // ==================== 反馈时刻的机器结论快照 ====================

    /** LLM / RULE —— 用来统计"规则兜底被纠正的比例是否显著高于 LLM" */
    private String sourceAtFeedback;

    private String modelName;

    private String categoryAtFeedback;

    private Integer isSpamAtFeedback;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 回显给前端的反馈视图。
     * <p>
     * 字段与实体一致，唯一的区别是<b>带上</b> {@code createTime}/{@code updateTime}：
     * 实体里的时间列由数据库维护，读取时是有的，但接口直接返回实体会让
     * 快照字段（{@code sourceAtFeedback} 等）也一起暴露出去 —— 那是内部统计口径，
     * 前端不需要也不该依赖它。
     * </p>
     */
    public UserFeedbackView toView() {
        UserFeedbackView view = new UserFeedbackView();
        view.setFeedbackType(feedbackType);
        view.setCorrectedCategory(correctedCategory);
        view.setCorrectedSpam(correctedSpam);
        view.setComment(comment);
        view.setCreateTime(createTime);
        view.setUpdateTime(updateTime);
        return view;
    }

    /** 反馈的对外视图 */
    @Data
    public static class UserFeedbackView {
        private String feedbackType;
        private String correctedCategory;
        private Integer correctedSpam;
        private String comment;
        private LocalDateTime createTime;
        private LocalDateTime updateTime;
    }
}

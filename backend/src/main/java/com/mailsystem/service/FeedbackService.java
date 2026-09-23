package com.mailsystem.service;

import com.mailsystem.dto.FeedbackRequest;
import com.mailsystem.entity.UserFeedback;

import java.util.Map;

/**
 * 人工反馈回流服务。
 *
 * <h3>反馈做什么、不做什么</h3>
 * <p>
 * <b>做</b>：把用户的判断落库，并把"当时机器说了什么"一起快照下来，
 * 供管理端统计准确率、按来源/模型/分类分组对比，以及下钻看分歧样本。
 * </p>
 * <p>
 * <b>不</b>做：自动学习。反馈不会回流进 Prompt、不会微调模型、不会自动改
 * 其他邮件的结论。这是刻意的 —— 一条反馈就改动全局行为，意味着一个用户的
 * 误操作会污染所有人的分类，而且这种污染无法回滚。反馈目前是<b>度量</b>，
 * 不是<b>训练信号</b>。
 * </p>
 *
 * <h3>纠正与反馈是两件事，落在两处</h3>
 * <ul>
 *   <li>{@code user_feedback} —— 评价记录，用于统计。每条只属于提交它的用户</li>
 *   <li>{@code mail_intelligence_result.override_*} —— 生效的纠正，
 *       用于改变"这封邮件显示成什么"</li>
 * </ul>
 * <p>
 * 两者都写，是因为它们的生命周期不同：反馈是历史（会被统计、不该被后续操作改），
 * 覆盖是状态（可能被下一次纠正再改）。合并成一张表会出现
 * "为了改分类而伪造一条反馈"或"重跑分析把统计抹掉"这类问题。
 * </p>
 */
public interface FeedbackService {

    /**
     * 提交或覆盖当前用户对某封邮件的反馈。
     *
     * @throws RuntimeException 反馈类型非法、纠正值越界、或该用户与此邮件无关
     */
    UserFeedback submit(Long mailId, Long userId, FeedbackRequest request);

    /**
     * 查当前用户对某封邮件的反馈；没提交过返回 null。
     */
    UserFeedback findMine(Long mailId, Long userId);

    // ==================== 管理端聚合 ====================

    /**
     * 汇总统计：总准确率、按分类/来源/模型分组的采纳率。
     */
    Map<String, Object> stats();

    /**
     * 分歧样本分页列表（含邮件主题，供管理端下钻）。
     */
    Map<String, Object> disagreements(int page, int pageSize);
}

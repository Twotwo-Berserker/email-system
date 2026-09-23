package com.mailsystem.service;

import com.mailsystem.dto.PageResult;
import com.mailsystem.dto.UserAdminView;

import java.util.Map;

/**
 * 管理端服务接口
 */
public interface AdminService {

    /**
     * 用户列表（分页 + 关键字/角色/状态筛选）
     */
    PageResult<UserAdminView> listUsers(int page, int pageSize, String keyword, String role, Integer status);

    /**
     * 修改用户角色。
     * <p>拒绝：给自己降级、降级最后一个启用状态的管理员。</p>
     */
    void updateRole(Long operatorId, Long targetUserId, String role);

    /**
     * 启用/禁用用户。
     * <p>拒绝：禁用自己、禁用最后一个启用状态的管理员。</p>
     */
    void updateStatus(Long operatorId, Long targetUserId, Integer status);

    /**
     * 重置用户密码（不需要旧密码），并置强制改密标记
     */
    void resetPassword(Long operatorId, Long targetUserId, String newPassword);

    /**
     * 管理端概览计数
     */
    Map<String, Object> overview();

    // ==================== 反馈汇总（P3） ====================

    /**
     * 反馈汇总统计：总准确率 + 按分类/来源/模型分组的采纳率。
     * <p>
     * 数据源是 {@code user_feedback}，只统计<b>表过态</b>的邮件 ——
     * 未反馈的不计入分母，否则"用户懒得点"会被算成"用户认可"。
     * </p>
     */
    Map<String, Object> feedbackStats();

    /**
     * 分歧样本分页列表（user_feedback 中 DISAGREE 的记录 + 邮件主题）
     */
    Map<String, Object> feedbackDisagreements(int page, int pageSize);

    // ==================== LLM 调用监控（P3） ====================

    /**
     * 近 N 天的 LLM 调用指标：调用数、成功率、降级数、平均延迟、token 消耗、
     * 以及按用户的分布。
     * <p>
     * 回答的是"分类质量是不是在下降、是谁的 Key 在失败"，
     * 与 {@link #feedbackStats()} 的人工判断互为印证。
     * </p>
     *
     * @param days 回溯天数；小于 1 时按 1 处理
     */
    Map<String, Object> llmStats(int days);

    /**
     * 分析结果的状态分布（PENDING/RUNNING/DONE/FAILED 各多少），
     * 用于发现"大量邮件卡在 RUNNING"这类管线停滞
     */
    Map<String, Object> analysisStatusStats();
}

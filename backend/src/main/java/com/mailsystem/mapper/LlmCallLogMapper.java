package com.mailsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mailsystem.entity.LlmCallLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * LLM 调用日志 Mapper
 */
@Mapper
public interface LlmCallLogMapper extends BaseMapper<LlmCallLog> {

    /**
     * 调用状态分布（含降级为规则的比例）
     */
    @Select("SELECT status, COUNT(*) AS cnt FROM llm_call_log "
            + "WHERE create_time >= #{since} GROUP BY status ORDER BY cnt DESC")
    List<Map<String, Object>> countByStatusSince(@Param("since") LocalDateTime since);

    /**
     * 总体指标：调用数、平均延迟、token 消耗。
     * <p>
     * {@code notSuccess} 是所有非 {@code SUCCESS} 的合计，用于一眼看出"降级率"。
     * 它刻意<b>不</b>按 {@code FALLBACK_RULE} 单列统计：降级不是调用的一种结局，
     * 而是调用失败之后的处置方式 —— 权威表述在
     * {@code mail_intelligence_result.source = 'RULE'} 上，
     * 在那里能按分类/按邮件维度下钻，而这里只有一行汇总。
     * 若在这里也列一个 {@code fallbackRule}，同一个数字就有了两个口径，
     * 迟早会出现"两处对不上"的排查成本。
     * </p>
     */
    @Select("SELECT COUNT(*) AS calls, "
            + "SUM(status = 'SUCCESS') AS success, "
            + "SUM(status <> 'SUCCESS') AS notSuccess, "
            + "SUM(status = 'TIMEOUT') AS timeout, "
            + "SUM(status = 'HTTP_ERROR') AS httpError, "
            + "SUM(status = 'PARSE_ERROR') AS parseError, "
            + "SUM(status = 'REJECTED') AS rejected, "
            + "SUM(status = 'SKIPPED_NO_KEY') AS skippedNoKey, "
            + "AVG(NULLIF(latency_ms, 0)) AS avgLatencyMs, "
            + "SUM(COALESCE(total_tokens, 0)) AS totalTokens "
            + "FROM llm_call_log WHERE create_time >= #{since}")
    Map<String, Object> summarySince(@Param("since") LocalDateTime since);

    /**
     * 按收件人统计，用于排查"某个用户的邮件一直在降级"。
     * <p>
     * 分组键是 {@code user_id} —— 即被分析邮件的收件人，<b>不是</b> Key 的归属者。
     * 因此这里回答的是"谁的邮件没拿到 LLM 结论"，而不是"谁的 Key 坏了"；
     * 后者要在 {@code llm_config} 上排查，本表提供不了。
     * </p>
     * <p>
     * {@code userLabel} 取昵称优先、邮箱兜底 —— 管理端看到 {@code #7} 等于什么也没说。
     * </p>
     */
    @Select("SELECT l.user_id AS userId, COALESCE(u.nickname, u.email) AS userLabel, "
            + "COUNT(*) AS calls, SUM(l.status = 'SUCCESS') AS success, "
            + "SUM(l.status <> 'SUCCESS') AS notSuccess, "
            + "SUM(COALESCE(l.total_tokens, 0)) AS totalTokens "
            + "FROM llm_call_log l LEFT JOIN user u ON u.id = l.user_id "
            + "WHERE l.create_time >= #{since} AND l.user_id IS NOT NULL "
            + "GROUP BY l.user_id, userLabel ORDER BY calls DESC LIMIT #{limit}")
    List<Map<String, Object>> statsByUserSince(@Param("since") LocalDateTime since,
                                               @Param("limit") int limit);
}

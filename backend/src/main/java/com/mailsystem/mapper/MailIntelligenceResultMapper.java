package com.mailsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mailsystem.entity.MailIntelligenceResult;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 邮件智能分析结果 Mapper（注解式，与本项目其余 Mapper 一致）
 */
@Mapper
public interface MailIntelligenceResultMapper extends BaseMapper<MailIntelligenceResult> {

    /**
     * 取某收件人对某封邮件的分析结果
     */
    @Select("SELECT * FROM mail_intelligence_result "
            + "WHERE mail_id = #{mailId} AND user_id = #{userId} LIMIT 1")
    MailIntelligenceResult selectOne(@Param("mailId") Long mailId, @Param("userId") Long userId);

    /**
     * 批量取某收件人对多封邮件的分析结果。
     * <p>
     * 供管理端"分歧样本下钻"与列表页批量补数据用，避免 N+1。
     * 调用方需保证 {@code mailIds} 非空 —— 空集合会拼出 {@code IN ()} 语法错误，
     * 这里用 {@code <script>} 的 {@code <if>} 兜住。
     * </p>
     */
    @Select("<script>"
            + "SELECT * FROM mail_intelligence_result WHERE user_id = #{userId} "
            + "<if test='mailIds != null and mailIds.size() > 0'>"
            + "AND mail_id IN <foreach collection='mailIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</if>"
            + "</script>")
    List<MailIntelligenceResult> selectByMailIds(@Param("userId") Long userId,
                                                 @Param("mailIds") List<Long> mailIds);

    /**
     * 开始一次分析：不存在则插入 RUNNING 行，已存在则置为 RUNNING 并累加重跑次数。
     * <p>
     * {@code revision} 只在"同一封邮件被重新分析"时增长，因此它的值减一
     * 就是这封邮件此前被分析过的次数 —— 重跑率是判断管线是否稳定的指标。
     * </p>
     * <p>
     * 刻意<b>不</b>覆盖 {@code override_category}/{@code override_is_spam}：
     * 用户的纠正不该被一次重跑抹掉，服务层会自己读出来再合入展示。
     * </p>
     */
    @Insert("INSERT INTO mail_intelligence_result (mail_id, user_id, status, content_hash, revision) "
            + "VALUES (#{mailId}, #{userId}, #{status}, #{contentHash}, 1) "
            + "ON DUPLICATE KEY UPDATE status = VALUES(status), "
            + "content_hash = VALUES(content_hash), revision = revision + 1")
    int beginRun(@Param("mailId") Long mailId,
                 @Param("userId") Long userId,
                 @Param("status") String status,
                 @Param("contentHash") String contentHash);

    /**
     * 写入分析结论。
     * <p>
     * 显式列出每一列而不用 {@code updateById}，原因是后者会跳过 null 字段 ——
     * 这次成功了、上次失败的 {@code error_code} 就永远清不掉，
     * 界面上会一直挂着一个已经没意义的失败原因。
     * </p>
     * <p>
     * 同时刻意不触碰 {@code override_*} 与 {@code revision}：
     * 前者是用户的纠正，后者由 {@link #beginRun} 维护。
     * </p>
     */
    @Update("UPDATE mail_intelligence_result SET status = #{r.status}, source = #{r.source}, "
            + "category = #{r.category}, is_spam = #{r.isSpam}, priority = #{r.priority}, "
            + "risk_level = #{r.riskLevel}, spam_score = #{r.spamScore}, confidence = #{r.confidence}, "
            + "indicators = #{r.indicators}, actions = #{r.actions}, summary = #{r.summary}, "
            + "model_name = #{r.modelName}, provider = #{r.provider}, "
            + "pipeline_version = #{r.pipelineVersion}, prompt_version = #{r.promptVersion}, "
            + "latency_ms = #{r.latencyMs}, error_code = #{r.errorCode} "
            + "WHERE mail_id = #{r.mailId} AND user_id = #{r.userId}")
    int complete(@Param("r") MailIntelligenceResult r);

    /**
     * 回收孤儿行：JVM 在分析中途退出会留下永远停留的 PENDING/RUNNING 行，
     * 界面上表现为"分析中"永不结束。
     * <p>
     * 判断时间用 {@code COALESCE(update_time, create_time)} ——
     * 新插入的行 {@code update_time} 是 NULL（列定义只有 {@code ON UPDATE}，
     * 没有 {@code DEFAULT CURRENT_TIMESTAMP}），直接比 {@code update_time}
     * 会因为 NULL 比较恒为假而永远回收不到，这正是本方法存在的前提。
     * </p>
     */
    @Update("UPDATE mail_intelligence_result SET status = 'FAILED', error_code = 'ORPHANED' "
            + "WHERE status IN ('PENDING', 'RUNNING') "
            + "AND COALESCE(update_time, create_time) < #{cutoff}")
    int reclaimOrphans(@Param("cutoff") LocalDateTime cutoff);

    // ==================== 用户纠正（override） ====================

    /**
     * 写入用户的纠正。
     *
     * <h4>为什么还可能 INSERT</h4>
     * <p>
     * 用户可以对一封<b>从未被分析过</b>的邮件提出异议 —— 列表上显示的分类
     * 来自 {@code mail} 表的规则值（读取端的 {@code COALESCE} 兜底），
     * 这封邮件可能一个 {@code mail_intelligence_result} 行都没有
     * （比如该用户没有 Key、或邮件是在分析管线上线前收到的）。
     * 此时只发 UPDATE 会静默影响 0 行，用户的纠正看起来"保存成功"却从未生效。
     * </p>
     * <p>
     * 这种情况下新建的行 {@code status='DONE'} 但 {@code source} 为 NULL ——
     * 与 {@code completeFailure} 用的是同一个约定：{@code source} 为 NULL 表示
     * "这一行没有机器结论"。读取端看到结论列为 NULL 会回落到 {@code mail} 表，
     * 界面不会因此空白。
     * </p>
     *
     * <h4>{@code COALESCE(VALUES(x), x)} 的用意</h4>
     * <p>
     * 请求里没提到的纠正项（值为 NULL）不该抹掉已有的值 ——
     * 用户只改分类、没提垃圾标记时，上次的垃圾纠正必须留着。
     * 要清除纠正请用 {@link #clearOverride}。
     * </p>
     */
    @Insert("INSERT INTO mail_intelligence_result "
            + "(mail_id, user_id, status, override_category, override_is_spam) "
            + "VALUES (#{mailId}, #{userId}, 'DONE', #{category}, #{isSpam}) "
            + "ON DUPLICATE KEY UPDATE "
            + "override_category = COALESCE(VALUES(override_category), override_category), "
            + "override_is_spam = COALESCE(VALUES(override_is_spam), override_is_spam)")
    int upsertOverride(@Param("mailId") Long mailId, @Param("userId") Long userId,
                       @Param("category") String category, @Param("isSpam") Integer isSpam);

    /**
     * 清除用户的纠正（用户从"纠正"改为"认可"时）。
     * <p>
     * 必须用显式 SQL：{@code updateById} 会跳过 null 字段，
     * 把实体上的 {@code overrideCategory} 置空再保存是个静默的空操作，
     * 表现是"点了认可，但分类还是错的"。
     * </p>
     */
    @Update("UPDATE mail_intelligence_result SET override_category = NULL, override_is_spam = NULL "
            + "WHERE mail_id = #{mailId} AND user_id = #{userId}")
    int clearOverride(@Param("mailId") Long mailId, @Param("userId") Long userId);

    /**
     * 统计各状态的行数（管理端监控用）
     */
    @Select("SELECT status, COUNT(*) AS cnt FROM mail_intelligence_result GROUP BY status")
    List<java.util.Map<String, Object>> countByStatus();
}

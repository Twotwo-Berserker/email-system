package com.mailsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mailsystem.entity.UserFeedback;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 邮件分析人工反馈 Mapper
 *
 * <h3>统计查询为什么直接返回 Map</h3>
 * <p>
 * 这些是<b>只读的管理端聚合</b>，列名与语义一一对应、不需要参与任何业务计算，
 * 因此不引入 DTO 层。代价是列名变化不会被编译器发现 ——
 * 所以每一条 SQL 都显式用 {@code AS} 定死别名，前端依赖的是别名而不是物理列名。
 * </p>
 * <p>
 * 注意 MySQL 的 {@code SUM(cond)} 返回 {@code DECIMAL}，在 Map 里是
 * {@code BigDecimal}；前端与 {@code AdminService} 都按数值处理，不要当成 Integer。
 * </p>
 */
@Mapper
public interface UserFeedbackMapper extends BaseMapper<UserFeedback> {

    @Select("SELECT * FROM user_feedback WHERE mail_id = #{mailId} AND user_id = #{userId} LIMIT 1")
    UserFeedback selectOne(@Param("mailId") Long mailId, @Param("userId") Long userId);

    /**
     * 提交或覆盖反馈。
     * <p>
     * {@code uk_mail_user(mail_id, user_id)} 保证每人每封只有一条，
     * 用户改主意（先 👎 再 👍）时走 {@code ON DUPLICATE KEY UPDATE} 覆盖，
     * 而不是插入第二行 —— 否则统计时同一条反馈会被算两次。
     * </p>
     * <p>
     * 刻意<b>不</b>更新 {@code create_time}：它记的是"用户第一次表态的时间"，
     * 改主意不该让这条反馈看起来是新的。{@code update_time} 会自动跟随。
     * </p>
     */
    @Insert("INSERT INTO user_feedback (mail_id, user_id, feedback_type, corrected_category, "
            + "corrected_spam, comment, source_at_feedback, model_name, "
            + "category_at_feedback, is_spam_at_feedback) "
            + "VALUES (#{mailId}, #{userId}, #{feedbackType}, #{correctedCategory}, "
            + "#{correctedSpam}, #{comment}, #{sourceAtFeedback}, #{modelName}, "
            + "#{categoryAtFeedback}, #{isSpamAtFeedback}) "
            + "ON DUPLICATE KEY UPDATE feedback_type = VALUES(feedback_type), "
            + "corrected_category = VALUES(corrected_category), "
            + "corrected_spam = VALUES(corrected_spam), "
            + "comment = VALUES(comment), "
            + "source_at_feedback = VALUES(source_at_feedback), "
            + "model_name = VALUES(model_name), "
            + "category_at_feedback = VALUES(category_at_feedback), "
            + "is_spam_at_feedback = VALUES(is_spam_at_feedback)")
    int upsert(UserFeedback feedback);

    // ==================== 管理端聚合统计 ====================

    /**
     * 总准确率的分母与分子。
     * <p>
     * 只统计"纠正"与"认可"两类，未反馈的邮件<b>不计入分母</b> ——
     * 把沉默当作认可会得到一个漂亮但无意义的准确率。
     * </p>
     */
    @Select("SELECT COUNT(*) AS total, "
            + "SUM(feedback_type = 'AGREE') AS agree, "
            + "SUM(feedback_type = 'DISAGREE') AS disagree "
            + "FROM user_feedback")
    Map<String, Object> accuracyTotals();

    /**
     * 按机器给出的分类分组，看哪一类最容易被纠正
     */
    @Select("SELECT COALESCE(category_at_feedback, '(未分类)') AS category, "
            + "COUNT(*) AS total, "
            + "SUM(feedback_type = 'AGREE') AS agree, "
            + "SUM(feedback_type = 'DISAGREE') AS disagree "
            + "FROM user_feedback GROUP BY category_at_feedback ORDER BY total DESC")
    List<Map<String, Object>> statsByCategory();

    /**
     * 按结论来源分组。这是最有价值的一组：
     * 规则兜底的纠正率若显著高于 LLM，说明兜底结论需要收紧或需要修 Key。
     */
    @Select("SELECT COALESCE(source_at_feedback, '(未知)') AS source, "
            + "COUNT(*) AS total, "
            + "SUM(feedback_type = 'AGREE') AS agree, "
            + "SUM(feedback_type = 'DISAGREE') AS disagree "
            + "FROM user_feedback GROUP BY source_at_feedback ORDER BY total DESC")
    List<Map<String, Object>> statsBySource();

    /**
     * 按模型分组，用于对比不同模型/不同 Prompt 版本的效果
     */
    @Select("SELECT COALESCE(model_name, '(规则兜底)') AS model, "
            + "COUNT(*) AS total, "
            + "SUM(feedback_type = 'AGREE') AS agree, "
            + "SUM(feedback_type = 'DISAGREE') AS disagree "
            + "FROM user_feedback GROUP BY model_name ORDER BY total DESC")
    List<Map<String, Object>> statsByModel();

    /**
     * 分歧样本列表（管理端下钻到具体邮件）
     */
    @Select("SELECT * FROM user_feedback WHERE feedback_type = 'DISAGREE' "
            + "ORDER BY create_time DESC LIMIT #{limit} OFFSET #{offset}")
    List<UserFeedback> selectDisagreements(@Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM user_feedback WHERE feedback_type = 'DISAGREE'")
    long countDisagreements();
}

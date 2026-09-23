package com.mailsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mailsystem.entity.Mail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 邮件 Mapper
 *
 * <h3>为什么查询里带着一个 {@code LEFT JOIN mail_intelligence_result}</h3>
 * <p>
 * 分类 / 垃圾标记 / 优先级 / 摘要这四项现在是<b>按收件人</b>计算的
 * （每人用自己的 LLM Key），结论存在 {@code mail_intelligence_result} 里，
 * 而 {@code mail} 表上的同名列降级为"规则兜底值 / 分析完成前的过渡值"。
 * </p>
 * <p>
 * 读取时用 {@code COALESCE(ir.x, m.x)} 让"当前收件人自己的结论"优先：
 * </p>
 * <ul>
 *   <li>分析已完成 → 用 {@code ir} 的结论（可能是 LLM 的）</li>
 *   <li>分析进行中（{@code ir} 行存在但结论列为 NULL）→ 回落到 {@code m}，
 *       界面不空白</li>
 *   <li>没有 {@code ir} 行（未分析 / 规则兜底 / 老数据）→ 回落到 {@code m}</li>
 * </ul>
 * <p>
 * 前端因此<b>零改动</b>：它读的还是 {@code mail.category} 这些字段名。
 * 这是"读取时消解"，不产生第二个真相源，所以不需要任何回写同步逻辑。
 * </p>
 *
 * <h3>为什么必须显式列出列名，不能用 {@code m.*}</h3>
 * <p>
 * {@code SELECT m.*, COALESCE(...) AS category} 会让结果集里出现两个
 * {@code category} 列，JDBC 驱动按列名取值时返回哪一个是不确定的 ——
 * 而且这种 bug 在本地数据上可能"恰好"总是取到想要的那个。
 * 枚举列名是唯一可靠的做法。
 * </p>
 * <p>
 * 代价是：<b>给 {@code mail} 表加列时必须同步更新 {@link #MAIL_COLUMNS}</b>，
 * 否则新列不会出现在查询结果里。这是本文件最容易踩的坑。
 * </p>
 */
@Mapper
public interface MailMapper extends BaseMapper<Mail> {

    /**
     * {@code mail} 表的投影列（已含四项结论的 COALESCE 覆盖）。
     * <p>
     * 接口里的 {@code String} 常量是编译期常量，因此可以直接拼进注解里的 SQL。
     * </p>
     */
    String MAIL_COLUMNS = "m.id, m.sender_id, m.sender_email, m.receiver_ids, m.cc_ids, "
            + "m.subject, m.body, m.send_time, m.status, "
            + "COALESCE(ir.priority, m.priority) AS priority, "
            + "COALESCE(ir.is_spam, m.is_spam) AS is_spam, "
            + "COALESCE(ir.summary, m.summary) AS summary, "
            + "COALESCE(ir.category, m.category) AS category, "
            + "m.direction, m.external_from, m.external_to, m.external_msg_id, "
            + "m.account_id, m.imap_uid, m.external_status, m.external_error";

    /**
     * 分析结果表的 JOIN 片段（按收件人）。
     * <p>
     * 收件箱/垃圾箱/搜索用这个：邮件是"发给我的"，我的 {@code mail_status} 行必然存在。
     * </p>
     */
    String JOIN_ANALYSIS_BY_STATUS = "LEFT JOIN mail_intelligence_result ir "
            + "ON ir.mail_id = m.id AND ir.user_id = ms.user_id ";

    /**
     * 分析结果表的 JOIN 片段（按当前用户）。
     * <p>
     * 发件箱用这个：发件人没有自己的 {@code mail_status} 行，
     * 只能用查询参数里的 {@code userId}，否则 JOIN 条件里的 {@code ms.user_id}
     * 为 NULL 会让整条 LEFT JOIN 永远匹配不上 —— 表现是发件箱里
     * 所有邮件的分类都退回规则值，且不报任何错。
     * </p>
     */
    String JOIN_ANALYSIS_BY_USER = "LEFT JOIN mail_intelligence_result ir "
            + "ON ir.mail_id = m.id AND ir.user_id = #{userId} ";

    // ==================== 收件箱 ====================

    /**
     * 查询收件箱（用户作为收件人或抄送人的邮件，且未被该用户删除）
     */
    @Select("SELECT " + MAIL_COLUMNS + ", ms.is_read FROM mail m "
            + "INNER JOIN mail_status ms ON m.id = ms.mail_id "
            + JOIN_ANALYSIS_BY_STATUS
            + "WHERE ms.user_id = #{userId} AND ms.is_deleted = 0 "
            + "AND m.status != 2 "
            + "ORDER BY m.send_time DESC")
    List<Mail> selectInbox(@Param("userId") Long userId);

    /**
     * 查询收件箱（分页版本）
     */
    @Select("SELECT " + MAIL_COLUMNS + ", ms.is_read FROM mail m "
            + "INNER JOIN mail_status ms ON m.id = ms.mail_id "
            + JOIN_ANALYSIS_BY_STATUS
            + "WHERE ms.user_id = #{userId} AND ms.is_deleted = 0 "
            + "AND m.status != 2 "
            + "ORDER BY m.send_time DESC")
    IPage<Mail> selectInboxPage(Page<Mail> page, @Param("userId") Long userId);

    // ==================== 发件箱 ====================

    /**
     * 查询已发送邮件（排除草稿，排除发件人已通过 mail_status 软删除的邮件）
     */
    @Select("SELECT " + MAIL_COLUMNS + ", ms.is_read FROM mail m "
            + "LEFT JOIN mail_status ms ON m.id = ms.mail_id AND ms.user_id = #{userId} "
            + JOIN_ANALYSIS_BY_USER
            + "WHERE m.sender_id = #{userId} AND m.status = 1 "
            + "AND (ms.id IS NULL OR ms.is_deleted = 0) "
            + "ORDER BY m.send_time DESC")
    List<Mail> selectSent(@Param("userId") Long userId);

    /**
     * 查询已发送邮件（分页版本）
     */
    @Select("SELECT " + MAIL_COLUMNS + ", ms.is_read FROM mail m "
            + "LEFT JOIN mail_status ms ON m.id = ms.mail_id AND ms.user_id = #{userId} "
            + JOIN_ANALYSIS_BY_USER
            + "WHERE m.sender_id = #{userId} AND m.status = 1 "
            + "AND (ms.id IS NULL OR ms.is_deleted = 0) "
            + "ORDER BY m.send_time DESC")
    IPage<Mail> selectSentPage(Page<Mail> page, @Param("userId") Long userId);

    // ==================== 垃圾箱 ====================

    /**
     * 查询垃圾箱（用户删除的邮件）
     */
    @Select("SELECT " + MAIL_COLUMNS + ", ms.is_read FROM mail m "
            + "INNER JOIN mail_status ms ON m.id = ms.mail_id "
            + JOIN_ANALYSIS_BY_STATUS
            + "WHERE ms.user_id = #{userId} AND ms.is_deleted = 1 "
            + "ORDER BY m.send_time DESC")
    List<Mail> selectTrash(@Param("userId") Long userId);

    /**
     * 查询垃圾箱（分页版本）
     */
    @Select("SELECT " + MAIL_COLUMNS + ", ms.is_read FROM mail m "
            + "INNER JOIN mail_status ms ON m.id = ms.mail_id "
            + JOIN_ANALYSIS_BY_STATUS
            + "WHERE ms.user_id = #{userId} AND ms.is_deleted = 1 "
            + "ORDER BY m.send_time DESC")
    IPage<Mail> selectTrashPage(Page<Mail> page, @Param("userId") Long userId);

    // ==================== 搜索 ====================

    /**
     * 全文搜索邮件主题和正文
     */
    @Select("SELECT " + MAIL_COLUMNS + ", ms.is_read FROM mail m "
            + "INNER JOIN mail_status ms ON m.id = ms.mail_id "
            + JOIN_ANALYSIS_BY_STATUS
            + "WHERE ms.user_id = #{userId} AND ms.is_deleted = 0 "
            + "AND (m.subject LIKE CONCAT('%', #{keyword}, '%') "
            + "OR m.body LIKE CONCAT('%', #{keyword}, '%')) "
            + "ORDER BY m.send_time DESC")
    List<Mail> searchMails(@Param("userId") Long userId, @Param("keyword") String keyword);

    /**
     * 全文搜索（分页版本）
     */
    @Select("SELECT " + MAIL_COLUMNS + ", ms.is_read FROM mail m "
            + "INNER JOIN mail_status ms ON m.id = ms.mail_id "
            + JOIN_ANALYSIS_BY_STATUS
            + "WHERE ms.user_id = #{userId} AND ms.is_deleted = 0 "
            + "AND (m.subject LIKE CONCAT('%', #{keyword}, '%') "
            + "OR m.body LIKE CONCAT('%', #{keyword}, '%')) "
            + "ORDER BY m.send_time DESC")
    IPage<Mail> searchMailsPage(Page<Mail> page, @Param("userId") Long userId, @Param("keyword") String keyword);

    // ==================== 详情 ====================

    /**
     * 查一封邮件的详情，<b>并校验它属于该用户</b>。
     *
     * <h4>为什么授权写在 SQL 里而不是服务层再查一次</h4>
     * <p>
     * 原来的实现是"按 id 查出来，再让调用方自己比对"，只要有一个调用点忘了比对，
     * 就是一个越权读（IDOR）。把判据并进 WHERE 之后，返回 {@code null} 就等价于
     * "不存在或无权访问"，调用方无法绕过 —— 这在结构上消除了整类问题，
     * 而不是靠每个调用点自觉。
     * </p>
     * <p>
     * 返回 null 时对外统一报"邮件不存在"，不区分"没这封邮件"与"不是你的邮件"：
     * 区分开会让接口变成一个"探测某封邮件是否存在"的工具。
     * </p>
     *
     * <h4>刻意不排除 {@code is_deleted}</h4>
     * <p>
     * 垃圾箱里的邮件也要能打开。排除已删除会让"从垃圾箱点进去"直接报错，
     * 而回收站存在的意义就是还能看。
     * </p>
     *
     * <h4>授权条件覆盖三种身份</h4>
     * <ul>
     *   <li>收件人/抄送人 —— 有自己的 {@code mail_status} 行（{@code ms.user_id} 非空）</li>
     *   <li>发件人 —— {@code m.sender_id} 匹配（发件箱、草稿）</li>
     * </ul>
     * 用 {@code LEFT JOIN} 而不是 {@code INNER JOIN}，正是为了让发件人这条路径
     * 在没有 {@code mail_status} 行时依然能命中。
     */
    @Select("SELECT " + MAIL_COLUMNS + ", ms.is_read FROM mail m "
            + "LEFT JOIN mail_status ms ON ms.mail_id = m.id AND ms.user_id = #{userId} "
            + "LEFT JOIN mail_intelligence_result ir "
            + "ON ir.mail_id = m.id AND ir.user_id = #{userId} "
            + "WHERE m.id = #{mailId} "
            + "AND (ms.user_id IS NOT NULL OR m.sender_id = #{userId}) "
            + "LIMIT 1")
    Mail selectDetailForUser(@Param("mailId") Long mailId, @Param("userId") Long userId);

    // ==================== 写入（规则兜底路径用） ====================

    /**
     * 更新邮件优先级评分
     */
    @Update("UPDATE mail SET priority = #{priority} WHERE id = #{id}")
    int updatePriority(@Param("id") Long id, @Param("priority") int priority);

    /**
     * 标记垃圾邮件
     */
    @Update("UPDATE mail SET is_spam = #{isSpam} WHERE id = #{id}")
    int markSpam(@Param("id") Long id, @Param("isSpam") int isSpam);

    /**
     * 更新摘要
     */
    @Update("UPDATE mail SET summary = #{summary} WHERE id = #{id}")
    int updateSummary(@Param("id") Long id, @Param("summary") String summary);

    /**
     * 更新分类
     */
    @Update("UPDATE mail SET category = #{category} WHERE id = #{id}")
    int updateCategory(@Param("id") Long id, @Param("category") String category);

    /**
     * 按 RFC5322 Message-ID 判断邮件是否已入库（收信去重的第一道闸）。
     * <p>
     * 只是省一次正文解析与附件下载的开销，<b>不是</b>并发安全的判据 ——
     * 真正的保障是 {@code uk_external_msg_id} 唯一键，插入时冲突会抛
     * {@code DuplicateKeyException}，由收信流程捕获。
     * </p>
     */
    @Select("SELECT COUNT(*) FROM mail WHERE external_msg_id = #{messageId}")
    int countByExternalMsgId(@Param("messageId") String messageId);
}

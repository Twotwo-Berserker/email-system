package com.mailsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mailsystem.entity.Mail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 邮件 Mapper
 */
@Mapper
public interface MailMapper extends BaseMapper<Mail> {

    /**
     * 查询收件箱（用户作为收件人或抄送人的邮件，且未被该用户删除）
     */
    @Select("SELECT m.* FROM mail m "
            + "INNER JOIN mail_status ms ON m.id = ms.mail_id "
            + "WHERE ms.user_id = #{userId} AND ms.is_deleted = 0 "
            + "AND m.status != 2 "
            + "ORDER BY m.send_time DESC")
    List<Mail> selectInbox(@Param("userId") Long userId);

    /**
     * 查询已发送邮件（排除草稿，排除发件人已通过 mail_status 软删除的邮件）
     */
    @Select("SELECT m.* FROM mail m "
            + "LEFT JOIN mail_status ms ON m.id = ms.mail_id AND ms.user_id = #{userId} "
            + "WHERE m.sender_id = #{userId} AND m.status = 1 "
            + "AND (ms.id IS NULL OR ms.is_deleted = 0) "
            + "ORDER BY m.send_time DESC")
    List<Mail> selectSent(@Param("userId") Long userId);

    /**
     * 查询垃圾箱（用户删除的邮件）
     */
    @Select("SELECT m.* FROM mail m "
            + "INNER JOIN mail_status ms ON m.id = ms.mail_id "
            + "WHERE ms.user_id = #{userId} AND ms.is_deleted = 1 "
            + "ORDER BY m.send_time DESC")
    List<Mail> selectTrash(@Param("userId") Long userId);

    /**
     * 全文搜索邮件主题和正文
     */
    @Select("SELECT m.* FROM mail m "
            + "INNER JOIN mail_status ms ON m.id = ms.mail_id "
            + "WHERE ms.user_id = #{userId} AND ms.is_deleted = 0 "
            + "AND (m.subject LIKE CONCAT('%', #{keyword}, '%') "
            + "OR m.body LIKE CONCAT('%', #{keyword}, '%')) "
            + "ORDER BY m.send_time DESC")
    List<Mail> searchMails(@Param("userId") Long userId, @Param("keyword") String keyword);

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
}

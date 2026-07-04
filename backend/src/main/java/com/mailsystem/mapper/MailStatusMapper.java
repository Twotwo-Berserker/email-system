package com.mailsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mailsystem.entity.MailStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import java.util.List;

/**
 * 邮件状态 Mapper
 */
@Mapper
public interface MailStatusMapper extends BaseMapper<MailStatus> {

    /**
     * 根据邮件ID和用户ID查询状态
     */
    @Select("SELECT * FROM mail_status WHERE mail_id = #{mailId} AND user_id = #{userId}")
    MailStatus selectByMailIdAndUserId(@Param("mailId") Long mailId, @Param("userId") Long userId);

    /**
     * 标记已读
     */
    @Update("UPDATE mail_status SET is_read = 1, read_time = NOW(), updated_time = NOW() WHERE mail_id = #{mailId} AND user_id = #{userId}")
    int markAsRead(@Param("mailId") Long mailId, @Param("userId") Long userId);

    /**
     * 标记未读（清除阅读时间和已读状态）
     */
    @Update("UPDATE mail_status SET is_read = 0, read_time = NULL, updated_time = NOW() WHERE mail_id = #{mailId} AND user_id = #{userId}")
    int markAsUnread(@Param("mailId") Long mailId, @Param("userId") Long userId);

    /**
     * 插入邮件状态记录（用于初始创建或 toggle 补建）
     */
    @org.apache.ibatis.annotations.Insert("INSERT INTO mail_status (mail_id, user_id, is_read, is_deleted, sync_status, read_time) VALUES (#{mailId}, #{userId}, #{isRead}, #{isDeleted}, #{syncStatus}, #{readTime})")
    int insertStatus(@Param("mailId") Long mailId, @Param("userId") Long userId,
                     @Param("isRead") Integer isRead, @Param("isDeleted") Integer isDeleted,
                     @Param("syncStatus") Integer syncStatus,
                     @Param("readTime") java.time.LocalDateTime readTime);

    /**
     * 软删除（移至垃圾箱）
     */
    @Update("UPDATE mail_status SET is_deleted = 1, updated_time = NOW() WHERE mail_id = #{mailId} AND user_id = #{userId}")
    int softDelete(@Param("mailId") Long mailId, @Param("userId") Long userId);

    /**
     * 恢复删除
     */
    @Update("UPDATE mail_status SET is_deleted = 0, updated_time = NOW() WHERE mail_id = #{mailId} AND user_id = #{userId}")
    int restoreDelete(@Param("mailId") Long mailId, @Param("userId") Long userId);

    /**
     * 查询未读邮件数量
     */
    @Select("SELECT COUNT(*) FROM mail_status WHERE user_id = #{userId} AND is_read = 0 AND is_deleted = 0")
    int countUnread(@Param("userId") Long userId);

    /**
     * 永久删除邮件状态记录（彻底从垃圾箱删除）
     */
    @Delete("DELETE FROM mail_status WHERE mail_id = #{mailId} AND user_id = #{userId}")
    int deleteByMailIdAndUserId(@Param("mailId") Long mailId, @Param("userId") Long userId);

    /**
     * 查询自某时间以来的状态变更（增量同步用）
     */
    @Select("SELECT * FROM mail_status WHERE user_id = #{userId} AND updated_time > #{since} ORDER BY updated_time ASC")
    List<MailStatus> selectChangesSince(@Param("userId") Long userId, @Param("since") String since);
}

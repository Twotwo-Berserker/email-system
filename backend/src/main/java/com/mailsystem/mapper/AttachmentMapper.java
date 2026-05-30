package com.mailsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mailsystem.entity.Attachment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 附件 Mapper
 */
@Mapper
public interface AttachmentMapper extends BaseMapper<Attachment> {

    /**
     * 根据邮件ID查询附件列表
     */
    @Select("SELECT * FROM attachment WHERE mail_id = #{mailId}")
    List<Attachment> selectByMailId(@Param("mailId") Long mailId);

    /**
     * 解绑邮件附件（将 mail_id 设为 NULL）
     */
    @Update("UPDATE attachment SET mail_id = NULL WHERE mail_id = #{mailId}")
    int unbindByMailId(@Param("mailId") Long mailId);
}

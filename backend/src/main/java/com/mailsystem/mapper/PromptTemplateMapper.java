package com.mailsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mailsystem.entity.PromptTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Prompt 模板 Mapper
 */
@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplate> {

    /**
     * 取当前启用的模板。
     * <p>
     * 按 {@code id DESC} 排序再取第一条：万一因为并发或人工改库出现了
     * 两行同时 {@code enabled = 1}，至少是<b>确定性地</b>选最新那行，
     * 而不是随 MySQL 的返回顺序漂移。服务层会在这行不止一条时打告警。
     * </p>
     */
    @Select("SELECT * FROM prompt_template WHERE name = #{name} AND enabled = 1 "
            + "ORDER BY id DESC LIMIT 1")
    PromptTemplate selectActive(@Param("name") String name);

    /**
     * 同名的启用行数，用于检测"多行同时启用"这一意外状态
     */
    @Select("SELECT COUNT(*) FROM prompt_template WHERE name = #{name} AND enabled = 1")
    int countActive(@Param("name") String name);

    @Select("SELECT * FROM prompt_template WHERE name = #{name} ORDER BY id DESC")
    List<PromptTemplate> selectByName(@Param("name") String name);

    @Select("SELECT * FROM prompt_template ORDER BY name ASC, id DESC")
    List<PromptTemplate> selectAllOrdered();

    /**
     * 停用某模板下所有版本。
     * <p>
     * 启用操作的第一步。表上只有 {@code uk_name_version(name, version)}，
     * <b>没有</b>任何约束能阻止两行同时启用，所以"启用某版本"必须由
     * 服务层用「先全停、再启用」的两步写在一个事务里来保证互斥。
     * </p>
     */
    @Update("UPDATE prompt_template SET enabled = 0 WHERE name = #{name}")
    int disableAll(@Param("name") String name);

    @Update("UPDATE prompt_template SET enabled = #{enabled} WHERE id = #{id}")
    int setEnabled(@Param("id") Long id, @Param("enabled") int enabled);
}

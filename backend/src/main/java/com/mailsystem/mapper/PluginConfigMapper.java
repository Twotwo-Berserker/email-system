package com.mailsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mailsystem.entity.PluginConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 插件配置 Mapper
 */
@Mapper
public interface PluginConfigMapper extends BaseMapper<PluginConfig> {

    /**
     * 根据插件名查询配置
     */
    @Select("SELECT * FROM plugin_config WHERE plugin_name = #{pluginName}")
    PluginConfig selectByPluginName(@Param("pluginName") String pluginName);

    /**
     * 更新插件启用状态
     */
    @Update("UPDATE plugin_config SET enabled = #{enabled} WHERE plugin_name = #{pluginName}")
    int updateEnabled(@Param("pluginName") String pluginName, @Param("enabled") int enabled);
}

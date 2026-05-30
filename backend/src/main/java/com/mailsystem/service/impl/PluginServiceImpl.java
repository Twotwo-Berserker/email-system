package com.mailsystem.service.impl;

import com.mailsystem.entity.PluginConfig;
import com.mailsystem.mapper.PluginConfigMapper;
import com.mailsystem.service.PluginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 插件服务实现
 */
@Service
public class PluginServiceImpl implements PluginService {

    @Autowired
    private PluginConfigMapper pluginConfigMapper;

    @Override
    public List<PluginConfig> listPlugins() {
        return pluginConfigMapper.selectList(null);
    }

    @Override
    public PluginConfig togglePlugin(String pluginName, boolean enabled) {
        PluginConfig config = pluginConfigMapper.selectByPluginName(pluginName);
        if (config == null) {
            throw new RuntimeException("插件不存在: " + pluginName);
        }
        config.setEnabled(enabled ? 1 : 0);
        pluginConfigMapper.updateEnabled(pluginName, enabled ? 1 : 0);
        return pluginConfigMapper.selectByPluginName(pluginName);
    }

    @Override
    public boolean isPluginEnabled(String pluginName) {
        PluginConfig config = pluginConfigMapper.selectByPluginName(pluginName);
        return config != null && config.getEnabled() == 1;
    }
}

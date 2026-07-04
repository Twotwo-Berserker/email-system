package com.mailsystem.plugin.dynamic;

import com.mailsystem.plugin.PluginInterface;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态插件注册中心
 * 管理运行时通过JAR加载的外部插件
 */
@Component
public class DynamicPluginRegistry {

    /** 已加载的动态插件: name -> PluginInterface */
    private final Map<String, PluginInterface> plugins = new ConcurrentHashMap<>();

    /**
     * 注册插件
     */
    public void register(String name, PluginInterface plugin) {
        plugins.put(name, plugin);
        System.out.println("[DynamicPlugin] 已注册插件: " + name);
    }

    /**
     * 注销插件
     */
    public void unregister(String name) {
        PluginInterface removed = plugins.remove(name);
        if (removed != null) {
            System.out.println("[DynamicPlugin] 已注销插件: " + name);
        }
    }

    /**
     * 获取所有已加载的动态插件
     */
    public List<PluginInterface> getAllPlugins() {
        return new ArrayList<>(plugins.values());
    }

    /**
     * 获取动态插件名称列表
     */
    public List<String> getPluginNames() {
        return new ArrayList<>(plugins.keySet());
    }

    /**
     * 获取指定插件
     */
    public PluginInterface getPlugin(String name) {
        return plugins.get(name);
    }

    /**
     * 插件数量
     */
    public int getCount() {
        return plugins.size();
    }

    /**
     * 清空所有动态插件
     */
    public void clear() {
        plugins.clear();
    }
}

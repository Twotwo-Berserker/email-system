package com.mailsystem.plugin.dynamic;

import com.mailsystem.plugin.PluginInterface;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 动态插件加载器
 * 扫描 ./plugins/ 目录，用 URLClassLoader 加载外部JAR插件
 */
@Component
public class DynamicPluginLoader {

    @Autowired
    private DynamicPluginRegistry registry;

    /** 插件存放目录 */
    private static final String PLUGIN_DIR = "./plugins/";

    /**
     * 扫描并加载 plugins 目录下的所有JAR插件
     * @return 加载成功的插件数量
     */
    public int scanAndLoad() {
        File dir = new File(PLUGIN_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
            System.out.println("[DynamicPlugin] 插件目录不存在，已创建: " + PLUGIN_DIR);
            return 0;
        }

        File[] jarFiles = dir.listFiles((d, name) -> name.endsWith(".jar"));
        if (jarFiles == null || jarFiles.length == 0) {
            System.out.println("[DynamicPlugin] 插件目录为空，没有找到JAR文件");
            return 0;
        }

        int loaded = 0;
        for (File jarFile : jarFiles) {
            try {
                PluginInterface plugin = loadJar(jarFile);
                if (plugin != null) {
                    String name = plugin.getName();
                    registry.register(name, plugin);
                    loaded++;
                    System.out.println("[DynamicPlugin] 成功加载插件: " + jarFile.getName() + " -> " + name);
                }
            } catch (Exception e) {
                System.err.println("[DynamicPlugin] 加载JAR失败: " + jarFile.getName() + " - " + e.getMessage());
            }
        }
        return loaded;
    }

    /**
     * 从单个JAR文件加载插件
     */
    public PluginInterface loadJar(File jarFile) throws Exception {
        try (JarFile jar = new JarFile(jarFile)) {
            // 扫描JAR中的类，找到实现了 PluginInterface 的类
            List<String> pluginClasses = new ArrayList<>();
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class") && !name.contains("$")) {
                    // 转换为类名
                    String className = name.replace("/", ".").replace(".class", "");
                    pluginClasses.add(className);
                }
            }

            // 用独立的 URLClassLoader 加载JAR
            URL jarUrl = jarFile.toURI().toURL();
            try (URLClassLoader classLoader = new URLClassLoader(
                    new URL[]{jarUrl},
                    PluginInterface.class.getClassLoader())) {

                for (String className : pluginClasses) {
                    try {
                        Class<?> clazz = classLoader.loadClass(className);
                        // 检查是否实现了 PluginInterface
                        if (PluginInterface.class.isAssignableFrom(clazz) && !clazz.isInterface()) {
                            PluginInterface plugin = (PluginInterface) clazz.getDeclaredConstructor().newInstance();
                            return plugin; // 返回第一个找到的插件实现
                        }
                    } catch (Exception e) {
                        // 跳过无法加载的类
                    }
                }
            }
        }
        return null;
    }

    /**
     * 获取所有已加载的插件名称列表
     */
    public List<String> listLoadedPlugins() {
        return registry.getPluginNames();
    }

    /**
     * 卸载指定插件
     */
    public boolean unloadPlugin(String name) {
        registry.unregister(name);
        return true;
    }
}

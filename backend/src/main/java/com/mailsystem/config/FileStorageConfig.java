package com.mailsystem.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件存储配置 — 启动时创建上传目录
 */
@Configuration
public class FileStorageConfig implements CommandLineRunner {

    @Value("${file.upload.path}")
    private String uploadPath;

    @Override
    public void run(String... args) throws Exception {
        Path path = Paths.get(uploadPath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
            System.out.println("附件存储目录已创建: " + path.toAbsolutePath());
        }
    }
}

package com.mailsystem.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 对象存储配置类
 * 用途: 大附件上传/下载，替代本地磁盘存储
 */
@Configuration
public class MinioConfig {

    /** MinIO 服务端点（Docker 内部: http://minio:9000，本地开发: http://localhost:9000） */
    @Value("${minio.endpoint:http://localhost:9000}")
    private String endpoint;

    /** MinIO 访问密钥（对应 MINIO_ROOT_USER） */
    @Value("${minio.access-key:minioadmin}")
    private String accessKey;

    /** MinIO 秘密密钥（对应 MINIO_ROOT_PASSWORD） */
    @Value("${minio.secret-key:minioadmin123}")
    private String secretKey;

    /**
     * MinIO 客户端 Bean
     * 单例模式，线程安全，支持连接池复用
     */
    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}

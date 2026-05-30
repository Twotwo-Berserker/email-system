package com.mailsystem.service;

import io.minio.*;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 对象存储服务
 * 用途: 大附件的上传、下载、删除，替代本地磁盘存储
 */
@Service
public class MinioStorageService {

    @Autowired
    private MinioClient minioClient;

    /** 存储桶名称 */
    @Value("${minio.bucket-name:mail-attachments}")
    private String bucketName;

    /** 是否启用 MinIO（当 MinIO 可用时自动使用，否则回退到本地存储） */
    @Value("${minio.enabled:true}")
    private boolean enabled;

    /**
     * 上传文件到 MinIO
     * @param file 上传的文件
     * @return MinIO 中的对象路径（bucket/objectName）
     */
    public String uploadFile(MultipartFile file) throws IOException {
        if (!enabled) {
            throw new IOException("MinIO storage is disabled");
        }

        try {
            // 确保存储桶存在
            ensureBucketExists();

            // 生成唯一对象名（保持原始扩展名）
            String originalName = file.getOriginalFilename();
            String extension = "";
            if (originalName != null && originalName.contains(".")) {
                extension = originalName.substring(originalName.lastIndexOf("."));
            }
            String objectName = UUID.randomUUID().toString().replace("-", "") + extension;

            // 上传到 MinIO
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build()
            );

            // 返回完整的对象标识（bucket/objectName）
            return bucketName + "/" + objectName;
        } catch (Exception e) {
            throw new IOException("MinIO upload failed: " + e.getMessage(), e);
        }
    }

    /**
     * 从 MinIO 下载文件内容
     * @param objectPath 对象路径（格式: bucket/objectName 或仅 objectName）
     * @return 文件字节数组
     */
    public byte[] downloadFile(String objectPath) throws IOException {
        if (!enabled) {
            throw new IOException("MinIO storage is disabled");
        }

        String objectName = extractObjectName(objectPath);

        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build())) {
            return stream.readAllBytes();
        } catch (Exception e) {
            throw new IOException("MinIO download failed: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 MinIO 文件输入流（用于大文件流式下载）
     * @param objectPath 对象路径
     * @return 文件输入流
     */
    public InputStream getFileStream(String objectPath) throws IOException {
        if (!enabled) {
            throw new IOException("MinIO storage is disabled");
        }

        String objectName = extractObjectName(objectPath);

        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build());
        } catch (Exception e) {
            throw new IOException("MinIO stream failed: " + e.getMessage(), e);
        }
    }

    /**
     * 生成文件的临时下载链接（签名 URL）
     * @param objectPath 对象路径
     * @param expirySeconds 过期时间（秒）
     * @return 预签名 URL
     */
    public String getPresignedUrl(String objectPath, int expirySeconds) throws IOException {
        if (!enabled) {
            throw new IOException("MinIO storage is disabled");
        }

        String objectName = extractObjectName(objectPath);

        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .method(Method.GET)
                            .expiry(expirySeconds, TimeUnit.SECONDS)
                            .build());
        } catch (Exception e) {
            throw new IOException("MinIO presigned URL failed: " + e.getMessage(), e);
        }
    }

    /**
     * 删除 MinIO 中的文件
     * @param objectPath 对象路径
     */
    public boolean deleteFile(String objectPath) {
        if (!enabled) {
            return false;
        }

        String objectName = extractObjectName(objectPath);

        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build());
            return true;
        } catch (Exception e) {
            // 删除失败不抛异常，记录日志
            System.err.println("MinIO delete failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 检查 MinIO 是否可用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 确保存储桶存在，不存在则创建
     */
    private void ensureBucketExists() throws Exception {
        boolean found = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucketName).build());
        if (!found) {
            minioClient.makeBucket(
                    MakeBucketArgs.builder().bucket(bucketName).build());
            System.out.println("MinIO bucket created: " + bucketName);
        }
    }

    /**
     * 从完整路径中提取对象名（去掉 bucket/ 前缀）
     */
    private String extractObjectName(String objectPath) {
        if (objectPath.contains("/")) {
            return objectPath.substring(objectPath.indexOf("/") + 1);
        }
        return objectPath;
    }
}

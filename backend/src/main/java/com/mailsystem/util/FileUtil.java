package com.mailsystem.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 文件工具类 — 附件存储与读取
 */
@Component
public class FileUtil {

    @Value("${file.upload.path}")
    private String uploadPath;

    /**
     * 保存上传文件到本地磁盘
     * @return 存储后的文件路径
     */
    public String storeFile(MultipartFile file) throws IOException {
        // 确保目录存在
        Path dir = Paths.get(uploadPath);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        // 生成唯一文件名
        String originalName = file.getOriginalFilename();
        String extension = "";
        if (originalName != null && originalName.contains(".")) {
            extension = originalName.substring(originalName.lastIndexOf("."));
        }
        String storedName = UUID.randomUUID().toString().replace("-", "") + extension;
        Path targetPath = dir.resolve(storedName);

        // 写入文件
        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

        return targetPath.toString();
    }

    /**
     * 读取文件
     * @param filePath 文件路径
     * @return 文件字节数组
     */
    public byte[] readFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new IOException("文件不存在: " + filePath);
        }
        return Files.readAllBytes(path);
    }

    /**
     * 删除文件
     */
    public boolean deleteFile(String filePath) {
        try {
            return Files.deleteIfExists(Paths.get(filePath));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 获取上传目录路径
     */
    public String getUploadPath() {
        return uploadPath;
    }
}

package com.mailsystem.service.impl;

import com.mailsystem.entity.Attachment;
import com.mailsystem.mapper.AttachmentMapper;
import com.mailsystem.service.AttachmentService;
import com.mailsystem.service.MinioStorageService;
import com.mailsystem.util.FileUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * 附件服务实现
 * 存储策略: MinIO 优先（生产环境） > 本地磁盘（开发环境）
 * 通过 minioStorageService.isEnabled() 自动判断
 */
@Service
public class AttachmentServiceImpl implements AttachmentService {

    @Autowired
    private AttachmentMapper attachmentMapper;

    @Autowired
    private FileUtil fileUtil;

    @Autowired
    private MinioStorageService minioStorageService;

    @Override
    public Attachment uploadAttachment(MultipartFile file) {
        try {
            String filePath;

            // 优先使用 MinIO 对象存储（生产环境）
            if (minioStorageService.isEnabled()) {
                filePath = minioStorageService.uploadFile(file);
            } else {
                // 回退到本地磁盘存储（开发环境）
                filePath = fileUtil.storeFile(file);
            }

            // 保存附件记录到数据库
            Attachment attachment = new Attachment();
            attachment.setFileName(file.getOriginalFilename());
            attachment.setFilePath(filePath);
            attachment.setFileSize(file.getSize());
            attachment.setContentType(file.getContentType());
            attachment.setUploadTime(LocalDateTime.now());
            // mailId 暂不设置，发送邮件时再绑定
            attachmentMapper.insert(attachment);

            return attachment;
        } catch (IOException e) {
            throw new RuntimeException("附件上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Attachment downloadAttachment(Long attachmentId) {
        Attachment attachment = attachmentMapper.selectById(attachmentId);
        if (attachment == null) {
            throw new RuntimeException("附件不存在");
        }
        return attachment;
    }

    @Override
    public byte[] getAttachmentData(Long attachmentId) {
        Attachment attachment = attachmentMapper.selectById(attachmentId);
        if (attachment == null) {
            throw new RuntimeException("附件不存在");
        }
        try {
            // 根据文件路径判断存储方式
            // MinIO 路径格式: bucket/objectName
            // 本地路径格式: ./uploads/attachments/xxx
            if (minioStorageService.isEnabled() && !attachment.getFilePath().startsWith("./")) {
                return minioStorageService.downloadFile(attachment.getFilePath());
            } else {
                return fileUtil.readFile(attachment.getFilePath());
            }
        } catch (IOException e) {
            throw new RuntimeException("附件文件读取失败: " + e.getMessage(), e);
        }
    }
}

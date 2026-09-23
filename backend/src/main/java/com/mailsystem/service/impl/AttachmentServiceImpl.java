package com.mailsystem.service.impl;

import com.mailsystem.entity.Attachment;
import com.mailsystem.mapper.AttachmentMapper;
import com.mailsystem.mapper.MailMapper;
import com.mailsystem.service.AttachmentService;
import com.mailsystem.service.MinioStorageService;
import com.mailsystem.util.FileUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

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
    private MailMapper mailMapper;

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
    public List<Attachment> attachmentList(Long mailId) {
        if (mailId == null) {
            return Collections.emptyList();
        }
        return attachmentMapper.selectByMailId(mailId);
    }

    @Override
    public Attachment storeIncoming(String fileName, String contentType, byte[] data, Long mailId) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            String filePath = minioStorageService.isEnabled()
                    ? minioStorageService.uploadBytes(data, fileName, contentType)
                    : fileUtil.storeBytes(data, fileName);

            Attachment attachment = new Attachment();
            attachment.setFileName(fileName == null || fileName.isEmpty() ? "attachment" : fileName);
            attachment.setFilePath(filePath);
            attachment.setFileSize((long) data.length);
            attachment.setContentType(contentType);
            attachment.setUploadTime(LocalDateTime.now());
            attachment.setMailId(mailId);
            attachmentMapper.insert(attachment);
            return attachment;
        } catch (IOException e) {
            // 单个附件存不下不该让整封邮件收不进来，由调用方决定是跳过还是中断
            throw new RuntimeException("外部邮件附件保存失败: " + e.getMessage(), e);
        }
    }

    /**
     * 归属校验。
     * <p>
     * 复用 {@code MailMapper.selectDetailForUser} —— 它已经把"收件人或发件人"
     * 这条规则写在了 SQL 里（列表/详情接口用的就是它）。在这里重写一遍判断
     * 意味着两处规则将来可能不一致，而权限判断的两处不一致就是漏洞。
     * </p>
     */
    @Override
    public Attachment requireReadable(Long attachmentId, Long userId) {
        Attachment attachment = requireAttachment(attachmentId);

        if (attachment.getMailId() == null) {
            // 已上传但尚未绑定邮件的附件（写信页上传完成、还没点发送）。
            // attachment 表没有"上传者"列，无法判断它属于谁，因此一律拒绝 ——
            // 它只在"上传完成到发送之间"这个短暂窗口内存在，正常流程不下载它。
            // 这类附件被枚举下载，泄露的是"别人写了但没发出去的东西"
            throw new RuntimeException("附件不存在");
        }
        if (userId == null || mailMapper.selectDetailForUser(attachment.getMailId(), userId) == null) {
            throw new RuntimeException("附件不存在");
        }
        return attachment;
    }

    private Attachment requireAttachment(Long attachmentId) {
        if (attachmentId == null) {
            throw new RuntimeException("附件不存在");
        }
        Attachment attachment = attachmentMapper.selectById(attachmentId);
        if (attachment == null) {
            throw new RuntimeException("附件不存在");
        }
        return attachment;
    }

    @Override
    public byte[] getAttachmentData(Long attachmentId) {
        Attachment attachment = requireAttachment(attachmentId);
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

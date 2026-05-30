package com.mailsystem.service;

import com.mailsystem.entity.Attachment;
import org.springframework.web.multipart.MultipartFile;

/**
 * 附件服务接口
 */
public interface AttachmentService {

    /**
     * 上传附件
     */
    Attachment uploadAttachment(MultipartFile file);

    /**
     * 下载附件
     */
    Attachment downloadAttachment(Long attachmentId);

    /**
     * 获取附件存储的字节数据
     */
    byte[] getAttachmentData(Long attachmentId);
}

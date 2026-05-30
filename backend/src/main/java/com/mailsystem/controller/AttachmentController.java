package com.mailsystem.controller;

import com.mailsystem.dto.ApiResponse;
import com.mailsystem.entity.Attachment;
import com.mailsystem.service.AttachmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;

/**
 * 附件控制器 — /attachment/*
 */
@RestController
@RequestMapping("/attachment")
public class AttachmentController {

    @Autowired
    private AttachmentService attachmentService;

    /**
     * 上传附件
     * POST /attachment/upload
     */
    @PostMapping("/upload")
    public ApiResponse<Attachment> upload(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ApiResponse.error("请选择文件");
            }
            Attachment attachment = attachmentService.uploadAttachment(file);
            return ApiResponse.ok("上传成功", attachment);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 下载附件
     * GET /attachment/download/{id}
     */
    @GetMapping("/download/{id}")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        try {
            Attachment attachment = attachmentService.downloadAttachment(id);
            byte[] data = attachmentService.getAttachmentData(id);

            String encodedFileName = URLEncoder.encode(attachment.getFileName(), "UTF-8")
                    .replace("+", "%20");

            String contentType = attachment.getContentType() != null
                    ? attachment.getContentType()
                    : "application/octet-stream";

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + encodedFileName + "\"")
                    .body(data);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 预览附件（内联显示）
     * GET /attachment/preview/{id}
     */
    @GetMapping("/preview/{id}")
    public ResponseEntity<byte[]> preview(@PathVariable Long id) {
        try {
            Attachment attachment = attachmentService.downloadAttachment(id);
            byte[] data = attachmentService.getAttachmentData(id);

            String contentType = attachment.getContentType() != null
                    ? attachment.getContentType()
                    : "application/octet-stream";
            MediaType mediaType = MediaType.parseMediaType(contentType);

            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .body(data);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}

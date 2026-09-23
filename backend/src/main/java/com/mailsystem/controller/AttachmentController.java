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

import javax.servlet.http.HttpServletRequest;
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
     * <p>
     * 先做归属校验：只有附件所属邮件的收件人或发件人能下载。原先这里只用
     * 附件 ID 取数据，任何登录用户枚举 ID 就能下载别人的附件。
     * </p>
     */
    @GetMapping("/download/{id}")
    public ResponseEntity<byte[]> download(HttpServletRequest request, @PathVariable Long id) {
        try {
            Attachment attachment = attachmentService.requireReadable(id, userId(request));
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
     * <p>
     * 与下载同一套归属校验。预览走 {@code Content-Disposition: inline}，
     * 漏校验比下载更危险 —— 浏览器会直接把内容渲染出来。
     * </p>
     */
    @GetMapping("/preview/{id}")
    public ResponseEntity<byte[]> preview(HttpServletRequest request, @PathVariable Long id) {
        try {
            Attachment attachment = attachmentService.requireReadable(id, userId(request));
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

    /**
     * 从请求中取当前用户 ID（由 {@code JwtInterceptor} 写入）。
     * <p>
     * 返回 null 时 {@code requireReadable} 会拒绝 —— 能走到这里的请求都过了
     * 拦截器，理论上取得到，但"取不到就当没登录"比"取不到就放行"安全。
     * </p>
     */
    private Long userId(HttpServletRequest request) {
        Object uid = request.getAttribute("userId");
        if (uid instanceof Long) {
            return (Long) uid;
        }
        if (uid instanceof Integer) {
            return ((Integer) uid).longValue();
        }
        return uid == null ? null : Long.valueOf(uid.toString());
    }
}

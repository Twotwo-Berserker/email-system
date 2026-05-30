package com.mailsystem.controller;

import com.mailsystem.dto.ApiResponse;
import com.mailsystem.dto.SendMailRequest;
import com.mailsystem.entity.Attachment;
import com.mailsystem.entity.Mail;
import com.mailsystem.service.MailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.List;

/**
 * 邮件控制器 — /mail/*
 */
@RestController
@RequestMapping("/mail")
public class MailController {

    @Autowired
    private MailService mailService;

    /**
     * 发送邮件
     * POST /mail/send
     */
    @PostMapping("/send")
    public ApiResponse<Mail> send(HttpServletRequest request, @Valid @RequestBody SendMailRequest req) {
        try {
            Long userId = (Long) request.getAttribute("userId");
            Mail mail = mailService.sendMail(userId, req.getReceiverEmails(), req.getCcEmails(),
                    req.getSubject(), req.getBody(), req.getAttachmentIds());
            return ApiResponse.ok("发送成功", mail);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 拉取收件箱（新邮件拉取）
     * GET /mail/receive
     */
    @GetMapping("/receive")
    public ApiResponse<List<Mail>> receive(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        List<Mail> mails = mailService.receiveMails(userId);
        return ApiResponse.ok(mails);
    }

    /**
     * 邮件列表（按类型）
     * GET /mail/list?type=1
     * type: 1=收件箱, 2=已发送, 3=垃圾箱, 4=草稿
     */
    @GetMapping("/list")
    public ApiResponse<List<Mail>> list(HttpServletRequest request,
                                        @RequestParam(defaultValue = "1") Integer type) {
        Long userId = (Long) request.getAttribute("userId");
        List<Mail> mails = mailService.listMails(userId, type);
        return ApiResponse.ok(mails);
    }

    /**
     * 邮件详情
     * GET /mail/detail/{id}
     */
    @GetMapping("/detail/{id}")
    public ApiResponse<Mail> detail(HttpServletRequest request, @PathVariable Long id) {
        try {
            Long userId = (Long) request.getAttribute("userId");
            Mail mail = mailService.getMailDetail(id, userId);
            // 自动标记已读
            mailService.markAsRead(id, userId);

            // 将附件和状态信息合并到返回
            return ApiResponse.ok(mail);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取邮件附件列表
     * GET /mail/detail/{id}/attachments
     */
    @GetMapping("/detail/{id}/attachments")
    public ApiResponse<List<Attachment>> attachments(@PathVariable Long id) {
        List<Attachment> attachments = mailService.getAttachments(id);
        return ApiResponse.ok(attachments);
    }

    /**
     * 标记已读
     * PUT /mail/read/{id}
     */
    @PutMapping("/read/{id}")
    public ApiResponse<Void> read(HttpServletRequest request, @PathVariable Long id) {
        Long userId = (Long) request.getAttribute("userId");
        mailService.markAsRead(id, userId);
        return ApiResponse.ok("已标记为已读", null);
    }

    /**
     * 删除邮件（软删除）
     * DELETE /mail/delete/{id}
     */
    @DeleteMapping("/delete/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        Long userId = (Long) request.getAttribute("userId");
        try {
            mailService.deleteMail(id, userId);
            return ApiResponse.ok("已删除", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 搜索邮件
     * GET /mail/search?keyword=xxx
     */
    @GetMapping("/search")
    public ApiResponse<List<Mail>> search(HttpServletRequest request,
                                          @RequestParam String keyword) {
        Long userId = (Long) request.getAttribute("userId");
        List<Mail> mails = mailService.searchMails(userId, keyword);
        return ApiResponse.ok(mails);
    }

    /**
     * 获取未读邮件数量
     * GET /mail/unread-count
     */
    @GetMapping("/unread-count")
    public ApiResponse<Integer> unreadCount(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        int count = mailService.getUnreadCount(userId);
        return ApiResponse.ok(count);
    }

    /**
     * 从垃圾箱恢复邮件
     * PUT /mail/restore/{id}
     */
    @PutMapping("/restore/{id}")
    public ApiResponse<Void> restore(HttpServletRequest request, @PathVariable Long id) {
        Long userId = (Long) request.getAttribute("userId");
        try {
            mailService.restoreMail(id, userId);
            return ApiResponse.ok("已恢复", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 永久删除邮件（从垃圾箱彻底删除）
     * DELETE /mail/permanent/{id}
     */
    @DeleteMapping("/permanent/{id}")
    public ApiResponse<Void> permanentDelete(HttpServletRequest request, @PathVariable Long id) {
        Long userId = (Long) request.getAttribute("userId");
        try {
            mailService.permanentDeleteMail(id, userId);
            return ApiResponse.ok("已彻底删除", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 清空垃圾箱
     * PUT /mail/trash/empty
     */
    @PutMapping("/trash/empty")
    public ApiResponse<Void> emptyTrash(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        try {
            mailService.emptyTrash(userId);
            return ApiResponse.ok("垃圾箱已清空", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 保存草稿
     * POST /mail/draft
     */
    @PostMapping("/draft")
    public ApiResponse<Mail> saveDraft(HttpServletRequest request, @Valid @RequestBody SendMailRequest req) {
        try {
            Long userId = (Long) request.getAttribute("userId");
            Mail mail = mailService.saveDraft(userId, req.getReceiverEmails(), req.getCcEmails(),
                    req.getSubject(), req.getBody(), req.getAttachmentIds());
            return ApiResponse.ok("草稿已保存", mail);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 更新草稿
     * PUT /mail/draft/{id}
     */
    @PutMapping("/draft/{id}")
    public ApiResponse<Mail> updateDraft(HttpServletRequest request, @PathVariable Long id,
                                          @Valid @RequestBody SendMailRequest req) {
        try {
            Long userId = (Long) request.getAttribute("userId");
            Mail mail = mailService.updateDraft(id, userId, req.getReceiverEmails(), req.getCcEmails(),
                    req.getSubject(), req.getBody(), req.getAttachmentIds());
            return ApiResponse.ok("草稿已更新", mail);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 发送草稿
     * PUT /mail/draft/{id}/send
     */
    @PutMapping("/draft/{id}/send")
    public ApiResponse<Mail> sendDraft(HttpServletRequest request, @PathVariable Long id) {
        try {
            Long userId = (Long) request.getAttribute("userId");
            Mail mail = mailService.sendDraft(id, userId);
            return ApiResponse.ok("草稿已发送", mail);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 删除草稿
     * DELETE /mail/draft/{id}
     */
    @DeleteMapping("/draft/{id}")
    public ApiResponse<Void> deleteDraft(HttpServletRequest request, @PathVariable Long id) {
        try {
            Long userId = (Long) request.getAttribute("userId");
            mailService.deleteDraft(id, userId);
            return ApiResponse.ok("草稿已删除", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }
}

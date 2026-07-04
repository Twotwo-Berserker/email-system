package com.mailsystem.controller;

import com.mailsystem.dto.*;
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
        Long userId = (Long) request.getAttribute("userId");
        Mail mail = mailService.sendMail(userId, req.getReceiverEmails(), req.getCcEmails(),
                req.getSubject(), req.getBody(), req.getAttachmentIds());
        return ApiResponse.ok("发送成功", mail);
    }

    /**
     * 转发邮件
     * POST /mail/forward/{id}
     */
    @PostMapping("/forward/{id}")
    public ApiResponse<Mail> forward(HttpServletRequest request, @PathVariable Long id,
                                     @Valid @RequestBody ForwardMailRequest req) {
        Long userId = (Long) request.getAttribute("userId");
        Mail mail = mailService.forwardMail(userId, id, req.getReceiverEmails(),
                req.getCcEmails(), req.getAdditionalBody());
        return ApiResponse.ok("转发成功", mail);
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
     * 邮件列表（按类型，带分页）
     * GET /mail/list?type=1&page=1&pageSize=20
     * type: 1=收件箱, 2=已发送, 3=垃圾箱, 4=草稿
     */
    @GetMapping("/list")
    public ApiResponse<PageResult<Mail>> list(HttpServletRequest request,
                                              @RequestParam(defaultValue = "1") Integer type,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int pageSize) {
        Long userId = (Long) request.getAttribute("userId");
        PageResult<Mail> result = mailService.listMails(userId, type, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * 邮件详情
     * GET /mail/detail/{id}
     */
    @GetMapping("/detail/{id}")
    public ApiResponse<Mail> detail(HttpServletRequest request, @PathVariable Long id) {
        Long userId = (Long) request.getAttribute("userId");
        Mail mail = mailService.getMailDetail(id, userId);
        mailService.markAsRead(id, userId);
        return ApiResponse.ok(mail);
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
        mailService.deleteMail(id, userId);
        return ApiResponse.ok("已删除", null);
    }

    /**
     * 批量删除邮件
     * DELETE /mail/batch-delete
     */
    @DeleteMapping("/batch-delete")
    public ApiResponse<Void> batchDelete(HttpServletRequest request, @RequestBody List<Long> mailIds) {
        Long userId = (Long) request.getAttribute("userId");
        mailService.batchDelete(mailIds, userId);
        return ApiResponse.ok("已批量删除", null);
    }

    /**
     * 批量永久删除邮件（垃圾箱用）
     * DELETE /mail/batch-permanent-delete
     */
    @DeleteMapping("/batch-permanent-delete")
    public ApiResponse<Void> batchPermanentDelete(HttpServletRequest request, @RequestBody List<Long> mailIds) {
        Long userId = (Long) request.getAttribute("userId");
        mailService.batchPermanentDelete(mailIds, userId);
        return ApiResponse.ok("已批量彻底删除", null);
    }

    /**
     * 搜索邮件
     * GET /mail/search?keyword=xxx&page=1&pageSize=20
     */
    @GetMapping("/search")
    public ApiResponse<PageResult<Mail>> search(HttpServletRequest request,
                                                @RequestParam String keyword,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int pageSize) {
        Long userId = (Long) request.getAttribute("userId");
        PageResult<Mail> result = mailService.searchMails(userId, keyword, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * 邮件增量同步
     * GET /mail/sync?since=2026-01-01 00:00:00
     */
    @GetMapping("/sync")
    public ApiResponse<List<SyncMailEvent>> sync(HttpServletRequest request,
                                                  @RequestParam String since) {
        Long userId = (Long) request.getAttribute("userId");
        List<SyncMailEvent> events = mailService.syncChanges(userId, since);
        return ApiResponse.ok(events);
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
        mailService.restoreMail(id, userId);
        return ApiResponse.ok("已恢复", null);
    }

    /**
     * 永久删除邮件（从垃圾箱彻底删除）
     * DELETE /mail/permanent/{id}
     */
    @DeleteMapping("/permanent/{id}")
    public ApiResponse<Void> permanentDelete(HttpServletRequest request, @PathVariable Long id) {
        Long userId = (Long) request.getAttribute("userId");
        mailService.permanentDeleteMail(id, userId);
        return ApiResponse.ok("已彻底删除", null);
    }

    /**
     * 清空垃圾箱
     * PUT /mail/trash/empty
     */
    @PutMapping("/trash/empty")
    public ApiResponse<Void> emptyTrash(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        mailService.emptyTrash(userId);
        return ApiResponse.ok("垃圾箱已清空", null);
    }

    /**
     * 保存草稿
     * POST /mail/draft
     */
    @PostMapping("/draft")
    public ApiResponse<Mail> saveDraft(HttpServletRequest request, @Valid @RequestBody SendMailRequest req) {
        Long userId = (Long) request.getAttribute("userId");
        Mail mail = mailService.saveDraft(userId, req.getReceiverEmails(), req.getCcEmails(),
                req.getSubject(), req.getBody(), req.getAttachmentIds());
        return ApiResponse.ok("草稿已保存", mail);
    }

    /**
     * 更新草稿
     * PUT /mail/draft/{id}
     */
    @PutMapping("/draft/{id}")
    public ApiResponse<Mail> updateDraft(HttpServletRequest request, @PathVariable Long id,
                                          @Valid @RequestBody SendMailRequest req) {
        Long userId = (Long) request.getAttribute("userId");
        Mail mail = mailService.updateDraft(id, userId, req.getReceiverEmails(), req.getCcEmails(),
                req.getSubject(), req.getBody(), req.getAttachmentIds());
        return ApiResponse.ok("草稿已更新", mail);
    }

    /**
     * 发送草稿
     * PUT /mail/draft/{id}/send
     */
    @PutMapping("/draft/{id}/send")
    public ApiResponse<Mail> sendDraft(HttpServletRequest request, @PathVariable Long id) {
        Long userId = (Long) request.getAttribute("userId");
        Mail mail = mailService.sendDraft(id, userId);
        return ApiResponse.ok("草稿已发送", mail);
    }

    /**
     * 删除草稿
     * DELETE /mail/draft/{id}
     */
    @DeleteMapping("/draft/{id}")
    public ApiResponse<Void> deleteDraft(HttpServletRequest request, @PathVariable Long id) {
        Long userId = (Long) request.getAttribute("userId");
        mailService.deleteDraft(id, userId);
        return ApiResponse.ok("草稿已删除", null);
    }
}

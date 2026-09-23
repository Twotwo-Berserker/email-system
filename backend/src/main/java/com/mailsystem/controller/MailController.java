package com.mailsystem.controller;

import com.mailsystem.dto.*;
import com.mailsystem.entity.Attachment;
import com.mailsystem.entity.Mail;
import com.mailsystem.entity.UserFeedback;
import com.mailsystem.service.AttachmentService;
import com.mailsystem.service.FeedbackService;
import com.mailsystem.service.MailAnalysisService;
import com.mailsystem.service.MailService;
import com.mailsystem.util.MimeBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.ArrayList;
import java.util.List;

/**
 * 邮件控制器 — /mail/*
 */
@RestController
@RequestMapping("/mail")
public class MailController {

    @Autowired
    private MailService mailService;

    @Autowired
    private MailAnalysisService mailAnalysisService;

    @Autowired
    private FeedbackService feedbackService;

    /** 读附件内容 —— 只在原始报文重建（IMAP 代理）时用到 */
    @Autowired
    private AttachmentService attachmentService;

    @Autowired
    private MimeBuilder mimeBuilder;

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
     * 原始报文（RFC822）
     * GET /mail/detail/{id}/raw
     * <p>
     * 供 IMAP 代理（{@code proxy/}）使用：IMAP 的 {@code FETCH BODY[]} 必须交出
     * 整封报文的字节，而库内是拆开的字段 + 附件表，只有后端拼得出来（见
     * {@code MimeBuilder}）。
     * </p>
     * <p>
     * 归属校验同样借 {@code getMailDetail}。这个接口<b>不做 {@code markAsRead}</b> ——
     * 它会被邮件客户端在同步时批量调用（列表预览、预取正文），把它当成"用户读了"
     * 会让整箱邮件在用户还没打开时就全变已读。
     * </p>
     */
    @GetMapping("/detail/{id}/raw")
    public ResponseEntity<byte[]> raw(HttpServletRequest request, @PathVariable Long id) {
        Long userId = (Long) request.getAttribute("userId");
        Mail mail = mailService.getMailDetail(id, userId);

        List<MimeBuilder.AttachmentPart> parts = new ArrayList<>();
        for (Attachment att : mailService.getAttachments(id)) {
            parts.add(new MimeBuilder.AttachmentPart(
                    att.getFileName(), att.getContentType(), attachmentService.getAttachmentData(att.getId())));
        }

        try {
            byte[] raw = mimeBuilder.build(mail, parts);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("message/rfc822"))
                    .body(raw);
        } catch (Exception e) {
            System.err.println("[Mail] 邮件#" + id + " 报文重建失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 获取邮件附件列表
     * GET /mail/detail/{id}/attachments
     * <p>
     * 先过一遍详情查询做归属校验 —— 它带授权判据（收件人或发件人）。
     * 原先这里完全不取 userId，任何登录用户报一个邮件 ID 就能列出
     * 别人邮件的附件清单（文件名 + 大小），再配合附件下载接口拿走内容。
     * </p>
     */
    @GetMapping("/detail/{id}/attachments")
    public ApiResponse<List<Attachment>> attachments(HttpServletRequest request, @PathVariable Long id) {
        Long userId = getUserId(request);
        mailService.getMailDetail(id, userId);
        List<Attachment> attachments = mailService.getAttachments(id);
        return ApiResponse.ok(attachments);
    }

    // ==================== 智能分析面板与人工反馈 ====================

    /**
     * 获取某封邮件的智能分析面板
     * GET /mail/detail/{id}/analysis
     * <p>
     * 返回分类、风险、置信度、判定依据、建议动作、模型与 Prompt 版本，
     * 以及当前用户已提交的反馈。列表页不需要这个接口 ——
     * 那四项结论已经由查询层的 COALESCE 覆盖到 {@code Mail} 上了。
     * </p>
     */
    @GetMapping("/detail/{id}/analysis")
    public ApiResponse<MailAnalysisView> analysis(HttpServletRequest request, @PathVariable Long id) {
        Long userId = getUserId(request);
        // 先过一遍详情查询：它带授权判据（收件人或发件人），
        // 用它来挡住"读别人邮件的分析结论"。viewFor 本身不做授权
        mailService.getMailDetail(id, userId);
        return ApiResponse.ok(mailAnalysisService.viewFor(id, userId));
    }

    /**
     * 重新分析一封邮件（跳过"内容未变则不重跑"的检查）
     * POST /mail/detail/{id}/reanalyze
     * <p>
     * 同步执行：用户点了按钮就在等结果，异步返回一个"已提交"没有意义。
     * 单封邮件的分析最坏情况是一次 read timeout（默认 12 秒），
     * 可以接受。
     * </p>
     */
    @PostMapping("/detail/{id}/reanalyze")
    public ApiResponse<MailAnalysisView> reanalyze(HttpServletRequest request, @PathVariable Long id) {
        Long userId = getUserId(request);
        try {
            boolean analyzed = mailAnalysisService.reanalyze(id, userId);
            MailAnalysisView view = mailAnalysisService.viewFor(id, userId);
            return ApiResponse.ok(analyzed ? "已重新分析" : "邮件不存在或无权访问", view);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 提交对分析结论的反馈
     * POST /mail/detail/{id}/feedback
     * Body: { "feedbackType": "AGREE"|"DISAGREE", "correctedCategory": "...",
     *         "correctedSpam": 0|1, "comment": "..." }
     */
    @PostMapping("/detail/{id}/feedback")
    public ApiResponse<MailAnalysisView> submitFeedback(HttpServletRequest request,
                                                        @PathVariable Long id,
                                                        @Valid @RequestBody FeedbackRequest req) {
        Long userId = getUserId(request);
        try {
            feedbackService.submit(id, userId, req);
            return ApiResponse.ok("反馈已提交", mailAnalysisService.viewFor(id, userId));
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 查询当前用户对某封邮件的反馈
     * GET /mail/detail/{id}/feedback
     */
    @GetMapping("/detail/{id}/feedback")
    public ApiResponse<UserFeedback.UserFeedbackView> myFeedback(HttpServletRequest request,
                                                                 @PathVariable Long id) {
        Long userId = getUserId(request);
        mailService.getMailDetail(id, userId);
        UserFeedback feedback = feedbackService.findMine(id, userId);
        return ApiResponse.ok(feedback == null ? null : feedback.toView());
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
     * 标记未读
     * PUT /mail/unread/{id}
     */
    @PutMapping("/unread/{id}")
    public ApiResponse<Void> unread(HttpServletRequest request, @PathVariable Long id) {
        Long userId = getUserId(request);
        mailService.markAsUnread(id, userId);
        return ApiResponse.ok("已标记为未读", null);
    }

    /**
     * 切换已读/未读状态
     * PUT /mail/toggle-read/{id}
     */
    @PutMapping("/toggle-read/{id}")
    public ApiResponse<Boolean> toggleRead(HttpServletRequest request, @PathVariable Long id) {
        Long userId = getUserId(request);
        boolean isRead = mailService.toggleRead(id, userId);
        return ApiResponse.ok(isRead ? "已标记为已读" : "已标记为未读", isRead);
    }

    /** 从请求中安全提取 userId */
    private Long getUserId(HttpServletRequest request) {
        Object uid = request.getAttribute("userId");
        if (uid instanceof Long) return (Long) uid;
        if (uid instanceof Integer) return ((Integer) uid).longValue();
        if (uid != null) return Long.valueOf(uid.toString());
        return null;
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

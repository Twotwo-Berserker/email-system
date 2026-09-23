package com.mailsystem.controller;

import com.mailsystem.dto.ApiResponse;
import com.mailsystem.dto.CloudflareBindRequest;
import com.mailsystem.dto.InboundCapabilityView;
import com.mailsystem.dto.MailAccountRequest;
import com.mailsystem.dto.MailAccountView;
import com.mailsystem.dto.ProviderInfoView;
import com.mailsystem.dto.QuickBindRequest;
import com.mailsystem.dto.QuickBindResult;
import com.mailsystem.entity.MailAccount;
import com.mailsystem.entity.User;
import com.mailsystem.service.ImapReceiveService;
import com.mailsystem.service.MailAccountService;
import com.mailsystem.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 邮箱账户控制器 — /mail-account/*
 * <p>
 * 用户自助绑定自己的外部邮箱（SMTP 发信 / IMAP 收信）。
 * 账户归属由 {@code MailAccountService.requireOwned} 校验，
 * 控制器不信任请求体里的任何 user 字段。
 * </p>
 * <p>
 * 整个路径由 {@code JwtInterceptor}（{@code addPathPatterns("/**")}）保护，
 * 无需在此重复鉴权。
 * </p>
 */
@RestController
@RequestMapping("/mail-account")
public class MailAccountController {

    @Autowired
    private MailAccountService mailAccountService;

    @Autowired
    private ImapReceiveService imapReceiveService;

    @Autowired
    private UserService userService;

    /**
     * 当前用户的账户列表（授权码字段脱敏）
     * GET /mail-account/list
     */
    @GetMapping("/list")
    public ApiResponse<List<MailAccountView>> list(HttpServletRequest request) {
        Long userId = currentUserId(request);
        return ApiResponse.ok(mailAccountService.listForUser(userId, currentEmail(request, userId)));
    }

    /**
     * 识别邮箱服务商
     * GET /mail-account/detect?email=xxx&amp;mx=false
     * <p>
     * 只依赖邮箱地址，不需要授权码，因此前端可以在用户<b>边打字时</b>就调用 ——
     * 这正是把"识别"与"绑定"拆成两个接口的原因：用户还没决定要不要绑定，
     * 就已经能看到"我认出你的邮箱了，授权码在这里生成"。
     * </p>
     * <p>
     * {@code mx=true} 会做一次 DNS 查询（最长数秒），因此只在用户点击绑定前
     * 或明确请求时才开启；实时识别默认关闭。
     * </p>
     */
    @GetMapping("/detect")
    public ApiResponse<ProviderInfoView> detect(@RequestParam("email") String email,
                                                @RequestParam(value = "mx", defaultValue = "false")
                                                boolean useMx) {
        try {
            return ApiResponse.ok(mailAccountService.detectProvider(email, useMx));
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 一键绑定
     * POST /mail-account/quick-bind
     * <p>
     * 只需邮箱地址与授权码。服务器地址、端口、SSL/STARTTLS 由后端识别 + 真实
     * 连接探测得出，探测通过后才落库。
     * </p>
     * <p>
     * 探测失败时返回的 message 是多行的（含各协议的尝试明细与授权码获取步骤），
     * 前端应以弹窗而非轻提示呈现。
     * </p>
     */
    @PostMapping("/quick-bind")
    public ApiResponse<QuickBindResult> quickBind(@Valid @RequestBody QuickBindRequest req,
                                                  HttpServletRequest request) {
        try {
            QuickBindResult result = mailAccountService.quickBind(currentUserId(request), req);
            return ApiResponse.ok(quickBindMessage(result), result);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /** 两侧都通、只通一侧，是三种不同的结果，提示语也应当不同 */
    private String quickBindMessage(QuickBindResult result) {
        if (result.isSmtpOk() && result.isImapOk()) {
            return "绑定成功，收发信均已就绪";
        }
        if (result.isSmtpOk()) {
            return "绑定成功，可以发信；收信未能开通";
        }
        if (result.isImapOk()) {
            return "绑定成功，可以收信；发信未能开通";
        }
        return "绑定成功";
    }

    /**
     * 本域邮箱能力说明
     * GET /mail-account/capabilities
     * <p>
     * 前端据此决定「本域地址」入口显不显示、显示成什么样。见
     * {@code InboundCapabilityView} 的解释 —— "能不能领"取决于部署方配置，
     * 用户从界面上看不出来。
     * </p>
     */
    @GetMapping("/capabilities")
    public ApiResponse<InboundCapabilityView> capabilities() {
        return ApiResponse.ok(mailAccountService.inboundCapabilities());
    }

    /**
     * 领取本系统域名下的地址（无需授权码）
     * POST /mail-account/inbound
     * <p>
     * 与「一键绑定」相对：那边是"用你已有的邮箱"，这边是"给你一个新的"。
     * 没有探测、没有授权码 —— 收信由 Cloudflare 推送，发信走项目级中继，
     * 系统不登录任何第三方服务器。
     * </p>
     */
    @PostMapping("/inbound")
    public ApiResponse<MailAccountView> bindInbound(@RequestBody CloudflareBindRequest req,
                                                    HttpServletRequest request) {
        try {
            return ApiResponse.ok("地址领取成功，现在就可以收信了",
                    mailAccountService.bindCloudflareAddress(currentUserId(request), req));
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 新增账户
     * POST /mail-account
     */
    @PostMapping
    public ApiResponse<MailAccountView> create(@Valid @RequestBody MailAccountRequest req,
                                               HttpServletRequest request) {
        try {
            return ApiResponse.ok("邮箱绑定成功",
                    mailAccountService.create(currentUserId(request), req));
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 修改账户（授权码留空或回传掩码表示不修改）
     * PUT /mail-account/{id}
     */
    @PutMapping("/{id}")
    public ApiResponse<MailAccountView> update(@PathVariable Long id,
                                               @Valid @RequestBody MailAccountRequest req,
                                               HttpServletRequest request) {
        try {
            return ApiResponse.ok("邮箱配置已更新",
                    mailAccountService.update(currentUserId(request), id, req));
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 解绑账户
     * DELETE /mail-account/{id}
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        try {
            mailAccountService.delete(currentUserId(request), id);
            return ApiResponse.ok("已解绑", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 测试连接
     * POST /mail-account/{id}/test
     * <p>
     * SMTP 与 IMAP 分别测试，任一侧失败不影响另一侧的结果 ——
     * 只看发信的用户不需要为了 IMAP 配错而卡住。
     * </p>
     */
    @PostMapping("/{id}/test")
    public ApiResponse<Map<String, Object>> test(@PathVariable Long id, HttpServletRequest request) {
        try {
            return ApiResponse.ok("测试完成", mailAccountService.testConnection(currentUserId(request), id));
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 手动触发一次同步
     * POST /mail-account/{id}/sync
     * <p>
     * 同步是阻塞的（用户点了按钮就在等结果），耗时为一次 IMAP 往返 + 解析。
     * 因此这里只同步<b>单个</b>账户，不做全量 —— 全量留给定时任务。
     * </p>
     */
    @PostMapping("/{id}/sync")
    public ApiResponse<MailAccountView> sync(@PathVariable Long id, HttpServletRequest request) {
        try {
            MailAccount account = mailAccountService.requireOwned(currentUserId(request), id);
            int saved = imapReceiveService.syncAccount(account);

            // 重新读一次以带上本次同步写入的 last_sync_* 字段
            MailAccount fresh = mailAccountService.requireOwned(currentUserId(request), id);
            MailAccountView view = MailAccountView.from(fresh);
            view.setSyncedCount(saved);
            return ApiResponse.ok("同步完成，新增 " + saved + " 封邮件", view);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 私有方法 ====================

    private Long currentUserId(HttpServletRequest request) {
        return (Long) request.getAttribute("userId");
    }

    /**
     * 当前用户的邮箱，用于返回体里的 ownerEmail。
     * <p>
     * 取不到时返回 null：这个字段只是展示用，不该因为一次查询失败让整个
     * 列表接口报错。
     * </p>
     */
    private String currentEmail(HttpServletRequest request, Long userId) {
        try {
            User user = userService.getById(userId);
            return user == null ? null : user.getEmail();
        } catch (Exception e) {
            return null;
        }
    }
}

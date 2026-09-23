package com.mailsystem.controller;

import com.mailsystem.dto.*;
import com.mailsystem.entity.MailAccount;
import com.mailsystem.entity.PluginConfig;
import com.mailsystem.entity.PromptTemplate;
import com.mailsystem.service.AdminService;
import com.mailsystem.service.ImapReceiveService;
import com.mailsystem.service.MailAccountService;
import com.mailsystem.service.PluginService;
import com.mailsystem.service.PromptTemplateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 管理端控制器 — /admin/*
 * <p>
 * <b>整个 {@code /admin/**} 由 {@code AdminInterceptor} 限制为管理员访问</b>，
 * 控制器内不再重复做权限判断。
 * </p>
 * <p>
 * 操作人 ID 一律取自 Token（request attribute），不接受请求体传入 ——
 * 否则"不能给自己降级""不能禁用最后一个管理员"这些保护可以被伪造的操作人绕过。
 * </p>
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    @Autowired
    private AdminService adminService;

    @Autowired
    private PluginService pluginService;

    @Autowired
    private MailAccountService mailAccountService;

    @Autowired
    private ImapReceiveService imapReceiveService;

    @Autowired
    private PromptTemplateService promptTemplateService;

    // ==================== 用户管理 ====================

    /**
     * 用户列表
     * GET /admin/users?page=1&pageSize=20&keyword=&role=&status=
     */
    @GetMapping("/users")
    public ApiResponse<PageResult<UserAdminView>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Integer status) {
        // 限制页大小，避免一次拉全表
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        int safePage = Math.max(page, 1);
        return ApiResponse.ok(adminService.listUsers(safePage, safeSize, keyword, role, status));
    }

    /**
     * 修改用户角色
     * PUT /admin/users/{id}/role
     */
    @PutMapping("/users/{id}/role")
    public ApiResponse<Void> updateRole(@PathVariable Long id,
                                        @Valid @RequestBody UpdateRoleRequest req,
                                        HttpServletRequest request) {
        try {
            adminService.updateRole(operatorId(request), id, req.getRole());
            return ApiResponse.ok("角色已更新", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 启用/禁用用户
     * PUT /admin/users/{id}/status
     */
    @PutMapping("/users/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @Valid @RequestBody UpdateStatusRequest req,
                                          HttpServletRequest request) {
        try {
            adminService.updateStatus(operatorId(request), id, req.getStatus());
            return ApiResponse.ok(req.getStatus() == 1 ? "账号已启用" : "账号已禁用", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 重置用户密码（无需旧密码，但会强制其下次登录改密）
     * PUT /admin/users/{id}/password
     */
    @PutMapping("/users/{id}/password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id,
                                           @Valid @RequestBody ChangePasswordRequest req,
                                           HttpServletRequest request) {
        try {
            adminService.resetPassword(operatorId(request), id, req.getNewPassword());
            return ApiResponse.ok("密码已重置，该用户下次登录需修改密码", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 概览 ====================

    /**
     * 概览计数
     * GET /admin/stats/overview
     */
    @GetMapping("/stats/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(adminService.overview());
    }

    /**
     * 智能分析的状态分布（PENDING/RUNNING/DONE/FAILED、重跑次数、孤儿行）
     * GET /admin/stats/analysis
     */
    @GetMapping("/stats/analysis")
    public ApiResponse<Map<String, Object>> analysisStats() {
        return ApiResponse.ok(adminService.analysisStatusStats());
    }

    // ==================== 反馈汇总（P3） ====================

    /**
     * 反馈准确率与分组采纳率
     * GET /admin/feedback/stats
     * <p>
     * 准确率 = AGREE / (AGREE + DISAGREE)。<b>只统计表过态的邮件</b> ——
     * 把用户没反馈的当作认可会得到一个漂亮但无意义的数字。
     * </p>
     */
    @GetMapping("/feedback/stats")
    public ApiResponse<Map<String, Object>> feedbackStats() {
        return ApiResponse.ok(adminService.feedbackStats());
    }

    /**
     * 分歧样本分页列表（被纠正过的邮件）
     * GET /admin/feedback/list?page=1&pageSize=20
     */
    @GetMapping("/feedback/list")
    public ApiResponse<Map<String, Object>> feedbackList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(adminService.feedbackDisagreements(page, pageSize));
    }

    // ==================== LLM 调用监控（P3） ====================

    /**
     * 近 N 天的 LLM 调用指标与按用户分布
     * GET /admin/stats/llm?days=7
     */
    @GetMapping("/stats/llm")
    public ApiResponse<Map<String, Object>> llmStats(
            @RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(adminService.llmStats(days));
    }

    // ==================== 邮箱账户监控 ====================

    /**
     * 所有用户的邮箱绑定与同步状态
     * GET /admin/mail-accounts
     */
    @GetMapping("/mail-accounts")
    public ApiResponse<List<MailAccountView>> listMailAccounts() {
        return ApiResponse.ok(mailAccountService.listAllForAdmin());
    }

    /**
     * 手动触发某个账户的同步
     * POST /admin/mail-accounts/{id}/sync
     * <p>
     * 该接口的作用是排障：用户报"收不到信"时，管理员不必等到下一轮轮询。
     * </p>
     */
    @PostMapping("/mail-accounts/{id}/sync")
    public ApiResponse<MailAccountView> syncMailAccount(@PathVariable Long id) {
        try {
            MailAccount account = mailAccountService.requireById(id);
            int saved = imapReceiveService.syncAccount(account);
            // 重新读一次以带上本次同步写入的 last_sync_* 字段
            MailAccountView view = MailAccountView.from(mailAccountService.requireById(id));
            view.setSyncedCount(saved);
            return ApiResponse.ok("同步完成，新增 " + saved + " 封邮件", view);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 解绑某个账户（用户忘了密码、换了邮箱时的兜底手段）
     * DELETE /admin/mail-accounts/{id}
     */
    @DeleteMapping("/mail-accounts/{id}")
    public ApiResponse<Void> deleteMailAccount(@PathVariable Long id) {
        try {
            mailAccountService.deleteForAdmin(id);
            return ApiResponse.ok("已解绑", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 系统配置 ====================

    /**
     * 系统默认 LLM 配置（apiKey 掩码）
     * GET /admin/config/llm
     */
    @GetMapping("/config/llm")
    public ApiResponse<Map<String, Object>> getLlmConfig() {
        return ApiResponse.ok(pluginService.getLlmConfig());
    }

    /**
     * 更新系统默认 LLM 配置
     * PUT /admin/config/llm
     */
    @PutMapping("/config/llm")
    public ApiResponse<Void> updateLlmConfig(@RequestBody Map<String, Object> body) {
        String apiEndpoint = (String) body.get("apiEndpoint");
        String apiKey = (String) body.get("apiKey");
        String modelName = (String) body.get("modelName");
        Boolean enabled = body.get("enabled") instanceof Boolean ? (Boolean) body.get("enabled") : null;
        try {
            pluginService.updateLlmConfig(apiEndpoint, apiKey, modelName, enabled);
            return ApiResponse.ok("系统默认 LLM 配置已更新", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 插件开关列表
     * GET /admin/config/plugins
     */
    @GetMapping("/config/plugins")
    public ApiResponse<List<PluginConfig>> listPlugins() {
        return ApiResponse.ok(pluginService.listPlugins());
    }

    /**
     * 启用/禁用插件
     * PUT /admin/config/plugins/{name}
     * Body: { "enabled": true }
     */
    @PutMapping("/config/plugins/{name}")
    public ApiResponse<PluginConfig> togglePlugin(@PathVariable String name,
                                                  @RequestBody Map<String, Object> body) {
        Object enabledRaw = body.get("enabled");
        if (!(enabledRaw instanceof Boolean)) {
            return ApiResponse.error("enabled 必须为布尔值");
        }
        try {
            PluginConfig config = pluginService.togglePlugin(name, (Boolean) enabledRaw);
            return ApiResponse.ok((Boolean) enabledRaw ? "插件已启用" : "插件已禁用", config);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== Prompt 模板（P3） ====================

    /**
     * Prompt 模板列表（正文以预览形式返回，不回传全文）
     * GET /admin/config/prompts
     */
    @GetMapping("/config/prompts")
    public ApiResponse<List<PromptTemplate>> listPrompts() {
        return ApiResponse.ok(promptTemplateService.listAll());
    }

    /**
     * 某个 Prompt 版本的完整正文
     * GET /admin/config/prompts/{id}
     * <p>
     * 列表接口只给 200 字预览，管理员判断"这一版写了什么"必须看全文。
     * </p>
     */
    @GetMapping("/config/prompts/{id}")
    public ApiResponse<PromptTemplate> getPrompt(@PathVariable Long id) {
        try {
            return ApiResponse.ok(promptTemplateService.detail(id));
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 新建一个 Prompt 版本
     * POST /admin/config/prompts
     * Body: { "name": "mail_analysis", "version": "v2", "content": "...",
     *         "description": "...", "enabled": false }
     * <p>
     * 新建默认<b>不启用</b>：启用会立刻改变所有走 LLM 的分析结果，
     * 应当是另一个明确的动作（PUT .../{id}/enable），而不是新建的副作用。
     * </p>
     */
    @PostMapping("/config/prompts")
    public ApiResponse<PromptTemplate> createPrompt(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String version = (String) body.get("version");
        String content = (String) body.get("content");
        String description = (String) body.get("description");
        boolean enabled = body.get("enabled") instanceof Boolean && (Boolean) body.get("enabled");

        try {
            PromptTemplate template = promptTemplateService.create(
                    name, version, content, description, enabled);
            return ApiResponse.ok("Prompt 版本已创建", template);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 启用/停用某个 Prompt 版本
     * PUT /admin/config/prompts/{id}/enable
     * Body: { "enabled": true }
     * <p>
     * 启用时会把同名的其他版本停用（两步在同一事务内）；
     * 停用最后一个启用中的版本会被拒绝 —— 那会让分析管线失去 Prompt。
     * </p>
     */
    @PutMapping("/config/prompts/{id}/enable")
    public ApiResponse<PromptTemplate> setPromptEnabled(@PathVariable Long id,
                                                        @RequestBody Map<String, Object> body) {
        Object enabledRaw = body.get("enabled");
        if (!(enabledRaw instanceof Boolean)) {
            return ApiResponse.error("enabled 必须为布尔值");
        }
        try {
            PromptTemplate template = promptTemplateService.setEnabled(id, (Boolean) enabledRaw);
            return ApiResponse.ok((Boolean) enabledRaw ? "已启用" : "已停用", template);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 删除某个 Prompt 版本（启用中的不可删）
     * DELETE /admin/config/prompts/{id}
     */
    @DeleteMapping("/config/prompts/{id}")
    public ApiResponse<Void> deletePrompt(@PathVariable Long id) {
        try {
            promptTemplateService.delete(id);
            return ApiResponse.ok("已删除", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 私有方法 ====================

    private Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute("userId");
    }
}

package com.mailsystem.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mailsystem.dto.PageResult;
import com.mailsystem.dto.UserAdminView;
import com.mailsystem.entity.User;
import com.mailsystem.mapper.LlmCallLogMapper;
import com.mailsystem.mapper.MailIntelligenceResultMapper;
import com.mailsystem.mapper.UserMapper;
import com.mailsystem.service.AdminService;
import com.mailsystem.service.FeedbackService;
import com.mailsystem.service.TokenBlacklistService;
import com.mailsystem.util.PasswordUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理端服务实现
 */
@Service
public class AdminServiceImpl implements AdminService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordUtil passwordUtil;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    @Autowired
    private FeedbackService feedbackService;

    @Autowired
    private LlmCallLogMapper callLogMapper;

    @Autowired
    private MailIntelligenceResultMapper resultMapper;

    /** Token 最长有效期，用作用户级吊销标记的 TTL */
    @Value("${jwt.expiration}")
    private long jwtExpirationMs;

    @Override
    public PageResult<UserAdminView> listUsers(int page, int pageSize, String keyword, String role, Integer status) {
        Page<User> pageObj = new Page<>(page, pageSize);
        IPage<User> result = userMapper.selectPageFiltered(pageObj, keyword, role, status);

        List<UserAdminView> views = result.getRecords().stream()
                // selectPageFiltered 不查 password，所以拿不到哈希，
                // 这里无法逐个判断是否为弱哈希（会多出 N 次查询）。
                // 弱哈希识别改用下面的"整体计数"呈现，见 overview()。
                .map(u -> UserAdminView.from(u, false))
                .collect(Collectors.toList());

        return new PageResult<>(views, result.getTotal(), page, pageSize);
    }

    @Override
    @Transactional
    public void updateRole(Long operatorId, Long targetUserId, String role) {
        User target = requireUser(targetUserId);

        if (role == null || (!User.ROLE_USER.equals(role) && !User.ROLE_ADMIN.equals(role))) {
            throw new RuntimeException("角色只能是 USER 或 ADMIN");
        }
        if (targetUserId.equals(operatorId)) {
            // 防止管理员误操作把自己锁在门外；也避免"最后一个管理员自我降级"
            throw new RuntimeException("不能修改自己的角色");
        }
        if (User.ROLE_ADMIN.equals(target.getRole()) && User.ROLE_USER.equals(role)) {
            assertNotLastEnabledAdmin(target, "降级");
        }

        userMapper.updateRole(targetUserId, role);
    }

    @Override
    @Transactional
    public void updateStatus(Long operatorId, Long targetUserId, Integer status) {
        User target = requireUser(targetUserId);

        if (status == null || (status != User.STATUS_ENABLED && status != User.STATUS_DISABLED)) {
            throw new RuntimeException("状态只能是 0 或 1");
        }
        if (targetUserId.equals(operatorId)) {
            throw new RuntimeException("不能禁用自己的账号");
        }
        if (status == User.STATUS_DISABLED) {
            assertNotLastEnabledAdmin(target, "禁用");
        }

        userMapper.updateStatus(targetUserId, status);

        if (status == User.STATUS_DISABLED) {
            // 禁用必须立刻把已登录的会话踢下线，否则该账号在 Token 剩余有效期内
            // 仍能正常收发邮件 —— 管理员看到"已禁用"却毫无效果。
            tokenBlacklistService.revokeAllForUser(targetUserId, jwtExpirationMs);
        }
    }

    @Override
    @Transactional
    public void resetPassword(Long operatorId, Long targetUserId, String newPassword) {
        User target = requireUser(targetUserId);
        if (newPassword == null || newPassword.length() < 6 || newPassword.length() > 32) {
            throw new RuntimeException("新密码长度需为 6-32 位");
        }
        // mustChangePassword = 1：管理员重置后，用户首次登录必须自己改密，
        // 这样管理员知道的这个临时密码不会长期有效
        userMapper.updatePassword(targetUserId, passwordUtil.encode(newPassword), 1);
        // 重置密码意味着"原密码可能已泄露"，因此旧的登录会话一并作废：
        // 攻击者即便持有旧 Token 也拿不到新密码换来的访问权。
        // 用户用新密码重新登录即可。
        tokenBlacklistService.revokeAllForUser(targetUserId, jwtExpirationMs);
        System.out.println("[Admin] 管理员#" + operatorId + " 重置了用户#" + target.getId() + " 的密码");
    }

    @Override
    public Map<String, Object> overview() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("userTotal", count("SELECT COUNT(*) FROM user"));
        stats.put("userEnabled", count("SELECT COUNT(*) FROM user WHERE status = 1"));
        stats.put("userDisabled", count("SELECT COUNT(*) FROM user WHERE status = 0"));
        stats.put("adminTotal", count("SELECT COUNT(*) FROM user WHERE role = 'ADMIN'"));
        // 仍在使用无盐 SHA-256 的账号 —— 这些账号的密码一旦库泄露即可被批量还原，
        // 管理端据此提示需强制重置
        stats.put("weakPasswordUsers", count("SELECT COUNT(*) FROM user WHERE password NOT LIKE '$2%'"));

        stats.put("mailTotal", count("SELECT COUNT(*) FROM mail WHERE status != 2"));
        stats.put("mailDraft", count("SELECT COUNT(*) FROM mail WHERE status = 2"));
        stats.put("mailExternal", count("SELECT COUNT(*) FROM mail WHERE direction = 'EXTERNAL'"));
        stats.put("mailSpam", count("SELECT COUNT(*) FROM mail WHERE is_spam = 1"));

        stats.put("mailAccountTotal", count("SELECT COUNT(*) FROM mail_account"));
        stats.put("mailAccountEnabled", count("SELECT COUNT(*) FROM mail_account WHERE enabled = 1"));

        stats.put("feedbackTotal", count("SELECT COUNT(*) FROM user_feedback"));
        stats.put("feedbackAgree", count("SELECT COUNT(*) FROM user_feedback WHERE feedback_type = 'AGREE'"));

        stats.put("llmCallTotal", count("SELECT COUNT(*) FROM llm_call_log"));
        stats.put("llmCallFailed",
                count("SELECT COUNT(*) FROM llm_call_log WHERE status NOT IN ('SUCCESS','SKIPPED_NO_KEY')"));

        return stats;
    }

    // ==================== 反馈汇总（P3） ====================

    @Override
    public Map<String, Object> feedbackStats() {
        return feedbackService.stats();
    }

    @Override
    public Map<String, Object> feedbackDisagreements(int page, int pageSize) {
        return feedbackService.disagreements(page, pageSize);
    }

    // ==================== LLM 调用监控（P3） ====================

    @Override
    public Map<String, Object> llmStats(int days) {
        int safeDays = days < 1 ? 1 : days;
        LocalDateTime since = LocalDateTime.now().minusDays(safeDays);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("days", safeDays);
        stats.put("since", since);

        Map<String, Object> summary = callLogMapper.summarySince(since);
        Map<String, Object> overview = new LinkedHashMap<>();
        long calls = valueOf(summary, "calls");
        long success = valueOf(summary, "success");
        long skippedNoKey = valueOf(summary, "skippedNoKey");

        overview.put("calls", calls);
        overview.put("success", success);
        overview.put("notSuccess", valueOf(summary, "notSuccess"));
        overview.put("timeout", valueOf(summary, "timeout"));
        overview.put("httpError", valueOf(summary, "httpError"));
        overview.put("parseError", valueOf(summary, "parseError"));
        overview.put("rejected", valueOf(summary, "rejected"));
        overview.put("skippedNoKey", skippedNoKey);
        overview.put("totalTokens", valueOf(summary, "totalTokens"));

        // 成功率只把 SUCCESS 算成功，且分母要扣掉 SKIPPED_NO_KEY ——
        // "用户没配 Key"不是"调用失败"，把它算进分母会让一个大家都还没配 Key
        // 的新部署显示 0% 成功率，掩盖了真实的调用质量
        long attempted = calls - skippedNoKey;
        overview.put("successRate", attempted <= 0 ? null : round4((double) success / attempted));
        stats.put("overview", overview);

        stats.put("avgLatencyMs", valueOf(summary, "avgLatencyMs"));
        stats.put("byUser", callLogMapper.statsByUserSince(since, 20));
        return stats;
    }

    @Override
    public Map<String, Object> analysisStatusStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        for (Map<String, Object> row : resultMapper.countByStatus()) {
            Object status = row.get("status");
            if (status == null) {
                continue;
            }
            stats.put(status.toString(), valueOf(row, "cnt"));
        }
        // 重跑次数之和与平均：持续偏高说明幂等判断没生效，同一封邮件在反复花钱
        stats.put("totalRevision", count("SELECT COALESCE(SUM(revision), 0) FROM mail_intelligence_result"));
        stats.put("orphanFailed",
                count("SELECT COUNT(*) FROM mail_intelligence_result WHERE error_code = 'ORPHANED'"));
        return stats;
    }

    // ==================== 私有方法 ====================

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        return user;
    }

    /**
     * 拒绝让系统失去最后一个启用状态的管理员。
     * <p>
     * 只拒绝"当前启用 且 是最后一个"的情况。若目标账号本身已被禁用，
     * 降级/禁用它是安全的，不影响管理入口。
     * </p>
     */
    private void assertNotLastEnabledAdmin(User target, String action) {
        if (!target.isAdmin()) {
            return;
        }
        boolean currentlyEnabled = target.getStatus() != null && target.getStatus() == User.STATUS_ENABLED;
        if (!currentlyEnabled) {
            return;
        }
        if (userMapper.countEnabledByRole(User.ROLE_ADMIN) <= 1) {
            throw new RuntimeException(action + "失败：这是最后一个启用状态的管理员账号，"
                    + "操作后系统将失去管理入口。请先创建或启用另一个管理员。");
        }
    }

    private long count(String sql) {
        try {
            Long value = jdbcTemplate.queryForObject(sql, Long.class);
            return value == null ? 0L : value;
        } catch (Exception e) {
            // 概览页不应因为某张表缺失而整体失败（如尚未执行 migration_v3）
            System.err.println("[Admin] 统计失败: " + sql + " -> " + e.getMessage());
            return 0L;
        }
    }

    /**
     * 从聚合查询返回的 Map 里取一个数值。
     * <p>
     * MySQL 的 {@code COUNT(*)} 是 BIGINT、{@code SUM(cond)} 是 DECIMAL、
     * {@code AVG(...)} 是 DOUBLE，同一个 Map 里可能混着三种 Java 类型，
     * 直接强转会在某个字段上莫名其妙地 ClassCastException。
     * </p>
     * <p>
     * 空表时 {@code SUM}/{@code AVG} 返回 NULL，因此缺失与 null 都归零。
     * </p>
     */
    private static long valueOf(Map<String, Object> row, String key) {
        if (row == null) {
            return 0L;
        }
        Object value = row.get(key);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static double round4(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}

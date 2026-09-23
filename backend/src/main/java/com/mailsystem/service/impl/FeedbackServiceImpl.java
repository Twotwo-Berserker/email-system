package com.mailsystem.service.impl;

import com.mailsystem.dto.FeedbackRequest;
import com.mailsystem.entity.Mail;
import com.mailsystem.entity.MailIntelligenceResult;
import com.mailsystem.entity.User;
import com.mailsystem.entity.UserFeedback;
import com.mailsystem.mapper.MailIntelligenceResultMapper;
import com.mailsystem.mapper.MailMapper;
import com.mailsystem.mapper.UserFeedbackMapper;
import com.mailsystem.mapper.UserMapper;
import com.mailsystem.service.FeedbackService;
import com.mailsystem.service.analysis.AnalysisResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工反馈回流服务实现。
 *
 * <h3>提交反馈会改两处，顺序不能反</h3>
 * <ol>
 *   <li>写 {@code mail_intelligence_result.override_*} —— 生效的纠正</li>
 *   <li>写 {@code user_feedback} —— 评价记录，带机器结论快照</li>
 * </ol>
 * <p>
 * 先写覆盖再写反馈：快照要记的是"用户做判断时机器说了什么"。
 * 若先写反馈、后写覆盖，快照取的仍是同一份机器结论（覆盖是另一个列），
 * 顺序其实无所谓 —— 但反过来若将来有人在两者之间加逻辑，
 * 先覆盖会让"快照"有机会读到已经被改过的生效值，那就不再是快照了。
 * </p>
 *
 * <h3>为什么反馈里要快照机器结论</h3>
 * <p>
 * 没有快照，一个用户今天认可、明天重新分析得到不同结论后再认可，
 * 统计上会变成"同一个结论被认可了两次"，准确率凭空变好。
 * 快照把每一条反馈钉在它当时评价的那份结论上。
 * </p>
 */
@Service
public class FeedbackServiceImpl implements FeedbackService {

    @Autowired
    private UserFeedbackMapper feedbackMapper;

    @Autowired
    private MailIntelligenceResultMapper resultMapper;

    @Autowired
    private MailMapper mailMapper;

    @Autowired
    private UserMapper userMapper;

    // ==================== 用户端 ====================

    /**
     * 提交反馈。<b>必须在一个事务里</b> —— 它要写两处（生效的纠正 +
     * 评价记录），只成功一半会得到自相矛盾的状态：用户看到"已纠正"，
     * 管理端的统计里却没有这条；或反过来，统计里有、界面没变。
     * <p>
     * 这里可以放心用事务，与 {@code MailAnalysisServiceImpl} 的取舍不同：
     * 本方法只有两次短 SQL，没有任何网络调用，不会长时间占用连接。
     * </p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserFeedback submit(Long mailId, Long userId, FeedbackRequest request) {
        if (mailId == null || userId == null) {
            throw new RuntimeException("参数不完整");
        }
        if (request == null || request.getFeedbackType() == null) {
            throw new RuntimeException("请选择认可或不认可");
        }
        String type = request.getFeedbackType().trim().toUpperCase();
        if (!UserFeedback.TYPE_AGREE.equals(type) && !UserFeedback.TYPE_DISAGREE.equals(type)) {
            throw new RuntimeException("反馈类型只能是 AGREE 或 DISAGREE");
        }

        // 授权：这封邮件必须与该用户有关。返回 null 统一报"邮件不存在"，
        // 不区分"没这封邮件"与"不是你的邮件" —— 区分开会让接口变成
        // "探测某封邮件是否存在"的工具
        Mail mail = mailMapper.selectDetailForUser(mailId, userId);
        if (mail == null) {
            throw new RuntimeException("邮件不存在");
        }

        String correctedCategory = normalizeCategory(request.getCorrectedCategory());
        Integer correctedSpam = normalizeSpam(request.getCorrectedSpam());

        // 机器结论快照 —— 必须是"用户此刻看到的那个值"。
        // 结果行可能存在但还没跑完（RUNNING/FAILED，结论列为空），
        // 此时用户看到的是 mail 表上的规则兜底值，因此要逐字段回落，
        // 而不是整组二选一
        MailIntelligenceResult result = resultMapper.selectOne(mailId, userId);
        String sourceAtFeedback = result == null || result.getSource() == null
                ? MailIntelligenceResult.SOURCE_RULE : result.getSource();
        String modelName = result == null ? null : result.getModelName();
        String categoryAtFeedback = result == null || result.effectiveCategory() == null
                ? mail.getCategory() : result.effectiveCategory();
        Integer isSpamAtFeedback = result == null || result.effectiveIsSpam() == null
                ? mail.getIsSpam() : result.effectiveIsSpam();

        // 1. 生效的纠正
        if (UserFeedback.TYPE_AGREE.equals(type)) {
            // 改主意为"认可"时，之前纠正过的值必须清掉 ——
            // 否则界面上会出现"已认可"但分类仍是用户改过的那个值
            resultMapper.clearOverride(mailId, userId);
        } else {
            resultMapper.upsertOverride(mailId, userId, correctedCategory, correctedSpam);
        }

        // 2. 评价记录
        UserFeedback feedback = new UserFeedback();
        feedback.setMailId(mailId);
        feedback.setUserId(userId);
        feedback.setFeedbackType(type);
        feedback.setCorrectedCategory(correctedCategory);
        feedback.setCorrectedSpam(correctedSpam);
        feedback.setComment(trimToNull(request.getComment(), UserFeedback.MAX_COMMENT_CHARS));
        feedback.setSourceAtFeedback(sourceAtFeedback);
        feedback.setModelName(modelName);
        feedback.setCategoryAtFeedback(categoryAtFeedback);
        feedback.setIsSpamAtFeedback(isSpamAtFeedback);
        feedbackMapper.upsert(feedback);

        return feedbackMapper.selectOne(mailId, userId);
    }

    @Override
    public UserFeedback findMine(Long mailId, Long userId) {
        if (mailId == null || userId == null) {
            return null;
        }
        return feedbackMapper.selectOne(mailId, userId);
    }

    // ==================== 管理端 ====================

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        Map<String, Object> totals = feedbackMapper.accuracyTotals();
        long total = toLong(totals == null ? null : totals.get("total"));
        long agree = toLong(totals == null ? null : totals.get("agree"));
        long disagree = toLong(totals == null ? null : totals.get("disagree"));

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("total", total);
        overview.put("agree", agree);
        overview.put("disagree", disagree);
        // 分母只算表过态的邮件。把沉默当认可会得到一个漂亮但无意义的准确率
        overview.put("accuracy", total == 0 ? null : round4((double) agree / total));
        stats.put("overview", overview);

        stats.put("byCategory", withRates(feedbackMapper.statsByCategory(), "category"));
        stats.put("bySource", withRates(feedbackMapper.statsBySource(), "source"));
        stats.put("byModel", withRates(feedbackMapper.statsByModel(), "model"));
        return stats;
    }

    @Override
    public Map<String, Object> disagreements(int page, int pageSize) {
        int safePage = page < 1 ? 1 : page;
        // 上限 100：管理端下钻是给人看的，一次几百条既没人看也拖慢接口
        int safeSize = pageSize < 1 ? 20 : Math.min(pageSize, 100);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", feedbackMapper.countDisagreements());
        result.put("page", safePage);
        result.put("pageSize", safeSize);

        List<UserFeedback> rows = feedbackMapper.selectDisagreements(
                (safePage - 1) * safeSize, safeSize);
        result.put("list", withMailSubjects(rows));
        return result;
    }

    /**
     * 给分歧样本补上邮件主题与反馈人邮箱。
     * <p>
     * 管理端要看的是"哪封邮件被谁纠正了"，只有 mailId/userId 等于什么也没说。
     * 两个都用批量查询而不是逐条查 —— 每页 20 条逐条查就是 40 次往返。
     * </p>
     */
    private List<Map<String, Object>> withMailSubjects(List<UserFeedback> rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return list;
        }

        List<Long> mailIds = new ArrayList<>();
        List<Long> userIds = new ArrayList<>();
        for (UserFeedback row : rows) {
            if (row.getMailId() != null) {
                mailIds.add(row.getMailId());
            }
            if (row.getUserId() != null) {
                userIds.add(row.getUserId());
            }
        }
        Map<Long, Mail> mailById = new HashMap<>();
        if (!mailIds.isEmpty()) {
            List<Mail> mails = mailMapper.selectBatchIds(mailIds);
            if (mails != null) {
                for (Mail mail : mails) {
                    mailById.put(mail.getId(), mail);
                }
            }
        }
        Map<Long, String> emailById = new HashMap<>();
        if (!userIds.isEmpty()) {
            List<User> users = userMapper.selectBatchIds(userIds);
            if (users != null) {
                for (User user : users) {
                    emailById.put(user.getId(), user.getEmail());
                }
            }
        }

        for (UserFeedback row : rows) {
            Mail mail = mailById.get(row.getMailId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("mailId", row.getMailId());
            // 邮件可能已被物理删除（清空垃圾箱），此时主题缺失但反馈仍在 ——
            // 统计口径不该因为邮件被删而变化
            item.put("subject", mail == null ? null : mail.getSubject());
            item.put("userId", row.getUserId());
            item.put("userEmail", emailById.get(row.getUserId()));
            item.put("sourceAtFeedback", row.getSourceAtFeedback());
            item.put("modelName", row.getModelName());
            item.put("categoryAtFeedback", row.getCategoryAtFeedback());
            item.put("isSpamAtFeedback", row.getIsSpamAtFeedback());
            item.put("correctedCategory", row.getCorrectedCategory());
            item.put("correctedSpam", row.getCorrectedSpam());
            item.put("comment", row.getComment());
            item.put("createTime", row.getCreateTime());
            list.add(item);
        }
        return list;
    }

    /**
     * 给分组统计补上采纳率。
     * <p>
     * 采纳率在 SQL 里算也行，但那会让每个分组查询都得重复一遍除法与
     * 除零保护；统一在这里算一次，口径就只有一个。
     * </p>
     */
    private List<Map<String, Object>> withRates(List<Map<String, Object>> rows, String keyName) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (rows == null) {
            return list;
        }
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(keyName, row.get(keyName));
            long total = toLong(row.get("total"));
            long agree = toLong(row.get("agree"));
            long disagree = toLong(row.get("disagree"));
            item.put("total", total);
            item.put("agree", agree);
            item.put("disagree", disagree);
            item.put("accuracy", total == 0 ? null : round4((double) agree / total));
            list.add(item);
        }
        return list;
    }

    // ==================== 校验与小工具 ====================

    /**
     * 校验纠正后的分类。
     * <p>
     * 只接受白名单内的取值，理由与 {@code CategoryPlugin} 把"广告"改成"推广"
     * 是同一条：反馈表要按分类分组统计，一旦允许自由文本，
     * "财务"/"财务 "/"财务类" 会各成一类，管理端的图就没法看了。
     * 前端的纠正下拉框用的就是这份白名单，因此实际不会限制到用户。
     * </p>
     */
    private String normalizeCategory(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String trimmed = raw.trim();
        if (!AnalysisResult.CATEGORIES.contains(trimmed)) {
            throw new RuntimeException("分类不在可选范围内");
        }
        return trimmed;
    }

    private Integer normalizeSpam(Integer raw) {
        if (raw == null) {
            return null;
        }
        if (raw != 0 && raw != 1) {
            throw new RuntimeException("垃圾标记只能是 0 或 1");
        }
        return raw;
    }

    private static String trimToNull(String s, int max) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }

    private static double round4(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}

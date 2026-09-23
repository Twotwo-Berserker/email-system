package com.mailsystem.service.analysis;

import com.mailsystem.entity.Mail;
import com.mailsystem.entity.MailIntelligenceResult;
import com.mailsystem.plugin.PluginInterface;
import com.mailsystem.plugin.RuleContribution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则兜底分析器 —— 把各规则插件的贡献合并成一份完整结论。
 *
 * <h3>合并规则是"宁可保守"的</h3>
 * <ul>
 *   <li><b>垃圾判定取或</b>：任一插件认为可疑就标记，因为漏判一封钓鱼邮件的
 *       代价远高于误标一封正常邮件（误标只是多个标签，用户仍能看到原文）</li>
 *   <li><b>垃圾分数取最大</b>：与"取或"同向</li>
 *   <li><b>风险等级取最严重</b>：同上。且分数高也会抬高风险等级，
 *       避免"分数 90 却显示低风险"这种自相矛盾的界面</li>
 *   <li><b>分类/优先级/摘要取第一个非空</b>：这三项每项只有一个插件会表态，
 *       谁表态就用谁的，不做加权平均 —— 平均出来的分类没有意义</li>
 * </ul>
 *
 * <h3>确定性的来源</h3>
 * <p>
 * 各插件用 {@code @Order} 排了固定次序（10/20/30/40/50），因此 Spring 注入的
 * {@code List<PluginInterface>} 顺序是确定的，"取第一个非空"才有可复现的含义。
 * 改造前没有任何 {@code @Order}，同样的输入可能得到不同结果。
 * </p>
 *
 * <h3>单个插件异常不影响整体</h3>
 * <p>
 * 每个 {@code contribute} 都单独 try/catch。兜底路径的价值在于"主路径挂了它还在"，
 * 如果它自己能被一个正则异常掀翻，那它就不算兜底。
 * </p>
 */
@Component
public class RuleAnalyzer {

    /** 垃圾分数达到此值即把风险等级抬到 HIGH */
    private static final int HIGH_RISK_SCORE = 70;

    /** 垃圾分数达到此值即把风险等级抬到 MEDIUM */
    private static final int MEDIUM_RISK_SCORE = 40;

    /** 合并后最多保留的依据条数（与 AnalysisResult 的上限一致） */
    private static final int MAX_INDICATORS = 8;

    @Autowired(required = false)
    private List<PluginInterface> plugins;

    /**
     * 跑一遍所有启用的规则插件。
     *
     * @param mail      待分析邮件
     * @param errorCode 降级原因码（无 Key / 超时 / 熔断 / 解析失败），
     *                  会落进 {@code error_code} 便于管理端区分"为什么走了兜底"
     */
    public AnalysisResult analyze(Mail mail, String errorCode) {
        AnalysisResult result = AnalysisResult.rule(errorCode);

        String category = null;
        String summary = null;
        Integer priority = null;
        int maxSpamScore = 0;
        boolean spam = false;
        String risk = AnalysisResult.RISK_LOW;
        List<String> indicators = new ArrayList<>();

        if (plugins != null) {
            for (PluginInterface plugin : plugins) {
                RuleContribution contribution;
                try {
                    if (!plugin.isEnabled()) {
                        continue;
                    }
                    contribution = plugin.contribute(mail);
                } catch (Exception e) {
                    // 一个插件出错不该让整封邮件失去分类
                    System.err.println("[RuleAnalyzer] 插件 " + plugin.getName()
                            + " 计算失败: " + e.getMessage());
                    result.addWarning("插件失败: " + plugin.getName());
                    continue;
                }
                if (contribution == null || contribution.isEmpty()) {
                    continue;
                }

                if (category == null && contribution.getCategory() != null) {
                    category = contribution.getCategory();
                }
                if (summary == null && contribution.getSummary() != null) {
                    summary = contribution.getSummary();
                }
                if (priority == null && contribution.getPriority() != null) {
                    priority = contribution.getPriority();
                }
                if (contribution.getIsSpam() != null && contribution.getIsSpam() == 1) {
                    spam = true;
                }
                if (contribution.getSpamScore() != null) {
                    maxSpamScore = Math.max(maxSpamScore, contribution.getSpamScore());
                }
                risk = moreSevere(risk, contribution.getRiskLevel());

                for (String indicator : contribution.getIndicators()) {
                    if (indicators.size() >= MAX_INDICATORS) {
                        break;
                    }
                    indicators.add(indicator);
                }
            }
        }

        // 分数本身就是风险信号：一串垃圾特征同时命中时，风险不可能还是"低"
        risk = moreSevere(risk, scoreToRisk(maxSpamScore));

        result.setCategory(category == null ? AnalysisResult.CATEGORY_OTHER : category);
        result.setSummary(summary);
        result.setPriority(priority == null ? 50 : priority);
        result.setSpam(spam);
        result.setSpamScore(maxSpamScore);
        result.setRisk(risk);
        result.setIndicators(indicators);
        // 规则路径没有"模型的置信度"这回事。留 null 而不是填一个假数字 ——
        // 管理端的置信度分布图不该混入规则结论
        result.setConfidence(null);
        return result.sanitize();
    }

    /**
     * 判断是否所有规则插件都被管理员关掉了。
     * <p>
     * 全关时 {@link #analyze} 仍会返回一份"其他 / 非垃圾 / 中优先级"的结论，
     * 调用方据此在日志里说明"兜底其实什么也没算"。
     * </p>
     */
    public boolean hasEnabledPlugin() {
        if (plugins == null) {
            return false;
        }
        for (PluginInterface plugin : plugins) {
            try {
                if (plugin.isEnabled()) {
                    return true;
                }
            } catch (Exception e) {
                // 开关本身读不出来（比如 plugin_config 表还没建），按未启用处理
            }
        }
        return false;
    }

    private static String scoreToRisk(int spamScore) {
        if (spamScore >= HIGH_RISK_SCORE) {
            return MailIntelligenceResult.RISK_HIGH;
        }
        if (spamScore >= MEDIUM_RISK_SCORE) {
            return MailIntelligenceResult.RISK_MEDIUM;
        }
        return MailIntelligenceResult.RISK_LOW;
    }

    /**
     * 取两个风险等级中更严重的那个。null 视为最低。
     */
    private static String moreSevere(String a, String b) {
        return severityOf(a) >= severityOf(b) ? normalize(a) : normalize(b);
    }

    private static int severityOf(String risk) {
        if (MailIntelligenceResult.RISK_HIGH.equals(risk)) {
            return 3;
        }
        if (MailIntelligenceResult.RISK_MEDIUM.equals(risk)) {
            return 2;
        }
        if (MailIntelligenceResult.RISK_LOW.equals(risk)) {
            return 1;
        }
        return 0;
    }

    private static String normalize(String risk) {
        return risk == null ? AnalysisResult.RISK_LOW : risk;
    }
}

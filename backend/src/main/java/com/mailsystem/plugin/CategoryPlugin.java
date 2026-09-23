package com.mailsystem.plugin;

import com.mailsystem.entity.Mail;
import com.mailsystem.service.PluginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 邮件分类插件（规则兜底）
 *
 * <h3>分类标签与 LLM 的枚举必须一致</h3>
 * <p>
 * 本插件的标签集原本是"工作/个人/财务/通知/安全/社交/教育/广告/其他"，
 * 而 LLM 的 Prompt 里是"工作/个人/财务/通知/推广/社交/验证码/其他"。
 * 两套标签并存会导致同一封邮件在走 LLM 和走兜底时显示成不同的分类，
 * 管理端的"按分类统计纠正率"也会把同一类拆成两行。
 * </p>
 * <p>
 * 现已统一到 {@link com.mailsystem.service.analysis.AnalysisResult#CATEGORIES}
 * 的十项清单：原来的 {@code 广告} 改为 {@code 推广}（与 LLM 对齐），
 * 其余保留。<b>注意两个插件之间的关键词规则并没有合并</b> ——
 * 规则仍是各自独立的一套，只是输出的标签名统一了。
 * </p>
 */
@Component
@Order(30)
public class CategoryPlugin implements PluginInterface {

    /**
     * 分类规则: 标签名 -> 关键词正则
     * <p>
     * 标签名必须落在 {@code AnalysisResult.CATEGORIES} 内。
     * </p>
     */
    private static final String[][] CATEGORY_RULES = {
            {"工作", "工作|项目|任务|会议|报告|需求|上线|发布|开发|测试|部署"},
            {"个人", "朋友|家人|聚会|生日|旅游|周末|吃饭|活动"},
            {"财务", "发票|报销|付款|账单|银行|工资|理财|投资|订单|支付"},
            {"通知", "通知|公告|提醒|系统|自动|周报|日报|月报"},
            {"安全", "密码|安全|验证|登录|权限|认证|token|账号"},
            {"社交", "关注|评论|点赞|分享|好友|邀请|注册"},
            {"教育", "课程|学习|培训|考试|证书|毕业|论文|实训"},
            {"推广", "促销|优惠|折扣|免费|限时|活动|推广|特价|广告"}
    };

    /** 无任何关键词命中时的分类 */
    private static final String DEFAULT_CATEGORY = "其他";

    /** 最多记录几个命中的关键词作为依据 */
    private static final int MAX_HIT_KEYWORDS = 5;

    @Autowired
    private PluginService pluginService;

    @Override
    public String getName() {
        return "categoryClassifier";
    }

    @Override
    public RuleContribution contribute(Mail mail) {
        String content = ((mail.getSubject() == null ? "" : mail.getSubject())
                + " "
                + (mail.getBody() == null ? "" : mail.getBody())).toLowerCase();

        String bestCategory = DEFAULT_CATEGORY;
        int bestScore = 0;
        List<String> bestHits = new ArrayList<>();

        for (String[] rule : CATEGORY_RULES) {
            String label = rule[0];
            List<String> hits = new ArrayList<>();
            for (String keyword : rule[1].split("\\|")) {
                if (content.contains(keyword.toLowerCase())) {
                    hits.add(keyword);
                }
            }
            if (hits.size() > bestScore) {
                bestScore = hits.size();
                bestCategory = label;
                bestHits = hits;
            }
        }

        RuleContribution contribution = RuleContribution.category(bestCategory);
        if (bestScore > 0) {
            int limit = Math.min(bestHits.size(), MAX_HIT_KEYWORDS);
            contribution.withIndicator("分类依据（" + bestCategory + "）命中关键词: "
                    + String.join("、", bestHits.subList(0, limit)));
        }
        return contribution;
    }

    @Override
    public boolean isEnabled() {
        return pluginService.isPluginEnabled(getName());
    }
}

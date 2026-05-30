package com.mailsystem.plugin;

import com.mailsystem.entity.Mail;
import com.mailsystem.mapper.MailMapper;
import com.mailsystem.service.PluginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 智能分类插件（异步任务）
 * 根据邮件内容自动分类标签
 */
@Component
public class CategoryPlugin implements PluginInterface {

    @Autowired
    private PluginService pluginService;

    @Autowired
    private MailMapper mailMapper;

    /** 分类规则: 标签名 -> 关键词 */
    private static final String[][] CATEGORY_RULES = {
            {"工作", "工作|项目|任务|会议|报告|需求|上线|发布|开发|测试|部署"},
            {"个人", "朋友|家人|聚会|生日|旅游|周末|吃饭|活动"},
            {"财务", "发票|报销|付款|账单|银行|工资|理财|投资|订单|支付"},
            {"通知", "通知|公告|提醒|系统|自动|周报|日报|月报"},
            {"安全", "密码|安全|验证|登录|权限|认证|token|账号"},
            {"社交", "关注|评论|点赞|分享|好友|邀请|注册"},
            {"教育", "课程|学习|培训|考试|证书|毕业|论文|实训"},
            {"广告", "促销|优惠|折扣|免费|限时|活动|推广|特价"}
    };

    @Override
    public String getName() {
        return "categoryClassifier";
    }

    @Override
    @Async
    public void process(Mail mail) {
        String category = classify(mail);
        if (category != null) {
            mailMapper.updateCategory(mail.getId(), category);
        }
    }

    @Override
    public boolean isEnabled() {
        return pluginService.isPluginEnabled("categoryClassifier");
    }

    /**
     * 基于关键词匹配的分类算法
     */
    private String classify(Mail mail) {
        String content = (mail.getSubject() + " " + (mail.getBody() != null ? mail.getBody() : "")).toLowerCase();

        String bestCategory = "其他";
        int bestScore = 0;

        for (String[] rule : CATEGORY_RULES) {
            String label = rule[0];
            String[] keywords = rule[1].split("\\|");

            int score = 0;
            for (String kw : keywords) {
                if (content.contains(kw.toLowerCase())) {
                    score++;
                }
            }

            if (score > bestScore) {
                bestScore = score;
                bestCategory = label;
            }
        }

        return bestScore > 0 ? bestCategory : "其他";
    }
}

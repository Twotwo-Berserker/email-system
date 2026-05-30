package com.mailsystem.plugin;

import com.mailsystem.entity.Mail;
import com.mailsystem.mapper.MailMapper;
import com.mailsystem.service.PluginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 邮件优先级排序插件
 * 根据邮件内容重要性自动评分
 * 评分维度: 关键词、邮件长度、是否有附件、收件人数量
 */
@Component
public class PrioritySortPlugin implements PluginInterface {

    @Autowired
    private PluginService pluginService;

    @Autowired
    private MailMapper mailMapper;

    /** 高优先级关键词（紧急/重要） */
    private static final String[] HIGH_PRIORITY_KEYWORDS = {
            "紧急", "重要", "请尽快", "截止日期", "deadline",
            "会议", "审批", "确认", "urgent", "important",
            "asap", "请回复", "需要处理", "立即"
    };

    /** 低优先级关键词 */
    private static final String[] LOW_PRIORITY_KEYWORDS = {
            "广告", "通知", "newsletter", "周报", "日报",
            "fyi", "仅供参考", "无需回复", "自动发送"
    };

    @Override
    public String getName() {
        return "prioritySort";
    }

    @Override
    public void process(Mail mail) {
        int score = 50; // 基础分

        // 1. 关键词匹配
        String content = (mail.getSubject() + " " + (mail.getBody() != null ? mail.getBody() : "")).toLowerCase();
        for (String kw : HIGH_PRIORITY_KEYWORDS) {
            if (content.contains(kw.toLowerCase())) {
                score += 8;
            }
        }
        for (String kw : LOW_PRIORITY_KEYWORDS) {
            if (content.contains(kw.toLowerCase())) {
                score -= 5;
            }
        }

        // 2. 正文长度加权（适中长度=更可能是重要邮件）
        if (mail.getBody() != null) {
            int len = mail.getBody().length();
            if (len > 200 && len < 5000) score += 10;
            if (len > 5000) score -= 5; // 太长可能是系统邮件
        }

        // 3. 主题长度（过短可能是垃圾，正常长度加分）
        if (mail.getSubject() != null) {
            int subjLen = mail.getSubject().length();
            if (subjLen >= 5 && subjLen <= 100) score += 5;
        }

        // 4. 抄送人数影响（抄送人多=可能不太针对你）
        if (mail.getCcIds() != null && !mail.getCcIds().isEmpty()) {
            int ccCount = mail.getCcIds().split(",").length;
            if (ccCount > 5) score -= 10;
            if (ccCount > 10) score -= 15;
        }

        // 限制分数范围 [0, 100]
        score = Math.max(0, Math.min(100, score));

        mailMapper.updatePriority(mail.getId(), score);
    }

    @Override
    public boolean isEnabled() {
        return pluginService.isPluginEnabled("prioritySort");
    }
}

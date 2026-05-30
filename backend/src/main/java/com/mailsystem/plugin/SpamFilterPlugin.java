package com.mailsystem.plugin;

import com.mailsystem.entity.Mail;
import com.mailsystem.mapper.MailMapper;
import com.mailsystem.service.PluginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 垃圾邮件识别插件
 * 基于关键词匹配 + 规则引擎
 * 邮件到达时立即执行
 */
@Component
public class SpamFilterPlugin implements PluginInterface {

    @Autowired
    private PluginService pluginService;

    @Autowired
    private MailMapper mailMapper;

    /** 垃圾邮件关键词库 */
    private static final String[] SPAM_KEYWORDS = {
            "免费领取", "点击中奖", "恭喜中奖", "特价优惠", "限时抢购",
            "代办发票", "代办证件", "高额回报", "快速赚钱", "在家兼职",
            "免费试用", "立即点击", "不回复将", "账号异常", "系统升级",
            "spam", "lottery", "win money", "click here", "free offer",
            "urgent", "act now", "limited time", "special promotion"
    };

    /** 垃圾邮件发件人特征 */
    private static final String[] SUSPICIOUS_DOMAINS = {
            "spam", "bulk", "marketing", "offers", "deal", "promo"
    };

    @Override
    public String getName() {
        return "spamFilter";
    }

    @Override
    public void process(Mail mail) {
        int spamScore = 0;

        // 1. 关键词匹配
        String content = (mail.getSubject() + " " + mail.getBody()).toLowerCase();
        for (String keyword : SPAM_KEYWORDS) {
            if (content.contains(keyword.toLowerCase())) {
                spamScore += 10;
            }
        }

        // 2. 发件人域名检测
        String senderEmail = mail.getSenderEmail().toLowerCase();
        for (String domain : SUSPICIOUS_DOMAINS) {
            if (senderEmail.contains(domain)) {
                spamScore += 20;
            }
        }

        // 3. 正文长度异常检测（过短或过长）
        if (mail.getBody() != null) {
            int bodyLen = mail.getBody().length();
            if (bodyLen < 10) spamScore += 5;
            if (bodyLen > 50000) spamScore += 15;
        }

        // 4. 主题异常检测
        if (mail.getSubject() != null) {
            String subject = mail.getSubject();
            // 大量大写字母
            int upperCount = 0;
            for (char c : subject.toCharArray()) {
                if (Character.isUpperCase(c)) upperCount++;
            }
            if (subject.length() > 0 && (double) upperCount / subject.length() > 0.5) {
                spamScore += 10;
            }
            // 过多感叹号
            int exclaimCount = subject.length() - subject.replace("!", "").length();
            if (exclaimCount > 3) spamScore += 10;
        }

        // 达到阈值则标记为垃圾邮件
        if (spamScore >= 30) {
            mailMapper.markSpam(mail.getId(), 1);
        }
    }

    @Override
    public boolean isEnabled() {
        return pluginService.isPluginEnabled("spamFilter");
    }
}

package com.mailsystem.plugin;

import com.mailsystem.entity.Mail;
import com.mailsystem.service.PluginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 垃圾邮件识别插件（规则兜底）
 *
 * <h3>它现在的定位</h3>
 * <p>
 * 判垃圾的主路径已经交给 LLM。本插件只在"没有可用 Key / 调用失败 / 熔断中"
 * 时兜底，因此它<b>不需要</b>很聪明 —— 需要的是很快、很确定、且
 * <b>绝不抛异常</b>。它的结论会以 {@code source='RULE'} 落库，
 * 管理端的反馈统计能按 source 分组，从而看出兜底结论被用户纠正的比例。
 * </p>
 *
 * <h3>规则判定为什么必须给出依据</h3>
 * <p>
 * 改造前本插件只累加分数、不记录是什么命中的。用户在界面上看到一个
 * "垃圾"标记却没有任何解释时，唯一能做的就是关掉这个功能。
 * 现在每一次加分都同时记一条 {@code indicator}，与 LLM 的
 * {@code indicators} 走同一套展示。
 * </p>
 */
@Component
@Order(10)
public class SpamFilterPlugin implements PluginInterface {

    /** 命中一个垃圾关键词的加分 */
    private static final int KEYWORD_SCORE = 10;

    /** 发件人域名可疑的加分 */
    private static final int SUSPICIOUS_DOMAIN_SCORE = 20;

    /** 正文过短的加分 */
    private static final int SHORT_BODY_SCORE = 5;

    /** 正文过长的加分 */
    private static final int LONG_BODY_SCORE = 15;

    /** 主题大写比例异常的加分 */
    private static final int UPPERCASE_SCORE = 10;

    /** 主题感叹号过多的加分 */
    private static final int EXCLAMATION_SCORE = 10;

    /** 判定为垃圾的分数阈值 */
    private static final int SPAM_THRESHOLD = 30;

    /** 正文长度阈值 */
    private static final int SHORT_BODY_CHARS = 10;
    private static final int LONG_BODY_CHARS = 50_000;

    /** 最多记录几条依据（与 AnalysisResult 的上限一致） */
    private static final int MAX_INDICATORS = 6;

    @Autowired
    private PluginService pluginService;

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
    public RuleContribution contribute(Mail mail) {
        int spamScore = 0;
        List<String> indicators = new ArrayList<>();

        String subject = mail.getSubject() == null ? "" : mail.getSubject();
        String body = mail.getBody() == null ? "" : mail.getBody();
        String content = (subject + " " + body).toLowerCase();

        // 1. 关键词匹配
        List<String> hitKeywords = new ArrayList<>();
        for (String keyword : SPAM_KEYWORDS) {
            if (content.contains(keyword.toLowerCase())) {
                spamScore += KEYWORD_SCORE;
                if (hitKeywords.size() < MAX_INDICATORS) {
                    hitKeywords.add(keyword);
                }
            }
        }
        if (!hitKeywords.isEmpty()) {
            indicators.add("命中垃圾关键词: " + String.join("、", hitKeywords));
        }

        // 2. 发件人域名检测
        String senderEmail = mail.getSenderEmail() == null
                ? "" : mail.getSenderEmail().toLowerCase();
        for (String domain : SUSPICIOUS_DOMAINS) {
            if (senderEmail.contains(domain)) {
                spamScore += SUSPICIOUS_DOMAIN_SCORE;
                indicators.add("发件人地址含可疑字样: " + domain);
                break;
            }
        }

        // 3. 正文长度异常检测
        int bodyLen = body.length();
        if (bodyLen < SHORT_BODY_CHARS) {
            spamScore += SHORT_BODY_SCORE;
            indicators.add("正文过短（不足 " + SHORT_BODY_CHARS + " 字）");
        }
        if (bodyLen > LONG_BODY_CHARS) {
            spamScore += LONG_BODY_SCORE;
            indicators.add("正文异常长");
        }

        // 4. 主题异常检测
        if (!subject.isEmpty()) {
            int upperCount = 0;
            for (char c : subject.toCharArray()) {
                if (Character.isUpperCase(c)) {
                    upperCount++;
                }
            }
            if ((double) upperCount / subject.length() > 0.5) {
                spamScore += UPPERCASE_SCORE;
                indicators.add("主题中大写字母占比过高");
            }
            int exclaimCount = subject.length() - subject.replace("!", "").length();
            if (exclaimCount > 3) {
                spamScore += EXCLAMATION_SCORE;
                indicators.add("主题中感叹号过多");
            }
        }

        spamScore = Math.max(0, Math.min(100, spamScore));
        RuleContribution contribution = RuleContribution.spam(
                spamScore >= SPAM_THRESHOLD ? 1 : 0, spamScore);
        for (String indicator : indicators) {
            contribution.withIndicator(indicator);
        }
        return contribution;
    }

    @Override
    public boolean isEnabled() {
        return pluginService.isPluginEnabled(getName());
    }
}

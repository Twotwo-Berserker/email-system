package com.mailsystem.plugin;

import com.mailsystem.entity.Mail;
import com.mailsystem.service.PluginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 邮件优先级评分插件（规则兜底）
 *
 * <h3>为什么抄送人数量会影响优先级</h3>
 * <p>
 * 这个启发式来自原实现：收件人越多，越可能是一封群发通知而非点名找你的事。
 * 它对"群发"有效，但会误伤"项目组邮件"这类正常场景，
 * 因此扣分幅度刻意保持温和（最多 -25），不会把一封工作邮件压到低优先级。
 * </p>
 * <p>
 * 主路径已交给 LLM（它能理解"请于周五前回复"这类语义），本插件只在
 * 无可用 Key 或调用失败时兜底。
 * </p>
 */
@Component
@Order(40)
public class PrioritySortPlugin implements PluginInterface {

    /** 基础分：一封普通邮件的起点 */
    private static final int BASE_SCORE = 50;

    /** 命中一个高优先级关键词的加分 */
    private static final int HIGH_KEYWORD_SCORE = 8;

    /** 命中一个低优先级关键词的扣分 */
    private static final int LOW_KEYWORD_SCORE = -5;

    /** 正文长度适中的加分 */
    private static final int GOOD_LENGTH_SCORE = 10;

    /** 正文过长的扣分 */
    private static final int TOO_LONG_SCORE = -5;

    /** 主题长度正常的加分 */
    private static final int HEALTHY_SUBJECT_SCORE = 5;

    /** 抄送人数过多的扣分 */
    private static final int MANY_CC_SCORE = -10;
    private static final int TOO_MANY_CC_SCORE = -15;

    private static final int MANY_CC_COUNT = 5;
    private static final int TOO_MANY_CC_COUNT = 10;

    private static final int GOOD_BODY_MIN = 200;
    private static final int GOOD_BODY_MAX = 5000;
    private static final int TOO_LONG_BODY = 5000;

    private static final int SUBJECT_MIN = 5;
    private static final int SUBJECT_MAX = 100;

    /** 最多记录几条依据 */
    private static final int MAX_INDICATORS = 4;

    @Autowired
    private PluginService pluginService;

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
    public RuleContribution contribute(Mail mail) {
        int score = BASE_SCORE;
        List<String> indicators = new ArrayList<>();

        String subject = mail.getSubject() == null ? "" : mail.getSubject();
        String body = mail.getBody() == null ? "" : mail.getBody();
        String content = (subject + " " + body).toLowerCase();

        // 1. 关键词匹配
        List<String> highHits = new ArrayList<>();
        for (String keyword : HIGH_PRIORITY_KEYWORDS) {
            if (content.contains(keyword.toLowerCase())) {
                score += HIGH_KEYWORD_SCORE;
                if (highHits.size() < MAX_INDICATORS) {
                    highHits.add(keyword);
                }
            }
        }
        if (!highHits.isEmpty()) {
            indicators.add("含高优先级关键词: " + String.join("、", highHits));
        }

        List<String> lowHits = new ArrayList<>();
        for (String keyword : LOW_PRIORITY_KEYWORDS) {
            if (content.contains(keyword.toLowerCase())) {
                score += LOW_KEYWORD_SCORE;
                if (lowHits.size() < MAX_INDICATORS) {
                    lowHits.add(keyword);
                }
            }
        }
        if (!lowHits.isEmpty()) {
            indicators.add("含低优先级关键词: " + String.join("、", lowHits));
        }

        // 2. 正文长度加权（适中长度=更可能是重要邮件）
        int len = body.length();
        if (len > GOOD_BODY_MIN && len < GOOD_BODY_MAX) {
            score += GOOD_LENGTH_SCORE;
        }
        if (len > TOO_LONG_BODY) {
            score += TOO_LONG_SCORE;
        }

        // 3. 主题长度（过短可能是垃圾，正常长度加分）
        if (subject.length() >= SUBJECT_MIN && subject.length() <= SUBJECT_MAX) {
            score += HEALTHY_SUBJECT_SCORE;
        }

        // 4. 抄送人数影响（抄送人多=可能不太针对你）
        if (mail.getCcIds() != null && !mail.getCcIds().isEmpty()) {
            int ccCount = mail.getCcIds().split(",").length;
            if (ccCount > TOO_MANY_CC_COUNT) {
                score += TOO_MANY_CC_SCORE;
                indicators.add("抄送人数很多（" + ccCount + " 人）");
            } else if (ccCount > MANY_CC_COUNT) {
                score += MANY_CC_SCORE;
                indicators.add("抄送人数较多（" + ccCount + " 人）");
            }
        }

        score = Math.max(0, Math.min(100, score));

        RuleContribution contribution = RuleContribution.priority(score);
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

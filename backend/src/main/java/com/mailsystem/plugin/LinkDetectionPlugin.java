package com.mailsystem.plugin;

import com.mailsystem.entity.Mail;
import com.mailsystem.service.PluginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 恶意链接 / 伪造发件人检测插件
 *
 * <h3>改造前它是彻底的死代码</h3>
 * <p>
 * 原实现把检测到的威胁收集进局部变量 {@code threats}，然后只在
 * {@code !threats.isEmpty()} 的分支里拼了个字符串 <b>又扔掉</b>，
 * 末尾注释写着"与摘要插件协同使用"—— 但那个协同从未存在，
 * 该类当时既没注入 {@code MailMapper} 也无法影响任何输出。
 * 换句话说：这个"检测插件"检测到了什么，系统内外没有任何人知道。
 * </p>
 * <p>
 * 现在威胁列表通过 {@link RuleContribution#withIndicator} 进入分析结论，
 * 会展示在邮件详情的"判定依据"里，并参与风险等级判定。
 * </p>
 *
 * <h3>为什么它能独立给出 HIGH 风险</h3>
 * <p>
 * 垃圾程度（spam）与安全风险（risk）是两件事：一封正经的银行通知
 * 不是垃圾，但如果它里面的链接指向 IP 地址直连，风险就是高的。
 * 因此本插件直接表态风险等级，而不是把这个判断折算进垃圾分数 ——
 * 折算会让"高风险但非垃圾"的邮件在界面上表现为低风险。
 * </p>
 */
@Component
@Order(20)
public class LinkDetectionPlugin implements PluginInterface {

    /** URL 提取正则 */
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://[\\w\\-]+(\\.[\\w\\-]+)+([\\w\\-.,@?^=%&:/~+#]*[\\w\\-@?^=%&/~+#])?)",
            Pattern.CASE_INSENSITIVE
    );

    /** IP地址URL模式（高度可疑） */
    private static final Pattern IP_URL_PATTERN = Pattern.compile(
            "https?://\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}",
            Pattern.CASE_INSENSITIVE
    );

    /** 短链接域名（常用于恶意攻击） */
    private static final String[] SHORT_LINK_DOMAINS = {
            "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly",
            "is.gd", "buff.ly", "adf.ly", "shorte.st", "bc.vc"
    };

    /** 可疑 TLD */
    private static final String[] SUSPICIOUS_TLDS = {
            ".tk", ".ml", ".ga", ".cf", ".xyz", ".top", ".club", ".work"
    };

    /** 已知邮件域名（伪造发件人检测用） */
    private static final String[] KNOWN_DOMAINS = {
            "gmail.com", "qq.com", "163.com", "outlook.com", "yahoo.com"
    };

    /** 每个 URL 只记一条依据，避免一封满是链接的邮件刷屏 */
    private static final int MAX_URL_INDICATORS = 3;

    /** 伪造发件人依据的条数上限 */
    private static final int MAX_FORGERY_INDICATORS = 2;

    /** 出现 IP 直连或短链接时的风险等级 */
    private static final String RISK_ON_LINK_THREAT = "HIGH";

    /** 仅命中可疑 TLD / 伪造发件人时的风险等级 */
    private static final String RISK_ON_WEAK_SIGNAL = "MEDIUM";

    /** 正文里最长扫描的字符数：一封 500KB 的邮件不必全量正则匹配 */
    private static final int MAX_SCAN_CHARS = 100_000;

    @Autowired
    private PluginService pluginService;

    @Override
    public String getName() {
        return "linkDetection";
    }

    @Override
    public RuleContribution contribute(Mail mail) {
        String body = mail.getBody();
        if (body == null || body.isEmpty()) {
            return new RuleContribution();
        }
        if (body.length() > MAX_SCAN_CHARS) {
            body = body.substring(0, MAX_SCAN_CHARS);
        }

        RuleContribution contribution = new RuleContribution();
        List<String> weakSignals = new ArrayList<>();
        int urlIndicators = 0;

        // 1. 逐条提取 URL 并按可疑特征分类
        Matcher matcher = URL_PATTERN.matcher(body);
        while (matcher.find() && urlIndicators < MAX_URL_INDICATORS) {
            String url = matcher.group();
            String lower = url.toLowerCase();

            if (IP_URL_PATTERN.matcher(lower).find()) {
                contribution.withIndicator("检测到 IP 地址直连: " + shorten(url));
                contribution.withRisk(RISK_ON_LINK_THREAT);
                urlIndicators++;
                continue;
            }

            boolean shortLink = containsAny(lower, SHORT_LINK_DOMAINS);
            boolean badTld = containsAny(lower, SUSPICIOUS_TLDS);
            if (shortLink) {
                contribution.withIndicator("检测到短链接（真实目标不可见）: " + shorten(url));
                contribution.withRisk(RISK_ON_LINK_THREAT);
                urlIndicators++;
            } else if (badTld) {
                weakSignals.add("检测到可疑顶级域名: " + shorten(url));
                contribution.withRisk(RISK_ON_WEAK_SIGNAL);
            }
        }

        // 2. 伪造发件人检测：正文声称的发件人与真实发件人不一致
        detectSenderForgery(mail, body, contribution, weakSignals);

        for (String signal : weakSignals) {
            contribution.withIndicator(signal);
        }
        return contribution;
    }

    /**
     * 检测正文中声称的发件人与真实发件人不一致。
     * <p>
     * 只在正文里出现了 "from:" / "发件人:" 这类字样时才检查 ——
     * 否则邮件里随便提到的一个邮箱地址都会被当成"伪造"，噪声太大。
     * </p>
     */
    private void detectSenderForgery(Mail mail, String body, RuleContribution contribution,
                                     List<String> weakSignals) {
        String senderEmail = mail.getSenderEmail() == null
                ? "" : mail.getSenderEmail().toLowerCase();
        if (senderEmail.isEmpty()) {
            return;
        }
        String lower = body.toLowerCase();
        if (!lower.contains("from:") && !lower.contains("发件人:") && !lower.contains("sender:")) {
            return;
        }

        Matcher emailMatcher = Pattern.compile("[\\w.-]+@[\\w.-]+\\.\\w+").matcher(lower);
        int found = 0;
        while (emailMatcher.find() && found < MAX_FORGERY_INDICATORS) {
            String claimed = emailMatcher.group();
            if (claimed.equals(senderEmail) || !isKnownDomain(claimed)) {
                continue;
            }
            weakSignals.add("正文声称发件人为 " + claimed + "，实际为 " + mail.getSenderEmail());
            contribution.withRisk(RISK_ON_WEAK_SIGNAL);
            found++;
        }
    }

    @Override
    public boolean isEnabled() {
        return pluginService.isPluginEnabled(getName());
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** 依据里不必出现完整 URL：长链接会把界面撑破，也容易看错域名 */
    private static String shorten(String url) {
        return url.length() <= 80 ? url : url.substring(0, 80) + "…";
    }

    private static boolean isKnownDomain(String email) {
        for (String domain : KNOWN_DOMAINS) {
            if (email.endsWith("@" + domain)) {
                return true;
            }
        }
        return false;
    }
}

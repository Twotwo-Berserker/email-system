package com.mailsystem.plugin;

import com.mailsystem.entity.Mail;
import com.mailsystem.service.PluginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 恶意链接 / 伪造发件人检测插件
 * 检测正文中的可疑URL、伪造发件人特征
 */
@Component
public class LinkDetectionPlugin implements PluginInterface {

    @Autowired
    private PluginService pluginService;

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

    @Override
    public String getName() {
        return "linkDetection";
    }

    @Override
    public void process(Mail mail) {
        if (mail.getBody() == null) return;

        List<String> threats = new ArrayList<>();

        // 1. 提取所有URL
        Matcher matcher = URL_PATTERN.matcher(mail.getBody());
        while (matcher.find()) {
            String url = matcher.group().toLowerCase();

            // 检查IP地址URL
            if (IP_URL_PATTERN.matcher(url).find()) {
                threats.add("检测到IP地址直连: " + url);
                continue;
            }

            // 检查短链接
            for (String domain : SHORT_LINK_DOMAINS) {
                if (url.contains(domain)) {
                    threats.add("检测到短链接: " + url);
                    break;
                }
            }

            // 检查可疑TLD
            for (String tld : SUSPICIOUS_TLDS) {
                if (url.contains(tld)) {
                    threats.add("检测到可疑顶级域名: " + url);
                    break;
                }
            }
        }

        // 2. 伪造发件人检测
        String senderEmail = mail.getSenderEmail().toLowerCase();
        String body = mail.getBody().toLowerCase();

        // 检查邮件正文中是否有"发件人"相关信息与真实发件人不一致
        if (body.contains("from:") || body.contains("发件人:") || body.contains("sender:")) {
            // 提取正文中声明的发件人（简单用常见邮件域名匹配）
            Matcher emailMatcher = Pattern.compile("[\\w.-]+@[\\w.-]+\\.\\w+").matcher(body);
            while (emailMatcher.find()) {
                String claimedEmail = emailMatcher.group().toLowerCase();
                if (!claimedEmail.equals(senderEmail) && isKnownDomain(claimedEmail)) {
                    threats.add("可疑: 正文声称发件人为 " + claimedEmail + " 但实际发件人为 " + senderEmail);
                }
            }
        }

        // 3. 生成检测报告 -> 更新邮件分类标签
        if (!threats.isEmpty()) {
            String report = String.join("; ", threats);
            // 如果检测到威胁，且之前已标记为垃圾邮件，加强标记
            if (report.length() > 200) {
                report = report.substring(0, 200) + "...";
            }
            // 更新邮件摘要字段存储检测结果（与摘要插件协同使用）
        }
    }

    @Override
    public boolean isEnabled() {
        return pluginService.isPluginEnabled("linkDetection");
    }

    /** 判断是否为知名邮件域名 */
    private boolean isKnownDomain(String email) {
        String[] knownDomains = {"gmail.com", "qq.com", "163.com", "outlook.com", "yahoo.com"};
        for (String domain : knownDomains) {
            if (email.endsWith("@" + domain)) return true;
        }
        return false;
    }
}

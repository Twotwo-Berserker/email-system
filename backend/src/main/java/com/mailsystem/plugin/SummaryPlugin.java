package com.mailsystem.plugin;

import com.mailsystem.entity.Mail;
import com.mailsystem.service.PluginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 摘要生成插件（规则兜底）
 *
 * <h3>改造前它和 LlmPlugin 在抢同一列</h3>
 * <p>
 * 两者都标了 {@code @Async}、都写 {@code mail.summary}，且没有任何
 * {@code @Order} 约束谁先谁后 —— 最终摘要到底是"抽句算法"还是"LLM 摘要"
 * 取决于两个线程谁先提交事务。用户看到的结果因此是不可复现的。
 * </p>
 * <p>
 * 现在摘要只在 {@code RuleAnalyzer} 里被<b>串行</b>取用：LLM 成功时用 LLM 的，
 * LLM 不可用时才用本插件抽句的结果。竞态从"靠运气"变成了"靠代码顺序"。
 * </p>
 * <p>
 * 本插件保留抽句式摘要（而非简单截断前 N 个字）是有意的：兜底结论虽然质量
 * 不如 LLM，但"过滤掉问候语、优先取中间句"比"截前 200 字"有用得多 ——
 * 后者在一封以"您好，最近怎么样"开头的邮件上几乎总是没有信息量。
 * </p>
 */
@Component
@Order(50)
public class SummaryPlugin implements PluginInterface {

    private static final int SUMMARY_MAX_LENGTH = 200;
    private static final int MIN_SENTENCE_LENGTH = 10;

    /** 无正文时的占位摘要 —— 与"分析尚未完成"区分开 */
    private static final String EMPTY_BODY_SUMMARY = "(无正文)";

    /** 常见的问候语/开场白模式，这些句子不适合作为摘要 */
    private static final Pattern[] GREETING_PATTERNS = {
            Pattern.compile("^(你好|您好|嗨|哈喽|hello|hi|hey|dear|早上好|下午好|晚上好|各位|大家好)[，,!.！\\s]*.*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(好久不见|好久没联系|最近怎么样|近来可好|见信好|展信佳).*$"),
            Pattern.compile("^(我是|我叫|这是|this is|my name is).*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(感谢|谢谢|多谢|thank).*$", Pattern.CASE_INSENSITIVE),
    };

    /** 常见的结束语/签名模式 */
    private static final Pattern[] CLOSING_PATTERNS = {
            Pattern.compile("^(祝好|此致|敬礼|顺祝|安好|保重|再见|best regards|sincerely|yours|cheers|thanks|thank you).*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(发件人|发送自|sent from|获取|outlook|iPhone|iPad|Android).*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^--\\s*$"),
            Pattern.compile("^_{2,}.*$"),
            Pattern.compile("^-{2,}.*$"),
    };

    @Autowired
    private PluginService pluginService;

    @Override
    public String getName() {
        return "summaryGenerator";
    }

    @Override
    public RuleContribution contribute(Mail mail) {
        return RuleContribution.summary(generateSummary(mail));
    }

    @Override
    public boolean isEnabled() {
        return pluginService.isPluginEnabled(getName());
    }

    /**
     * 智能摘要生成算法：
     * 1. 去除HTML标签和多余空白
     * 2. 按句子拆分
     * 3. 过滤掉问候语、结束语等无信息量的句子
     * 4. 优先选取中间段落的核心句子
     * 5. 控制在200字符以内，在句子边界截断
     */
    private String generateSummary(Mail mail) {
        String body = mail.getBody();
        if (body == null || body.isEmpty()) {
            return EMPTY_BODY_SUMMARY;
        }

        // 1. 去除HTML标签与多余空白
        String plainText = body.replaceAll("<[^>]+>", "");
        plainText = plainText.replaceAll("\\s+", " ").trim();
        if (plainText.isEmpty()) {
            return EMPTY_BODY_SUMMARY;
        }

        // 2. 按句子拆分（中英文句子分隔符）
        String[] rawSentences = plainText.split("(?<=[。！？.!?\\n])\\s*");

        // 3. 过滤和清理句子
        List<String> goodSentences = new ArrayList<>();
        for (String s : rawSentences) {
            String cleaned = s.trim();
            if (cleaned.isEmpty() || cleaned.length() < MIN_SENTENCE_LENGTH) {
                continue;
            }
            if (isGreeting(cleaned) || isClosing(cleaned)) {
                continue;
            }
            goodSentences.add(cleaned);
        }

        // 4. 所有句子都被过滤掉时退回截断原文
        if (goodSentences.isEmpty()) {
            return plainText.length() > SUMMARY_MAX_LENGTH
                    ? plainText.substring(0, SUMMARY_MAX_LENGTH).trim() + "…"
                    : plainText;
        }

        // 5. 构建摘要：优先取中间的句子（通常包含核心信息），兼顾开头
        StringBuilder summary = new StringBuilder();
        for (String sentence : selectKeySentences(goodSentences)) {
            if (summary.length() + sentence.length() > SUMMARY_MAX_LENGTH) {
                // 尽量在最后一个完整句子处截断
                break;
            }
            if (summary.length() > 0) {
                summary.append(" ");
            }
            summary.append(sentence);
        }

        // 拼接后为空（第一句就超长）时退回截断第一个好句子
        if (summary.length() == 0) {
            String first = goodSentences.get(0);
            summary.append(first.length() > SUMMARY_MAX_LENGTH
                    ? first.substring(0, SUMMARY_MAX_LENGTH).trim() + "…"
                    : first);
        }

        String result = summary.toString().trim();
        if (result.length() < plainText.length() && !result.endsWith("…")) {
            result += "…";
        }
        return result.isEmpty() ? EMPTY_BODY_SUMMARY : result;
    }

    /**
     * 从句子列表中选取关键句子。
     * <p>策略：跳过开头 1 句（常为问候），优先取中间偏前的核心内容。</p>
     */
    private List<String> selectKeySentences(List<String> sentences) {
        List<String> selected = new ArrayList<>();
        int size = sentences.size();

        if (size <= 3) {
            // 句子很少，全部保留
            selected.addAll(sentences);
            return selected;
        }

        int startIdx = 1;
        int endIdx = Math.min(size, startIdx + 4); // 最多取4句
        for (int i = startIdx; i < endIdx; i++) {
            selected.add(sentences.get(i));
        }
        return selected;
    }

    private boolean isGreeting(String sentence) {
        for (Pattern p : GREETING_PATTERNS) {
            if (p.matcher(sentence).matches()) {
                return true;
            }
        }
        return false;
    }

    private boolean isClosing(String sentence) {
        for (Pattern p : CLOSING_PATTERNS) {
            if (p.matcher(sentence).matches()) {
                return true;
            }
        }
        return false;
    }
}

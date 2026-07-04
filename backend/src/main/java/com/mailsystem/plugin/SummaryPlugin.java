package com.mailsystem.plugin;

import com.mailsystem.entity.Mail;
import com.mailsystem.mapper.MailMapper;
import com.mailsystem.service.PluginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 智能摘要生成插件（异步任务）
 * 从邮件正文中提取关键句子生成摘要，而非简单截取前N个字符
 */
@Component
public class SummaryPlugin implements PluginInterface {

    @Autowired
    private PluginService pluginService;

    @Autowired
    private MailMapper mailMapper;

    private static final int SUMMARY_MAX_LENGTH = 200;
    private static final int MIN_SENTENCE_LENGTH = 10;

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

    @Override
    public String getName() {
        return "summaryGenerator";
    }

    /**
     * 异步生成摘要
     * 如果 LLM 大模型已启用，则跳过规则摘要，由 LlmPlugin 生成更高质量的摘要
     */
    @Override
    @Async
    public void process(Mail mail) {
        // LLM 已启用且配置了密钥时，由 LlmPlugin 负责生成摘要，本插件让步
        if (isLlmAvailable()) {
            return;
        }
        String summary = generateSummary(mail);
        if (summary != null) {
            mailMapper.updateSummary(mail.getId(), summary);
        }
    }

    /**
     * 检查 LLM 大模型是否可用
     */
    private boolean isLlmAvailable() {
        try {
            Map<String, Object> llmConfig = pluginService.getLlmConfig();
            if (llmConfig == null) return false;
            Object enabled = llmConfig.get("enabled");
            boolean isEnabled = (enabled instanceof Number) && ((Number) enabled).intValue() == 1;
            if (!isEnabled) return false;
            String apiKey = (String) llmConfig.get("apiKey");
            return apiKey != null && !apiKey.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isEnabled() {
        return pluginService.isPluginEnabled("summaryGenerator");
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
        if (mail.getBody() == null || mail.getBody().isEmpty()) {
            return "(无正文)";
        }

        // 1. 去除HTML标签
        String plainText = mail.getBody().replaceAll("<[^>]+>", "");
        // 合并多个空白字符
        plainText = plainText.replaceAll("\\s+", " ").trim();

        if (plainText.isEmpty()) {
            return "(无正文)";
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
            // 跳过问候语
            if (isGreeting(cleaned)) {
                continue;
            }
            // 跳过结束语
            if (isClosing(cleaned)) {
                continue;
            }
            goodSentences.add(cleaned);
        }

        // 4. 选取摘要句子
        if (goodSentences.isEmpty()) {
            // 退化：所有句子都被过滤了，取原始文本的前200字符
            String fallback = plainText.length() > SUMMARY_MAX_LENGTH
                    ? plainText.substring(0, SUMMARY_MAX_LENGTH).trim() + "…"
                    : plainText;
            return fallback;
        }

        // 5. 构建摘要：优先取中间的句子（通常包含核心信息），兼顾开头
        List<String> selectedSentences = selectKeySentences(goodSentences);

        // 6. 拼接摘要，控制在 SUMMARY_MAX_LENGTH 以内
        StringBuilder summary = new StringBuilder();
        for (String sentence : selectedSentences) {
            if (summary.length() + sentence.length() > SUMMARY_MAX_LENGTH) {
                // 尽量在最后一个完整句子处截断
                break;
            }
            if (summary.length() > 0) {
                summary.append(" ");
            }
            summary.append(sentence);
        }

        // 如果拼接后为空，取第一个好句子截断
        if (summary.length() == 0) {
            String first = goodSentences.get(0);
            summary.append(first.length() > SUMMARY_MAX_LENGTH
                    ? first.substring(0, SUMMARY_MAX_LENGTH).trim() + "…"
                    : first);
        }

        // 如果原文比摘要长，加上省略号
        String result = summary.toString().trim();
        if (result.length() < plainText.length() && !result.endsWith("…")) {
            result += "…";
        }

        return result.isEmpty() ? "(无正文)" : result;
    }

    /**
     * 从句子列表中选取关键句子
     * 策略：跳过开头1-2句（常为问候），优先取中间部分的核心内容
     */
    private List<String> selectKeySentences(List<String> sentences) {
        List<String> selected = new ArrayList<>();
        int size = sentences.size();

        if (size <= 3) {
            // 句子很少，全部保留
            selected.addAll(sentences);
            return selected;
        }

        // 跳过第1句（很可能是问候/开场），从第2句开始取
        // 对于较长的邮件，取中间偏前的句子（通常包含核心信息）
        int startIdx = Math.min(1, size - 1);
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

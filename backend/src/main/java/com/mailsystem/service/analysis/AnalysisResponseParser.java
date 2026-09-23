package com.mailsystem.service.analysis;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 把 LLM 的原始输出解析成 {@link AnalysisResult}。
 *
 * <h3>为什么需要"从一段话里抠出 JSON"</h3>
 * <p>
 * 即使 Prompt 里写了"只输出 JSON"、也传了 {@code response_format}，真实上游
 * 依然会给出这些东西：
 * </p>
 * <ul>
 *   <li>{@code ```json\n{...}\n```} —— Markdown 代码块包裹</li>
 *   <li>{@code 好的，以下是分析结果：\n{...}} —— 中文前言</li>
 *   <li>{@code {...}\n\n希望对你有帮助} —— 中文后记</li>
 *   <li>被 {@code max_tokens} 截断的半截 JSON</li>
 * </ul>
 * <p>
 * 前三种都能靠"剥代码块 + 取第一个 { 到最后一个 }"救回来，第四种救不回来
 * （此时返回 null，由调用方决定重试还是走规则兜底）。
 * </p>
 *
 * <h3>取第一个 { 到最后一个 } 而不是做括号配平</h3>
 * <p>
 * 配平在"正文里本身含 JSON 示例"时会抠错范围；而取最外层大范围配合
 * Jackson 的解析失败回退，行为更可预测：要么正好是那个对象，要么解析失败。
 * 宽松抠取 + 严格解析，比严格抠取 + 宽松解析安全。
 * </p>
 *
 * <h3>为什么是 public</h3>
 * <p>
 * 编排方（{@code MailAnalysisServiceImpl}）按项目约定在
 * {@code com.mailsystem.service.impl} 下，与本包不同名。本类只服务于分析管线。
 * </p>
 */
public final class AnalysisResponseParser {

    /** 代码块围栏：允许 ```json / ```JSON / ``` 三种写法 */
    private static final String FENCE_START = "```";

    private AnalysisResponseParser() {
    }

    /**
     * 解析。
     *
     * @return 解析成功的结论；拿不到 JSON 对象时返回 null
     */
    public static AnalysisResult parse(String raw) {
        String json = extractJsonObject(raw);
        if (json == null) {
            return null;
        }
        Map<String, Object> map = JsonSupport.parseObject(json);
        if (map == null) {
            return null;
        }

        AnalysisResult result = AnalysisResult.neutral();
        result.setSpam(readBoolean(map, "spam", false));
        result.setSpamScore(readInt(map, "spam_score", 0));
        result.setPriority(readInt(map, "priority", 50));
        result.setRisk(readString(map, "risk", AnalysisResult.RISK_MEDIUM));
        result.setCategory(readString(map, "category", AnalysisResult.CATEGORY_OTHER));
        result.setSummary(readString(map, "summary", null));
        result.setConfidence(readDecimal(map, "confidence"));
        result.setIndicators(JsonSupport.toStringList(firstPresent(map, "indicators", "reasons", "evidence")));
        result.setActions(JsonSupport.toStringList(firstPresent(map, "actions", "suggestions", "advice")));
        return result.sanitize();
    }

    /**
     * 从可能夹带前后文的响应里抠出第一个 JSON 对象。
     *
     * @return 形如 {@code {...}} 的字符串；找不到返回 null
     */
    static String extractJsonObject(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }

        // 1. 剥代码块围栏。只剥最外层一对，不改动内部内容
        text = stripFences(text);

        // 2. 取第一个 { 到最后一个 }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private static String stripFences(String text) {
        int first = text.indexOf(FENCE_START);
        if (first < 0) {
            return text;
        }
        int start = first + FENCE_START.length();
        // 跳过语言标注（json / JSON / javascript）
        while (start < text.length() && Character.isLetter(text.charAt(start))) {
            start++;
        }
        int end = text.indexOf(FENCE_START, start);
        if (end < 0) {
            // 只有开头围栏（被截断），取到结尾即可
            return text.substring(start).trim();
        }
        // 代码块之前的前言直接丢弃，块之后的后记也丢弃
        return text.substring(start, end).trim();
    }

    // ==================== 字段读取（全部容错） ====================

    /**
     * 读布尔。
     * <p>
     * 模型可能给 {@code true}、{@code "true"}、{@code 1}、{@code "是"}、
     * {@code "yes"}。只认前三种的实现在遇到中文模型时会静默把所有邮件都判成非垃圾。
     * </p>
     */
    private static boolean readBoolean(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        String s = value.toString().trim().toLowerCase();
        if (s.isEmpty()) {
            return fallback;
        }
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "是".equals(s)
                || "y".equals(s) || "垃圾".equals(s)) {
            return true;
        }
        if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "否".equals(s)
                || "n".equals(s) || "非垃圾".equals(s)) {
            return false;
        }
        // 无法判断时按"不是垃圾"处理：误判为垃圾会让用户漏掉正常邮件，
        // 而漏判只是不显示垃圾标记，代价不对称
        return fallback;
    }

    private static int readInt(Map<String, Object> map, String key, int fallback) {
        Integer value = readIntOrNull(map.get(key));
        return value == null ? fallback : value;
    }

    private static Integer readIntOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        String s = value.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        // 模型常写 "85" 或 "85 分"。抠出第一段连续数字，避免整字段丢失
        StringBuilder digits = new StringBuilder();
        boolean started = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
                started = true;
            } else if (started) {
                break;
            } else if (c == '-') {
                digits.append(c);
            }
        }
        if (digits.length() == 0) {
            return null;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            // 数字长到溢出 Integer，说明上游给的不是分数
            return null;
        }
    }

    private static String readString(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    private static BigDecimal readDecimal(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            // "0.85 左右" 这类带修饰的写法：抠第一段数字
            Integer digits = readIntOrNull(value);
            return digits == null ? null : BigDecimal.valueOf(digits);
        }
    }

    /**
     * 取第一个存在且非空的键。
     * <p>
     * 用来容忍模型改字段名：Prompt 里写的是 {@code indicators}，
     * 但模型偶尔会给 {@code reasons} 或 {@code evidence}。
     * 与其丢掉整组依据，不如按别名找一遍。
     * </p>
     */
    private static Object firstPresent(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                if (value instanceof List && ((List<?>) value).isEmpty()) {
                    continue;
                }
                if (value instanceof String && ((String) value).trim().isEmpty()) {
                    continue;
                }
                return value;
            }
        }
        return null;
    }

    /**
     * 把 JSON 对象里所有键列出来，用于"字段全不认识"时排查 Prompt 与模型的错配。
     */
    static List<String> keysOf(String raw) {
        String json = extractJsonObject(raw);
        JsonNode node = json == null ? null : JsonSupport.parseTree(json);
        List<String> keys = new ArrayList<>();
        if (node == null || !node.isObject()) {
            return keys;
        }
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            keys.add(it.next());
        }
        return keys;
    }
}

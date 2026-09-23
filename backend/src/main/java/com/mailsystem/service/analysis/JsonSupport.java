package com.mailsystem.service.analysis;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 分析管线专用的 JSON 工具。
 *
 * <h3>为什么自己建 ObjectMapper 而不是注入 Spring 的那个</h3>
 * <p>
 * 容器里的 ObjectMapper 被 {@code application.yml} 配成了
 * {@code default-property-inclusion: non_null} 与
 * {@code date-format: yyyy-MM-dd HH:mm:ss} —— 那是给<b>接口响应</b>用的策略。
 * 拿它来解析 LLM 的输出或序列化 {@code indicators} 数组会引入无关行为，
 * 而且将来有人调接口的序列化配置时会意外影响分析管线。
 * </p>
 *
 * <h3>解析失败一律返回 null，不抛异常</h3>
 * <p>
 * 上游返回的不是 JSON 是本管线的<b>常态</b>而非异常（模型加了前言、
 * 包了 Markdown 代码块、被网关截断）。把"解析不了"表达成 null 能让调用方
 * 用一条直线逻辑逐级降级，而不是在 try/catch 里嵌套。
 * </p>
 *
 * <h3>为什么是 public</h3>
 * <p>
 * 编排方（{@code MailAnalysisServiceImpl}）与读取路径（分析结果视图）
 * 按项目约定在同一包之外，因此必须公开。它只服务于分析管线，不是对外 API。
 * </p>
 */
public final class JsonSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // 上游可能多返回字段，不该因此失败
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonSupport() {
    }

    /**
     * 严格解析成一个 JSON 对象；不是对象（数组、字符串、非法 JSON）时返回 null。
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> map = MAPPER.readValue(json.trim(), Map.class);
            return map;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析成 JsonNode，用于按键路径安全取值。
     */
    static JsonNode parseTree(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(json.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 序列化。失败时返回 {@code "[]"} 而不是抛异常 ——
     * 这个方法的调用点全都在"正在写分析结论"的关键路径上，
     * 让一个序列化问题把整条结论丢掉是不划算的。
     */
    public static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 把一个任意的 JSON 值读成字符串列表。
     * <p>
     * 宽容处理三种上游偏差：
     * </p>
     * <ul>
     *   <li>该给数组却给了单个字符串 —— 包成单元素列表</li>
     *   <li>数组里混着数字/布尔 —— 转成字符串而不是丢掉</li>
     *   <li>给了对象 —— 取它的值（模型有时会把 {"依据": "..."} 当数组用）</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    static List<String> toStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                if (item == null) {
                    continue;
                }
                if (item instanceof Map) {
                    for (Object v : ((Map<String, Object>) item).values()) {
                        if (v != null) {
                            result.add(v.toString());
                        }
                    }
                } else {
                    result.add(item.toString());
                }
            }
            return result;
        }
        if (value instanceof Map) {
            for (Object v : ((Map<String, Object>) value).values()) {
                if (v != null) {
                    result.add(v.toString());
                }
            }
            return result;
        }
        result.add(value.toString());
        return result;
    }

    /**
     * 把数据库里存的 JSON 数组字符串读回列表。
     * <p>
     * 供读取路径使用（{@code indicators}/{@code actions} 列）。存量数据可能是
     * 空、非法 JSON、或早期写入的非数组形态，因此全部降级为空列表。
     * </p>
     */
    public static List<String> readStringList(String storedJson) {
        if (storedJson == null || storedJson.trim().isEmpty()) {
            return new ArrayList<>();
        }
        JsonNode node = parseTree(storedJson);
        if (node == null || !node.isArray()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            result.add(item.asText());
        }
        return result;
    }

    /**
     * 构造一个空 JSON 数组的字符串形式，供"没有依据"的场景落库。
     * <p>
     * 刻意写 {@code "[]"} 而不是 NULL：读取端因此不需要区分
     * "没有依据"与"还没分析"——后者由 {@code status} 表达。
     * </p>
     */
    static String emptyArray() {
        return "[]";
    }
}

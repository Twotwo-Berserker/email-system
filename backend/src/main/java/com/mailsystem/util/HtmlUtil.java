package com.mailsystem.util;

/**
 * HTML 转纯文本
 * <p>
 * 两个使用场景：
 * </p>
 * <ol>
 *   <li>IMAP 收到的邮件只有 {@code text/html} 部分时，落库前转成纯文本 ——
 *       详情页以纯文本渲染，直接存 HTML 会让用户看到满屏标签</li>
 *   <li>送 LLM 分析前剥离标签 —— 标签是纯粹的 token 浪费，
 *       而且会把正文挤到截断窗口之外</li>
 * </ol>
 *
 * <h3>这不是一个通用 HTML 解析器</h3>
 * <p>
 * 刻意用正则而不是引入 jsoup：这里的输入是邮件正文，目标是"把标签去掉、
 * 保留可读文字"，不需要 DOM 语义。已知的不足是不会处理畸形嵌套与
 * 注释中的标签，但这两者对目标无影响。
 * </p>
 * <p>
 * <b>安全提示</b>：本类的产出是纯文本，调用方渲染时<b>不得</b>再当作 HTML
 * 使用。前端以纯文本方式展示邮件正文正是为了避免邮件里的脚本被执行。
 * </p>
 */
public final class HtmlUtil {

    /** 块级标签：替换为换行，保留段落结构 */
    private static final String BLOCK_TAG_PATTERN =
            "(?i)</?(p|div|br|tr|li|h[1-6]|table|blockquote|section|article)[^>]*>";

    private HtmlUtil() {
    }

    /**
     * 把 HTML 转成尽量可读的纯文本。输入为 null 或空时原样返回。
     */
    public static String toPlainText(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        // 不含标签的内容直接返回，避免下面的实体解码把 "a < b" 这类正文改坏
        if (html.indexOf('<') < 0) {
            return html;
        }

        String text = html;

        // 1. script / style / head 整块丢弃 —— 它们的内容不是正文
        text = text.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        text = text.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        text = text.replaceAll("(?is)<head[^>]*>.*?</head>", " ");
        // 注释
        text = text.replaceAll("(?s)<!--.*?-->", " ");

        // 2. 块级标签 → 换行（在去掉标签之前，否则段落会粘成一行）
        text = text.replaceAll(BLOCK_TAG_PATTERN, "\n");

        // 3. 其余标签直接去掉
        text = text.replaceAll("(?s)<[^>]+>", "");

        // 4. 实体解码（只处理邮件里最常见的几个）
        text = decodeEntities(text);

        // 5. 收敛空白：行内多空格压成一个，连续空行压成一个
        text = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        text = text.replaceAll(" *\\n *", "\n");
        text = text.replaceAll("\\n{3,}", "\n\n");

        return text.trim();
    }

    /**
     * 解码常见 HTML 实体。
     * <p>
     * 顺序有讲究：{@code &amp;} 必须<b>最后</b>处理，否则
     * {@code &amp;lt;} 会被先解成 {@code &lt;}，再被解成 {@code <}，
     * 等于把用户本来想显示的尖括号解码成了标签。
     * </p>
     */
    private static String decodeEntities(String text) {
        return text
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&hellip;", "…")
                .replace("&amp;", "&");
    }
}

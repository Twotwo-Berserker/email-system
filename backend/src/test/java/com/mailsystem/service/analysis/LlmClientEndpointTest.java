package com.mailsystem.service.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 端点判型与地址拼接的单元测试。
 *
 * <h3>为什么这几十行字面量值得测</h3>
 * <p>
 * 这两件事都<b>不会以异常的形式失败</b>：判错型只是把请求发到一个不存在的路径，
 * 上游回 404，代码一路正常走进"HTTP 错误"分支落库。开发时看到的现象是
 * "分析没走完（HTTP_404）"，而 API Key、网络、模型名全都是好的 ——
 * 排查方向从一开始就是错的。曾经的真实故障：DeepSeek 的 Claude 格式入口
 * {@code https://api.deepseek.com/anthropic} 因为主机名不是 anthropic.com
 * 被判成 OpenAI 兼容端点，拼出了 {@code /anthropic/chat/completions}。
 * </p>
 * <p>
 * 用例全是不依赖网络的纯字符串判断，因此可以覆盖到各家网关的填法差异。
 * </p>
 */
class LlmClientEndpointTest {

    // ==================== 判型 ====================

    @Test
    @DisplayName("官方 Anthropic 域名判为 Anthropic")
    void officialHostIsAnthropic() {
        assertTrue(LlmClient.isAnthropicEndpoint("https://api.anthropic.com"));
        assertTrue(LlmClient.isAnthropicEndpoint("https://api.anthropic.com/v1"));
        assertTrue(LlmClient.isAnthropicEndpoint("https://api.anthropic.com/v1/messages"));
    }

    @Test
    @DisplayName("路径带 anthropic 的网关判为 Anthropic —— 本次故障的根因")
    void anthropicPathGatewayIsAnthropic() {
        assertTrue(LlmClient.isAnthropicEndpoint("https://api.deepseek.com/anthropic"));
        assertTrue(LlmClient.isAnthropicEndpoint("https://api.deepseek.com/anthropic/v1"));
        assertTrue(LlmClient.isAnthropicEndpoint("https://gw.example.com/api/anthropic"));
    }

    @Test
    @DisplayName("同一家厂商的 Chat Completions 入口仍判为 OpenAI 兼容")
    void openAiEntryStaysOpenAi() {
        // 判据必须是"路径里有 anthropic"，而不是"主机名是 deepseek"：
        // 两家入口共用一个域名，只能靠路径区分
        assertFalse(LlmClient.isAnthropicEndpoint("https://api.deepseek.com/v1"));
        assertFalse(LlmClient.isAnthropicEndpoint("https://api.deepseek.com"));
        assertFalse(LlmClient.isAnthropicEndpoint("https://api.openai.com/v1"));
        assertFalse(LlmClient.isAnthropicEndpoint("https://dashscope.aliyuncs.com/compatible-mode/v1"));
    }

    @Test
    @DisplayName("空值与无法解析的地址不判为 Anthropic")
    void blankIsNotAnthropic() {
        assertFalse(LlmClient.isAnthropicEndpoint(null));
        assertFalse(LlmClient.isAnthropicEndpoint(""));
        assertFalse(LlmClient.isAnthropicEndpoint("   "));
    }

    // ==================== 地址拼接 ====================

    @Test
    @DisplayName("网关根补全 /v1/messages")
    void gatewayRootGetsFullPath() {
        assertEquals("https://api.deepseek.com/anthropic/v1/messages",
                LlmClient.buildAnthropicUrl("https://api.deepseek.com/anthropic"));
        // 尾斜杠不该拼出双斜杠
        assertEquals("https://api.deepseek.com/anthropic/v1/messages",
                LlmClient.buildAnthropicUrl("https://api.deepseek.com/anthropic/"));
    }

    @Test
    @DisplayName("已到版本段只补 /messages —— 与 OpenAI 填到 /v1 的习惯一致")
    void versionSegmentGetsMessagesOnly() {
        assertEquals("https://api.anthropic.com/v1/messages",
                LlmClient.buildAnthropicUrl("https://api.anthropic.com/v1"));
        assertEquals("https://api.deepseek.com/anthropic/v1/messages",
                LlmClient.buildAnthropicUrl("https://api.deepseek.com/anthropic/v1"));
    }

    @Test
    @DisplayName("官方裸域名补 /v1/messages，不会被拼成 /messages")
    void bareHostGetsVersionedPath() {
        assertEquals("https://api.anthropic.com/v1/messages",
                LlmClient.buildAnthropicUrl("https://api.anthropic.com"));
    }

    @Test
    @DisplayName("已写全的地址原样使用，不重复拼接")
    void fullPathIsLeftAlone() {
        assertEquals("https://api.anthropic.com/v1/messages",
                LlmClient.buildAnthropicUrl("https://api.anthropic.com/v1/messages"));
        assertEquals("https://api.deepseek.com/anthropic/v1/messages",
                LlmClient.buildAnthropicUrl("https://api.deepseek.com/anthropic/v1/messages"));
    }

    // ==================== OpenAI 分支的既有行为 ====================

    @Test
    @DisplayName("OpenAI 分支：填到 /v1 或填全，两种都能用")
    void openAiUrlBuilding() {
        assertEquals("https://api.openai.com/v1/chat/completions",
                LlmClient.buildApiUrl("https://api.openai.com/v1", "/chat/completions"));
        assertEquals("https://api.openai.com/v1/chat/completions",
                LlmClient.buildApiUrl("https://api.openai.com/v1/chat/completions", "/chat/completions"));
    }
}

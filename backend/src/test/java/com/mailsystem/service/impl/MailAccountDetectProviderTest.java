package com.mailsystem.service.impl;

import com.mailsystem.dto.ProviderInfoView;
import com.mailsystem.util.MailProviderCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 识别接口返回载荷的测试
 *
 * <h3>为什么单独测这一层</h3>
 * <p>
 * 前端渲染识别卡片时读的是 {@link ProviderInfoView} 的字段
 * （{@code smtpCandidates[0]}、{@code guideSteps}、{@code unsupportedReason}…）。
 * 字段名或格式与前端预期不一致<b>不会让任何一侧报错</b> —— 表现只是
 * 卡片上少了一行、或者显示成 "undefined"。这类契约问题必须在后端侧
 * 用断言钉住，否则只能靠人眼在浏览器里发现。
 * </p>
 * <p>
 * 全程传 {@code useMx=false}，不依赖 DNS。
 * </p>
 */
class MailAccountDetectProviderTest {

    private MailAccountServiceImpl service;

    @BeforeEach
    void setUp() {
        // detectProvider 只依赖 providerCatalog，不需要数据库、不需要 Spring 容器。
        // 手工注入比拉起整个上下文快几个数量级
        service = new MailAccountServiceImpl();
        ReflectionTestUtils.setField(service, "providerCatalog", new MailProviderCatalog());
    }

    @Test
    @DisplayName("已识别服务商：前端要的每个字段都必须有值")
    void recognizedProviderPayload() {
        ProviderInfoView view = service.detectProvider("someone@qq.com", false);

        assertTrue(view.isRecognized());
        assertEquals("qq.com", view.getDomain());
        assertEquals("PRESET", view.getSource());
        assertNotNull(view.getProviderName());

        // 卡片上展示的服务器信息
        assertEquals("smtp.qq.com", view.getSmtpHost());
        assertEquals(465, view.getSmtpPort());
        assertEquals(1, view.getSmtpSsl());
        assertEquals("imap.qq.com", view.getImapHost());
        assertEquals(993, view.getImapPort());
        assertEquals(1, view.getImapSsl());

        assertTrue(view.isSmtpSupported());
        assertTrue(view.isImapSupported());

        // 授权码指引 —— 这是整个改动的核心卖点，缺了就等于没做
        assertNotNull(view.getGuideSteps());
        assertFalse(view.getGuideSteps().isEmpty());
        assertNotNull(view.getGuideUrl());
    }

    @Test
    @DisplayName("未识别服务商：不能给出'授权码在这里生成'的指引，但要说明会去试哪些服务器")
    void unrecognizedProviderPayload() {
        ProviderInfoView view = service.detectProvider("someone@unknown-corp.cn", false);

        assertFalse(view.isRecognized());
        assertEquals("PATTERN", view.getSource());

        // guideSteps 的语义是"怎么拿到授权码"。未收录的域名我们并不知道去哪儿拿，
        // 前端却会把它渲染在「授权码在这里生成」标题下 —— 因此这里必须为空，
        // 否则用户会看到一条文不对题的指引
        assertTrue(view.getGuideSteps() == null || view.getGuideSteps().isEmpty(),
                "未识别的服务商不该给出授权码获取步骤: " + view.getGuideSteps());
        assertNull(view.getGuideUrl());

        // 但候选端点必须有，前端要展示"将尝试 smtp.unknown-corp.cn:465 (SSL)"
        assertFalse(view.getSmtpCandidates().isEmpty());
        // "系统接下来会做什么"应当出现在 note 里
        assertNotNull(view.getNote());
        assertTrue(view.getNote().contains("unknown-corp.cn"),
                "note 里应当说明将要尝试的具体域名: " + view.getNote());
    }

    @Test
    @DisplayName("不支持的服务商：必须给出原因，前端据此显示劝退文案")
    void unsupportedProviderPayload() {
        ProviderInfoView view = service.detectProvider("someone@protonmail.com", false);

        assertTrue(view.isRecognized());
        assertFalse(view.isSmtpSupported());
        assertFalse(view.isImapSupported());
        assertTrue(view.getSmtpCandidates().isEmpty());
        // 没有这一项，前端只能显示一句干巴巴的"绑定失败"
        assertNotNull(view.getUnsupportedReason());
        assertFalse(view.getUnsupportedReason().isEmpty());
    }

    @Test
    @DisplayName("候选端点的展示格式必须与前端解析方式一致")
    void candidateDisplayFormat() {
        ProviderInfoView view = service.detectProvider("someone@outlook.com", false);

        // outlook 首选是 587 + STARTTLS —— 格式里必须体现加密方式，
        // 否则用户看到 "smtp.office365.com:587" 无从判断这意味着什么
        assertEquals("smtp.office365.com:587 (STARTTLS)", view.getSmtpCandidates().get(0));
        // 备选端点也要在列表里（探测时真的会去试）
        assertTrue(view.getSmtpCandidates().size() > 1, "Outlook 应有备选端点");
    }

    @Test
    @DisplayName("SSL 服务商的候选格式：465 必须标注为 SSL")
    void sslCandidateDisplayFormat() {
        ProviderInfoView view = service.detectProvider("someone@163.com", false);
        assertEquals("smtp.163.com:465 (SSL)", view.getSmtpCandidates().get(0));
        assertEquals("imap.163.com:993 (SSL)", view.getImapCandidates().get(0));
    }

    @Test
    @DisplayName("非法邮箱地址：返回空壳而不是抛异常")
    void invalidEmailReturnsEmptyView() {
        ProviderInfoView view = service.detectProvider("这不是邮箱", false);

        assertFalse(view.isRecognized());
        assertNull(view.getDomain());
        assertNull(view.getProviderName());
        assertTrue(view.getSmtpCandidates().isEmpty());
        assertTrue(view.getImapCandidates().isEmpty());
    }

    @Test
    @DisplayName("邮箱地址两侧的空白不影响识别")
    void surroundingWhitespaceIsTolerated() {
        ProviderInfoView view = service.detectProvider("  someone@163.com  ", false);
        assertTrue(view.isRecognized());
        assertEquals("163.com", view.getDomain());
        // 回填给前端的地址也要是干净的，否则会原样显示成带空格的样子
        assertEquals("someone@163.com", view.getEmailAddress());
    }
}

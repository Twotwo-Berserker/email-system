package com.mailsystem.util;

import com.mailsystem.util.MailProviderCatalog.Detection;
import com.mailsystem.util.MailProviderCatalog.Endpoint;
import com.mailsystem.util.MailProviderCatalog.Provider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务商识别表的单元测试
 *
 * <h3>为什么这张表值得测</h3>
 * <p>
 * 目录里是 30 多个域名到主机端口的映射，全部是字面量。写错一个字符
 * （{@code imap.163.com} 写成 {@code imap.163.net}、QQ 的 SSL 标志写成 0）
 * <b>不会有任何编译错误，也不会有运行时异常</b> —— 表现只是"某个服务商的用户
 * 绑定失败"，而开发者本地用的是 QQ 邮箱，永远复现不出来。
 * 这类静默错误正是单元测试的用武之地。
 * </p>
 * <p>
 * 所有用例都传 {@code useMx=false}：MX 查询要联网，单元测试不该依赖 DNS。
 * 因此这里验证的是<b>域名直配表</b>这一层，MX 反查属于集成测试的范畴。
 * </p>
 */
class MailProviderCatalogTest {

    private final MailProviderCatalog catalog = new MailProviderCatalog();

    // ==================== 域名解析 ====================

    @Test
    @DisplayName("域名解析：大小写、空白、无 @ 的输入都不该抛异常")
    void domainOfHandlesEdgeCases() {
        assertEquals("qq.com", MailProviderCatalog.domainOf("Someone@QQ.COM"));
        assertEquals("qq.com", MailProviderCatalog.domainOf("  a@qq.com  "));
        assertEquals("mail.co.uk", MailProviderCatalog.domainOf("a@mail.co.uk"));
        // 多个 @ 时取最后一个 —— 本地部分允许包含 @（带引号的地址）
        assertEquals("qq.com", MailProviderCatalog.domainOf("\"a@b\"@qq.com"));

        assertNull(MailProviderCatalog.domainOf(null));
        assertNull(MailProviderCatalog.domainOf("no-at-sign"));
        assertNull(MailProviderCatalog.domainOf("trailing@"));
    }

    // ==================== 主流服务商 ====================

    @Test
    @DisplayName("QQ 邮箱：ssl=1，且使用 465/993")
    void qqMail() {
        Provider provider = recognizedProvider("user@qq.com", "qq");

        assertEquals("smtp.qq.com", provider.getSmtpPrimary().host());
        assertEquals(465, provider.getSmtpPrimary().port());
        assertTrue(provider.getSmtpPrimary().isSsl());

        assertEquals("imap.qq.com", provider.getImapPrimary().host());
        assertEquals(993, provider.getImapPrimary().port());
        assertTrue(provider.getImapPrimary().isSsl());

        // 引导信息是给用户照着做的，缺了就等于没给
        assertFalse(provider.getGuideSteps().isEmpty());
        assertNotNull(provider.getGuideUrl());
    }

    @Test
    @DisplayName("Foxmail 与 QQ 邮箱共用同一套服务器配置")
    void foxmailSharesQqConfig() {
        assertEquals("qq", recognizedProvider("a@foxmail.com", "qq").getId());
        assertEquals("qq", recognizedProvider("a@vip.qq.com", "qq").getId());
    }

    @Test
    @DisplayName("网易系：163 / 126 / yeah.net 走同一套服务器")
    void neteaseFamily() {
        for (String domain : new String[]{"163.com", "126.com", "yeah.net"}) {
            Provider provider = recognizedProvider("user@" + domain, "163");
            assertEquals("smtp.163.com", provider.getSmtpPrimary().host(),
                    domain + " 应使用 smtp.163.com");
            assertEquals("imap.163.com", provider.getImapPrimary().host(),
                    domain + " 应使用 imap.163.com");
            assertEquals(993, provider.getImapPrimary().port());
        }
    }

    @Test
    @DisplayName("企业邮箱与个人版必须指向不同的服务器")
    void enterpriseDiffersFromPersonal() {
        assertEquals("smtp.exmail.qq.com",
                recognizedProvider("a@exmail.qq.com", "exmail-qq").getSmtpPrimary().host());
        assertEquals("smtp.qiye.163.com",
                recognizedProvider("a@qiye.163.com", "netease-qiye").getSmtpPrimary().host());
        // 若前缀相同则说明两套配置被合并了 —— 这是最隐蔽的一类错误
        assertFalse("smtp.qq.com".equals(
                recognizedProvider("a@exmail.qq.com", "exmail-qq").getSmtpPrimary().host()));
    }

    @Test
    @DisplayName("Outlook 个人版必须用 587 + STARTTLS，465 会被拒")
    void outlookUsesStartTls() {
        Provider provider = recognizedProvider("user@outlook.com", "outlook");
        Endpoint primary = provider.getSmtpPrimary();

        assertEquals(587, primary.port());
        assertFalse(primary.isSsl(), "首选端点必须是 STARTTLS 而非 SSL");

        // hotmail / live 是同一套后端
        assertEquals("outlook", recognizedProvider("a@hotmail.com", "outlook").getId());
        assertEquals("outlook", recognizedProvider("a@live.cn", "outlook").getId());
    }

    @Test
    @DisplayName("iCloud 的 SMTP 用 587 + STARTTLS，IMAP 用 993 SSL")
    void icloudMixedModes() {
        Provider provider = recognizedProvider("user@icloud.com", "icloud");
        assertEquals(587, provider.getSmtpPrimary().port());
        assertFalse(provider.getSmtpPrimary().isSsl());
        assertEquals(993, provider.getImapPrimary().port());
        assertTrue(provider.getImapPrimary().isSsl());
    }

    @Test
    @DisplayName("me.com 与 mac.com 是 iCloud 的历史域名")
    void icloudLegacyDomains() {
        assertEquals("icloud", recognizedProvider("a@me.com", "icloud").getId());
        assertEquals("icloud", recognizedProvider("a@mac.com", "icloud").getId());
    }

    // ==================== 不支持的服务商 ====================

    @Test
    @DisplayName("Proton 不开放协议：候选必须为空，不能拿猜测的地址去撞运气")
    void protonHasNoCandidates() {
        Detection detection = catalog.detect("user@protonmail.com", false);

        assertTrue(detection.isRecognized(), "Proton 是已收录的服务商");
        assertFalse(detection.isSmtpSupported(), "不应为 Proton 生成 SMTP 候选");
        assertFalse(detection.isImapSupported(), "不应为 Proton 生成 IMAP 候选");
        assertTrue(detection.getSmtpCandidates().isEmpty());
        assertTrue(detection.getImapCandidates().isEmpty());

        // 必须给出原因，否则用户只会看到"绑定失败"
        assertNotNull(detection.getProvider().getWarning());
    }

    // ==================== 未收录域名 ====================

    @Test
    @DisplayName("未收录域名：降级为猜测，且不被标记为已识别")
    void unknownDomainFallsBackToPattern() {
        Detection detection = catalog.detect("user@some-company.com", false);

        assertEquals("PATTERN", detection.getSource());
        assertFalse(detection.isRecognized(), "猜测不等于识别");
        assertTrue(detection.isSmtpSupported(), "猜测路径应产出可尝试的候选");

        assertEquals("smtp.some-company.com", detection.getSmtpCandidates().get(0).host());
        assertEquals(465, detection.getSmtpCandidates().get(0).port());
        assertEquals("imap.some-company.com", detection.getImapCandidates().get(0).host());
    }

    @Test
    @DisplayName("候选数量必须有上限 —— 每个候选都意味着一次最长数秒的连接等待")
    void candidateCountIsBounded() {
        for (String address : new String[]{
                "a@qq.com", "a@163.com", "a@outlook.com", "a@some-company.com"}) {
            Detection detection = catalog.detect(address, false);
            assertTrue(detection.getSmtpCandidates().size() <= 3,
                    address + " 的 SMTP 候选过多: " + detection.getSmtpCandidates().size());
            assertTrue(detection.getImapCandidates().size() <= 3,
                    address + " 的 IMAP 候选过多: " + detection.getImapCandidates().size());
        }
    }

    @Test
    @DisplayName("非法的邮箱地址不抛异常，只是识别不出")
    void invalidAddressDoesNotThrow() {
        Detection detection = catalog.detect("not-an-email", false);
        assertNull(detection.getDomain());
        assertFalse(detection.isRecognized());
        assertTrue(detection.getSmtpCandidates().isEmpty());

        assertFalse(catalog.detect(null, false).isRecognized());
    }

    // ==================== 一致性问题 ====================

    @Test
    @DisplayName("所有已收录服务商的首选端点必须出现在自己的候选列表里")
    void primaryEndpointIsAlwaysFirstCandidate() {
        String[] addresses = {
                "a@qq.com", "a@exmail.qq.com", "a@163.com", "a@qiye.163.com",
                "a@gmail.com", "a@outlook.com", "a@icloud.com", "a@zoho.com",
                "a@yandex.com", "a@sina.com", "a@sohu.com", "a@139.com",
                "a@189.cn", "a@21cn.com", "a@263.net", "a@aliyun.com"};

        for (String address : addresses) {
            Detection detection = catalog.detect(address, false);
            Provider provider = detection.getProvider();
            assertNotNull(provider, address + " 应被收录");

            if (provider.isSmtpSupported()) {
                assertFalse(detection.getSmtpCandidates().isEmpty(), address + " 缺 SMTP 候选");
                assertEquals(provider.getSmtpPrimary(), detection.getSmtpCandidates().get(0),
                        address + " 的首选 SMTP 端点与候选列表首位不一致");
            }
            if (provider.isImapSupported()) {
                assertFalse(detection.getImapCandidates().isEmpty(), address + " 缺 IMAP 候选");
                assertEquals(provider.getImapPrimary(), detection.getImapCandidates().get(0),
                        address + " 的首选 IMAP 端点与候选列表首位不一致");
            }
        }
    }

    @Test
    @DisplayName("端口与加密方式必须自洽：465/993 用 SSL，587/143 用 STARTTLS")
    void portAndSslModeAreConsistent() {
        String[] addresses = {
                "a@qq.com", "a@exmail.qq.com", "a@163.com", "a@qiye.163.com",
                "a@gmail.com", "a@outlook.com", "a@icloud.com", "a@zoho.com",
                "a@yandex.com", "a@sina.com", "a@sohu.com", "a@139.com",
                "a@189.cn", "a@21cn.com", "a@263.net", "a@aliyun.com"};

        for (String address : addresses) {
            List<Endpoint> endpoints = catalog.detect(address, false).getSmtpCandidates();
            for (Endpoint endpoint : endpoints) {
                assertPortMatchesMode(address, "SMTP", endpoint);
            }
            for (Endpoint endpoint : catalog.detect(address, false).getImapCandidates()) {
                assertPortMatchesMode(address, "IMAP", endpoint);
            }
        }
    }

    /**
     * 465/993 是"连上就是 TLS"，587/143 是"连上后 STARTTLS"。
     * 把端口和开关配错会得到一次 SSL 握手失败 —— 报错信息与"密码错误"完全不同，
     * 用户无从判断，因此这条一致性必须由测试守住。
     */
    private void assertPortMatchesMode(String address, String protocol, Endpoint endpoint) {
        int port = endpoint.port();
        if (port == 465 || port == 993) {
            assertTrue(endpoint.isSsl(),
                    address + " 的 " + protocol + " 端点 " + endpoint.display() + " 端口要求 SSL");
        } else if (port == 587 || port == 143) {
            assertFalse(endpoint.isSsl(),
                    address + " 的 " + protocol + " 端点 " + endpoint.display() + " 端口要求 STARTTLS");
        }
    }

    /** 断言识别出了指定 id 的服务商，并返回它 */
    private Provider recognizedProvider(String address, String expectedId) {
        Detection detection = catalog.detect(address, false);
        assertTrue(detection.isRecognized(), address + " 应被识别");
        Provider provider = detection.getProvider();
        assertNotNull(provider, address + " 的识别结果不应为 null");
        assertEquals(expectedId, provider.getId(), address + " 识别到的服务商 id 不符");
        return provider;
    }
}

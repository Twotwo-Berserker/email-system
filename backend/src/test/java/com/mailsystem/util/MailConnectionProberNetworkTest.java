package com.mailsystem.util;

import com.mailsystem.util.MailConnectionProber.ProbeResult;
import com.mailsystem.util.MailProviderCatalog.Detection;
import com.mailsystem.util.MailProviderCatalog.Endpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.mail.Session;
import javax.mail.Store;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 连接探测的联网诊断测试
 *
 * <h3>这个测试在验证什么</h3>
 * <p>
 * 不需要真实授权码，也能验证协议配置对不对：<b>拿一个必然错误的密码去连</b>，
 * 如果最终的错误是"认证失败"，说明 TLS 握手、协议版本、端口选择全都正确，
 * 只在最后一步被凭据拦下 —— 这正是我们要的结果。
 * </p>
 * <p>
 * 反过来，如果拿到的是 SSL 握手失败、连接超时或 {@code Unrecognized SSL message}，
 * 那就说明端口与加密方式的搭配错了（例如对 587 用了隐式 SSL）。
 * 这类错误在真实场景里会被用户报成"绑定不上"，而开发者本地用 QQ 邮箱永远复现不出来。
 * </p>
 *
 * <h3>没有网络时会跳过</h3>
 * <p>
 * 联网测试不该让一个离线环境的构建变红。连不上服务器时用
 * {@code assumeTrue} 跳过 —— 但要注意"跳过"本身也可能是网络问题，
 * 因此日志里必须留下痕迹，不能静默通过。
 * </p>
 */
class MailConnectionProberNetworkTest {

    private static final String BOGUS_PASSWORD = "definitely-not-a-real-auth-code";

    /** 一个语法合法但必然不存在的账号 —— 我们只测到"协议栈可用"为止 */
    private static final String FAKE_ACCOUNT = "mailsystem-probe-test@qq.com";

    private MailProviderCatalog catalog;
    private MailConnectionProber prober;

    @BeforeEach
    void setUp() {
        catalog = new MailProviderCatalog();

        MailConnectionFactory factory = new MailConnectionFactory();
        // 工厂与探测器都靠 @Value 注入超时，脱离 Spring 时手工补上。
        // 探测超时刻意保持较短，避免测试长时间挂起
        ReflectionTestUtils.setField(factory, "connectTimeoutMs", 8000);
        ReflectionTestUtils.setField(factory, "readTimeoutMs", 8000);
        ReflectionTestUtils.setField(factory, "sendClientId", true);

        prober = new MailConnectionProber();
        ReflectionTestUtils.setField(prober, "probeConnectMs", 8000);
        ReflectionTestUtils.setField(prober, "probeReadMs", 8000);
        ReflectionTestUtils.setField(prober, "connectionFactory", factory);
    }

    @Test
    @DisplayName("QQ 邮箱 SMTP：错误授权码必须被归为认证失败，而不是 SSL 或网络错误")
    void qqSmtpReachesAuthenticationStage() {
        Detection detection = catalog.detect(FAKE_ACCOUNT, false);
        List<Endpoint> candidates = detection.getSmtpCandidates();
        assumeTrue(!candidates.isEmpty(), "QQ 邮箱应有 SMTP 候选");

        ProbeResult result = prober.probeSmtp(candidates, FAKE_ACCOUNT, BOGUS_PASSWORD);
        System.out.println("[诊断] QQ SMTP 探测结果: " + result.summary());

        assumeTrue(!result.getAttempts().isEmpty(), "没有发起任何尝试");
        assumeTrue(isReachable(result), "网络不可达，跳过");

        assertFalse(result.isOk(), "用假授权码不该探测成功");
        assertTrue(result.isAuthFailed(),
                "连上了服务器却被非认证原因拒绝，说明协议配置有问题: " + result.summary());
    }

    @Test
    @DisplayName("QQ 邮箱 IMAP：错误授权码必须被归为认证失败")
    void qqImapReachesAuthenticationStage() {
        Detection detection = catalog.detect(FAKE_ACCOUNT, false);
        List<Endpoint> candidates = detection.getImapCandidates();
        assumeTrue(!candidates.isEmpty(), "QQ 邮箱应有 IMAP 候选");

        ProbeResult result = prober.probeImap(candidates, FAKE_ACCOUNT, BOGUS_PASSWORD);
        System.out.println("[诊断] QQ IMAP 探测结果: " + result.summary());

        assumeTrue(!result.getAttempts().isEmpty(), "没有发起任何尝试");
        assumeTrue(isReachable(result), "网络不可达，跳过");

        assertTrue(result.isAuthFailed(),
                "连上了服务器却被非认证原因拒绝，说明协议配置有问题: " + result.summary());
    }

    @Test
    @DisplayName("163 邮箱 IMAP：993 端口 + imaps 协议能走到 LOGIN 环节")
    void neteaseImapReachesLoginStage() {
        Detection detection = catalog.detect("mailsystem-probe-test@163.com", false);
        ProbeResult result = prober.probeImap(
                detection.getImapCandidates(), "mailsystem-probe-test@163.com", BOGUS_PASSWORD);
        System.out.println("[诊断] 163 IMAP 探测结果: " + result.summary());

        assumeTrue(!result.getAttempts().isEmpty(), "没有发起任何尝试");
        assumeTrue(isReachable(result), "网络不可达，跳过");

        assertTrue(result.isAuthFailed(),
                "163 的 IMAP 配置有问题，未走到认证环节: " + result.summary());
    }

    /**
     * 网易 ID 命令修复的回归护栏。
     *
     * <h3>这个测试能证明什么、不能证明什么</h3>
     * <p>
     * <b>能证明：</b>JavaMail 对 {@code imaps} 返回的实现类确实是
     * {@code com.sun.mail.imap.IMAPStore}，且它暴露了 {@code id(Map)} 方法 ——
     * 这正是 {@code MailConnectionFactory.sendClientId} 依赖反射查找的方法。
     * 方法在，反射就能找到，命令就会被发出。
     * </p>
     * <p>
     * <b>不能证明：</b>163 服务端确实因此放行。那个结论需要一份真实的
     * 163 授权码才能验证 —— 用错误的密码登录时，网易回的是
     * "LOGIN Login error or password error"，根本走不到触发 Unsafe Login 的分支。
     * 因此这里不假装验证了端到端效果。
     * </p>
     * <p>
     * <b>为什么仍然值得留着：</b>{@code sendClientId} 刻意吞掉了所有异常
     * （服务端不支持 ID 时会回 BAD）。这意味着一旦升级 JavaMail 导致
     * {@code id(Map)} 消失，那个修复会<b>静默失效</b> —— 没有报错、没有日志，
     * 只是 163 用户又开始收到 Unsafe Login。这条断言就是那个静默失效的哨兵。
     * </p>
     */
    @Test
    @DisplayName("护栏：IMAP 实现类必须一直暴露 id(Map)，否则网易修复会静默失效")
    void imapStoreStillExposesIdCommand() throws Exception {
        Session session = Session.getInstance(new Properties());
        Store store = session.getStore("imaps");

        assertTrue(store instanceof com.sun.mail.imap.IMAPStore,
                "imaps 的实现类变了，sendClientId 的反射查找需要重新确认: "
                        + store.getClass().getName());

        // 找不到方法会抛 NoSuchMethodException —— 这正是我们要拦住的回归
        assertNotNull(store.getClass().getMethod("id", Map.class),
                "IMAPStore.id(Map) 不存在了，网易的 ID 命令修复已失效");
    }

    /**
     * 判断这次失败是"服务器可达但拒绝了凭据"还是"根本没连上"。
     * <p>
     * 只要出现了认证类错误、或错误里带有服务端协议响应（而非 connect timeout /
     * unknown host），就认为网络是通的。
     * </p>
     */
    private boolean isReachable(ProbeResult result) {
        if (result.isAuthFailed()) {
            return true;
        }
        String error = String.valueOf(result.lastError()).toLowerCase();
        return !(error.contains("timed out") || error.contains("timeout")
                || error.contains("unknownhost") || error.contains("unknown host")
                || error.contains("network is unreachable") || error.contains("connect failed")
                || error.contains("connection refused"));
    }
}

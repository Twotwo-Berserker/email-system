package com.mailsystem.util;

import com.mailsystem.util.MailProviderCatalog.Endpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import javax.mail.Folder;
import javax.mail.Store;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 连接探测器 —— 用真实的连接结果替代用户的猜测
 *
 * <h3>它替用户回答了哪两个问题</h3>
 * <ol>
 *   <li><b>用哪个端口</b> —— 465 还是 587？</li>
 *   <li><b>SSL 还是 STARTTLS</b> —— 这两个词对非技术用户毫无意义</li>
 * </ol>
 * <p>
 * 这两个问题都有客观答案，而答案是"连一下试试"就能得到的。让用户去搜索引擎上
 * 查"我的邮箱该选哪个"，是把可自动化的判断转嫁成了用户的理解成本 ——
 * 更何况答案还可能因为网络环境而变（部分企业防火墙封 465，部分封 587）。
 * </p>
 *
 * <h3>探测在写库之前发生</h3>
 * <p>
 * 探测用的凭据就是用户本次填的明文授权码，尚未落库。这样做的直接好处是
 * <b>不会存下一个连不上的账户</b> —— 否则那个账户会在之后每 3 分钟的轮询里
 * 失败一次，把 {@code last_sync_error} 刷成一片红，而用户早已忘了自己填过什么。
 * </p>
 *
 * <h3>为什么 IMAP 探测要真的打开 INBOX</h3>
 * <p>
 * {@code store.connect()} 成功只证明<b>登录</b>通过，不能证明<b>能收信</b>。
 * 网易系邮箱的 "Unsafe Login" 拒绝、以及部分服务商的"未开启 IMAP 服务"
 * 都是在登录<b>之后</b>的第一条命令上才报出来的。因此这里继续把 INBOX 打开一次 ——
 * 这正是 {@code ImapReceiveServiceImpl} 同步时做的第一件事，
 * 于是"探测通过"与"同步能跑"说的是同一件事。
 * </p>
 *
 * <h3>为什么 SMTP 探测就停在 connect</h3>
 * <p>
 * 登录成功即返回，不再往下走 MAIL FROM / RCPT TO。SMTP 上验证"能不能发"
 * 的唯一彻底办法是真的发一封信 —— 而"绑定邮箱"这个动作绝不该产生一封
 * 发给陌生人的测试邮件。这是行业惯例（所有邮件客户端的"测试"按钮都只做认证），
 * 也是这个探测的边界。
 * </p>
 */
@Component
public class MailConnectionProber {

    /**
     * 探测阶段的超时显著短于正常收发。
     * <p>
     * 用户正对着一个转圈的按钮，而探测可能要试 3 个端点：按正常收发的
     * 10 秒 + 20 秒算，最坏情况会把用户晾在那里两分钟。这里压到 4 秒 + 6 秒，
     * 最坏约 30 秒。代价只是"极慢的服务器可能被判为不可用"，
     * 而这种服务器在真正收信时同样会超时。
     * </p>
     */
    @Value("${app.probe.connect-timeout-ms:4000}")
    private int probeConnectMs;

    @Value("${app.probe.read-timeout-ms:6000}")
    private int probeReadMs;

    @Autowired
    private MailConnectionFactory connectionFactory;

    // ==================== 对外入口 ====================

    /**
     * 依次尝试候选端点，返回第一个连得通的。
     *
     * @param candidates 已按命中概率排序的候选（见 {@code MailProviderCatalog}）
     */
    public ProbeResult probeSmtp(List<Endpoint> candidates, String username, String password) {
        List<Attempt> attempts = new ArrayList<>();
        for (Endpoint endpoint : candidates) {
            try {
                JavaMailSenderImpl sender = connectionFactory.buildSender(
                        endpoint.host(), endpoint.port(), endpoint.isSsl(),
                        username, password, probeConnectMs, probeReadMs);
                sender.testConnection();
                attempts.add(Attempt.success(endpoint));
                return ProbeResult.success(endpoint, attempts);
            } catch (Exception e) {
                Attempt attempt = Attempt.failure(endpoint, e);
                attempts.add(attempt);
                // 认证失败是凭据层面的问题，换端口/换加密方式都救不回来，
                // 继续试只是让用户多等几个超时
                if (attempt.authFailed) {
                    break;
                }
            }
        }
        return ProbeResult.failure(attempts);
    }

    /**
     * 依次尝试候选端点，返回第一个能登录<b>并打开 INBOX</b> 的。
     */
    public ProbeResult probeImap(List<Endpoint> candidates, String username, String password) {
        List<Attempt> attempts = new ArrayList<>();
        for (Endpoint endpoint : candidates) {
            Store store = null;
            Folder inbox = null;
            try {
                store = connectionFactory.openImapStore(
                        endpoint.host(), endpoint.port(), endpoint.isSsl(),
                        username, password, probeConnectMs, probeReadMs);
                inbox = store.getFolder("INBOX");
                inbox.open(Folder.READ_ONLY);
                attempts.add(Attempt.success(endpoint));
                return ProbeResult.success(endpoint, attempts);
            } catch (Exception e) {
                Attempt attempt = Attempt.failure(endpoint, e);
                attempts.add(attempt);
                if (attempt.authFailed) {
                    break;
                }
            } finally {
                closeQuietly(inbox, store);
            }
        }
        return ProbeResult.failure(attempts);
    }

    private void closeQuietly(Folder folder, Store store) {
        if (folder != null && folder.isOpen()) {
            try {
                folder.close(false);
            } catch (Exception ignored) {
                // 探测阶段的关闭失败无补救意义
            }
        }
        if (store != null && store.isConnected()) {
            try {
                store.close();
            } catch (Exception ignored) {
                // 同上
            }
        }
    }

    // ==================== 值类型 ====================

    /** 一次端点尝试的结果 */
    public static final class Attempt {

        private final Endpoint endpoint;
        private final boolean ok;
        private final boolean authFailed;
        private final String error;

        private Attempt(Endpoint endpoint, boolean ok, boolean authFailed, String error) {
            this.endpoint = endpoint;
            this.ok = ok;
            this.authFailed = authFailed;
            this.error = error;
        }

        static Attempt success(Endpoint endpoint) {
            return new Attempt(endpoint, true, false, null);
        }

        static Attempt failure(Endpoint endpoint, Throwable e) {
            return new Attempt(endpoint, false, MailErrors.isAuthFailure(e), MailErrors.describe(e));
        }

        public Endpoint getEndpoint() {
            return endpoint;
        }

        public boolean isOk() {
            return ok;
        }

        public boolean isAuthFailed() {
            return authFailed;
        }

        public String getError() {
            return error;
        }
    }

    /** 一次协议探测的完整结果 */
    public static final class ProbeResult {

        private final Endpoint working;
        private final List<Attempt> attempts;

        private ProbeResult(Endpoint working, List<Attempt> attempts) {
            this.working = working;
            this.attempts = Collections.unmodifiableList(attempts);
        }

        static ProbeResult success(Endpoint working, List<Attempt> attempts) {
            return new ProbeResult(working, attempts);
        }

        static ProbeResult failure(List<Attempt> attempts) {
            return new ProbeResult(null, attempts);
        }

        public boolean isOk() {
            return working != null;
        }

        /** 连得通的那个端点；全部失败时为 null */
        public Endpoint getWorking() {
            return working;
        }

        public List<Attempt> getAttempts() {
            return attempts;
        }

        /** 是否所有失败都是认证失败 —— 用于决定给用户看"授权码错了"还是"网络不通" */
        public boolean isAuthFailed() {
            if (attempts.isEmpty()) {
                return false;
            }
            for (Attempt attempt : attempts) {
                if (!attempt.authFailed) {
                    return false;
                }
            }
            return true;
        }

        /** 失败原因（取最后一次尝试的），成功时为 null */
        public String lastError() {
            return attempts.isEmpty() ? null : attempts.get(attempts.size() - 1).getError();
        }

        /**
         * 给用户看的一句话结论。
         * <p>
         * 刻意把"试了哪些端点"带上 —— 当用户来问"为什么连不上"时，
         * 这句话就是他需要提供给管理员或服务商的全部信息。
         * </p>
         */
        public String summary() {
            if (isOk()) {
                return "已连通 " + working.display();
            }
            StringBuilder sb = new StringBuilder();
            sb.append(isAuthFailed() ? "认证失败" : "无法连接");
            sb.append("（已尝试 ");
            for (int i = 0; i < attempts.size(); i++) {
                if (i > 0) {
                    sb.append("、");
                }
                sb.append(attempts.get(i).getEndpoint().display());
            }
            sb.append("）");
            String error = lastError();
            if (error != null && !error.isEmpty()) {
                sb.append("：").append(error);
            }
            return sb.toString();
        }
    }
}

package com.mailsystem.util;

import com.mailsystem.entity.MailAccount;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.mail.Session;
import javax.mail.Store;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 邮件协议连接的构造工具（SMTP / IMAP）
 * <p>
 * 单独抽出来的原因：{@code MailAccountService}（测试连接）、
 * {@code MailSendService}（发信）、{@code MailConnectionProber}（自动探测）
 * 都要建连接 —— 把建连逻辑放在任一方都会形成循环依赖，
 * 或让"测试连接"被迫走一遍发信流程。
 * </p>
 *
 * <h3>为什么处处显式设超时</h3>
 * <p>
 * JavaMail 默认<b>永不超时</b>。一个网络不可达的 SMTP 服务器会让调用线程
 * 永久挂起：发信场景下它占着 Tomcat 工作线程，轮询场景下它让后续所有账户
 * 都同步不了。因此连接/读取/写入三个超时都显式设置。
 * </p>
 *
 * <h3>SSL 与 STARTTLS 的区别</h3>
 * <ul>
 *   <li>{@code ssl=1} —— 全程 TLS 加密（SMTP 465 / IMAP 993）。
 *       IMAP 侧用 {@code imaps} 协议而非在 {@code imap} 上开 ssl 开关，
 *       前者是 JavaMail 的规范用法</li>
 *   <li>{@code ssl=0} —— 明文连接后用 STARTTLS 升级（SMTP 587 / IMAP 143）</li>
 * </ul>
 *
 * <h3>为什么有"显式参数"的重载</h3>
 * <p>
 * 实体版重载（{@link #buildSender(MailAccount, String)}）读取的是用户已经保存的配置；
 * 而自动探测阶段面对的是<b>还没落库的候选端点</b>，没有 {@link MailAccount} 可读。
 * 两者共用同一套属性构造逻辑，因此把 {@code (host, port, ssl, username, password)}
 * 作为最底层的方法，实体版只是它的一层取值封装 —— 这样"探测时能连通"
 * 与"保存后能发信"用的是同一份协议配置，不会出现探测通过、发信失败的错位。
 * </p>
 */
@Component
public class MailConnectionFactory {

    /** 大部分服务商（QQ/163/Gmail）都要求 From 与认证账号一致，这里统一用账户地址 */
    private static final String MAIL_SMTP_AUTH = "mail.smtp.auth";
    private static final String MAIL_SMTP_SSL_ENABLE = "mail.smtp.ssl.enable";
    private static final String MAIL_SMTP_STARTTLS_ENABLE = "mail.smtp.starttls.enable";
    private static final String MAIL_SMTP_CONN_TIMEOUT = "mail.smtp.connectiontimeout";
    private static final String MAIL_SMTP_TIMEOUT = "mail.smtp.timeout";
    private static final String MAIL_SMTP_WRITE_TIMEOUT = "mail.smtp.writetimeout";

    private static final String MAIL_IMAP_CONN_TIMEOUT = "mail.imap.connectiontimeout";
    private static final String MAIL_IMAP_TIMEOUT = "mail.imap.timeout";
    private static final String MAIL_IMAPS_CONN_TIMEOUT = "mail.imaps.connectiontimeout";
    private static final String MAIL_IMAPS_TIMEOUT = "mail.imaps.timeout";
    private static final String MAIL_IMAPS_SSL_ENABLE = "mail.imaps.ssl.enable";
    private static final String MAIL_IMAP_STARTTLS_ENABLE = "mail.imap.starttls.enable";

    @Value("${app.smtp.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${app.smtp.read-timeout-ms:20000}")
    private int readTimeoutMs;

    /**
     * 是否在 IMAP 登录后发送客户端标识（RFC 2971 的 ID 命令）。
     * <p>
     * 默认开启。见 {@link #sendClientId} 的说明 —— 这是网易系邮箱的硬性要求。
     * </p>
     */
    @Value("${app.imap.send-client-id:true}")
    private boolean sendClientId;

    // ==================== SMTP ====================

    /**
     * 构造一个可直接用于发信的 {@link JavaMailSenderImpl}。
     * <p>
     * 刻意<b>不</b>依赖 Spring 容器里的全局单例 {@code JavaMailSender} ——
     * 那是"一套全局配置"的模型，而这里是每个用户各用自己的邮箱，
     * 必须按账户动态构造。
     * </p>
     *
     * @param password 已解密的授权码
     */
    public JavaMailSenderImpl buildSender(MailAccount account, String password) {
        return buildSender(account.getSmtpHost(), resolveSmtpPort(account),
                isSsl(account.getSmtpSsl()), resolveSmtpUsername(account), password,
                connectTimeoutMs, readTimeoutMs);
    }

    /**
     * 按显式参数构造发信器 —— 自动探测与项目级中继用（此时没有落库的账户）。
     * <p>
     * 登录名为空时<b>关闭认证</b>：{@code mail.smtp.auth=true} 会让 JavaMail
     * 强制走 AUTH 流程，而中继服务器允许匿名投递（内网 Postfix 之类）时
     * 这个开关会让本来能通的连接直接失败。
     * </p>
     */
    public JavaMailSenderImpl buildSender(String host, int port, boolean ssl,
                                          String username, String password,
                                          int connectTimeout, int readTimeout) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        sender.setDefaultEncoding("UTF-8");
        boolean auth = !isBlank(username);
        sender.setJavaMailProperties(smtpProperties(ssl, auth, connectTimeout, readTimeout));
        return sender;
    }

    public Properties smtpProperties(MailAccount account) {
        // 用户绑定的账户恒有登录名（缺省回落为邮箱地址），因此恒需认证
        return smtpProperties(isSsl(account.getSmtpSsl()), true, connectTimeoutMs, readTimeoutMs);
    }

    public Properties smtpProperties(boolean ssl, int connectTimeout, int readTimeout) {
        return smtpProperties(ssl, true, connectTimeout, readTimeout);
    }

    public Properties smtpProperties(boolean ssl, boolean auth, int connectTimeout, int readTimeout) {
        Properties props = new Properties();
        props.put(MAIL_SMTP_AUTH, String.valueOf(auth));
        props.put(MAIL_SMTP_CONN_TIMEOUT, String.valueOf(connectTimeout));
        props.put(MAIL_SMTP_TIMEOUT, String.valueOf(readTimeout));
        props.put(MAIL_SMTP_WRITE_TIMEOUT, String.valueOf(readTimeout));
        if (ssl) {
            props.put(MAIL_SMTP_SSL_ENABLE, "true");
        } else {
            props.put(MAIL_SMTP_STARTTLS_ENABLE, "true");
        }
        return props;
    }

    // ==================== IMAP ====================

    /**
     * 打开 IMAP 连接。
     * <p>
     * 返回的 {@link Store} 由调用方负责 {@code close()} —— 放在 try-with-resources
     * 里使用。连接失败时抛 {@code MessagingException}，由调用方归类为
     * "认证失败"还是"网络失败"。
     * </p>
     *
     * @param password 已解密的授权码
     */
    public Store openImapStore(MailAccount account, String password) throws Exception {
        return openImapStore(account.getImapHost(), resolveImapPort(account),
                isSsl(account.getImapSsl()), resolveImapUsername(account), password,
                connectTimeoutMs, readTimeoutMs);
    }

    /**
     * 按显式参数打开 IMAP 连接 —— 自动探测阶段用。
     */
    public Store openImapStore(String host, int port, boolean ssl,
                               String username, String password,
                               int connectTimeout, int readTimeout) throws Exception {
        String protocol = ssl ? "imaps" : "imap";

        Properties props = new Properties();
        props.put(ssl ? MAIL_IMAPS_CONN_TIMEOUT : MAIL_IMAP_CONN_TIMEOUT,
                String.valueOf(connectTimeout));
        props.put(ssl ? MAIL_IMAPS_TIMEOUT : MAIL_IMAP_TIMEOUT,
                String.valueOf(readTimeout));
        if (ssl) {
            props.put(MAIL_IMAPS_SSL_ENABLE, "true");
        } else {
            props.put(MAIL_IMAP_STARTTLS_ENABLE, "true");
        }

        Session session = Session.getInstance(props);
        Store store = session.getStore(protocol);
        store.connect(host, port, username, password);
        sendClientId(store);
        return store;
    }

    /**
     * 发送 IMAP 客户端标识（RFC 2971）。
     *
     * <h3>为什么必须发</h3>
     * <p>
     * 网易系邮箱（163 / 126 / yeah.net / 网易企业邮）在 IMAP 登录后会校验客户端是否
     * 声明了自己的身份。不声明就直接拒绝服务，且报错极难理解：
     * </p>
     * <pre>Unsafe Login. Please contact kefu@188.com for help</pre>
     * <p>
     * 用户看到这句话只会以为是自己邮箱被盗了 —— 实际原因只是客户端没发 ID 命令。
     * 这是本项目里"用户不可能自己排查出来"的典型问题，因此在连接层统一解决。
     * </p>
     *
     * <h3>为什么用反射</h3>
     * <p>
     * {@code id()} 只存在于 JavaMail 的实现类 {@code com.sun.mail.imap.IMAPStore}，
     * 不在 {@code javax.mail.Store} 接口上，也没有对应的 {@code mail.imap.*} 配置项
     * （对比之下 Yahoo 的同款需求就有 {@code mail.imap.yahoo.guid} 这个专有属性，
     * 网易没有）。直接写上编译期依赖意味着换一个 IMAP provider 实现就会
     * {@code NoClassDefFoundError}；反射则退化为"这个能力不支持"，连接照常可用。
     * </p>
     *
     * <h3>失败为什么不上抛</h3>
     * <p>
     * 服务端不支持 ID 时会回 BAD，JavaMail 把它包成异常。但"服务端不认 ID"
     * 恰恰说明它也不需要 ID，此时连接是完全可用的 —— 让这个异常冒出去
     * 会把一次成功的登录变成一次失败。因此只记日志。
     * </p>
     */
    private void sendClientId(Store store) {
        if (!sendClientId) {
            return;
        }
        try {
            Method id = store.getClass().getMethod("id", Map.class);
            Map<String, String> clientId = new LinkedHashMap<>();
            clientId.put("name", "MailSystem");
            clientId.put("version", "1.0");
            // vendor 与 support-email 是网易文档里建议提供的字段，
            // 部分服务商只在字段齐全时才放行
            clientId.put("vendor", "MailSystem");
            clientId.put("support-email", "support@mailsystem.local");
            id.invoke(store, clientId);
        } catch (NoSuchMethodException e) {
            // 非 IMAPStore 的实现（如 IMAP over 其他 provider），没有这个能力
        } catch (Exception e) {
            System.out.println("[MailConnectionFactory] IMAP ID 命令未被服务端接受（不影响连接）: "
                    + e.getMessage());
        }
    }

    // ==================== 缺省值推导 ====================

    /** SSL 默认 465，STARTTLS 默认 587 */
    public int resolveSmtpPort(MailAccount account) {
        if (account.getSmtpPort() != null && account.getSmtpPort() > 0) {
            return account.getSmtpPort();
        }
        return isSsl(account.getSmtpSsl()) ? 465 : 587;
    }

    /** SSL 默认 993，STARTTLS 默认 143 */
    public int resolveImapPort(MailAccount account) {
        if (account.getImapPort() != null && account.getImapPort() > 0) {
            return account.getImapPort();
        }
        return isSsl(account.getImapSsl()) ? 993 : 143;
    }

    /** 用户名缺省即邮箱地址 —— QQ/163/Gmail 均如此 */
    public String resolveSmtpUsername(MailAccount account) {
        return isBlank(account.getSmtpUsername()) ? account.getEmailAddress() : account.getSmtpUsername();
    }

    public String resolveImapUsername(MailAccount account) {
        return isBlank(account.getImapUsername()) ? account.getEmailAddress() : account.getImapUsername();
    }

    /**
     * 是否启用 SSL。null 视为启用 —— 三家主流服务商都要求加密连接，
     * 默认关闭会让"忘了勾"的用户收到一个难懂的连接错误。
     */
    private boolean isSsl(Integer ssl) {
        return ssl == null || ssl == 1;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}

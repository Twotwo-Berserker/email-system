package com.mailsystem.util;

import org.springframework.stereotype.Component;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务商预设目录 —— 把"用户自己去查服务器地址"变成"系统已经知道"
 *
 * <h3>这个类解决什么问题</h3>
 * <p>
 * 手工配置模式下，用户要填 9 个字段：邮箱地址、SMTP 主机、SMTP 端口、是否 SSL、
 * SMTP 用户名、SMTP 授权码、IMAP 主机、IMAP 端口、IMAP 授权码。其中只有
 * <b>邮箱地址</b>和<b>授权码</b>是用户真正知道的，其余 7 个都是"查得到但不该让他查"
 * 的信息 —— 邮件的收发服务器地址由服务商在 DNS 的 MX 记录里公开声明，
 * 这是客观事实而非用户的选择。让用户去搜索"QQ 邮箱 SMTP 端口是多少"，
 * 是把这个系统的实现细节转嫁成了用户的理解成本。
 * </p>
 *
 * <h3>三级识别</h3>
 * <ol>
 *   <li><b>PRESET</b> —— 邮箱域名直接命中内置表（qq.com → QQ 邮箱）。
 *       覆盖国内主流与常见海外服务商，命中率最高、零网络开销</li>
 *   <li><b>MX</b> —— 域名没收录时查 MX 记录反推。企业邮箱和自有域名走这条路：
 *       公司域名 {@code @acme.com} 的 MX 指向 {@code acme-com.mail.protection.outlook.com}，
 *       说明它托管在 Microsoft 365，按 Outlook 的配置连即可。
 *       这是 Thunderbird 自动配置同款的判定思路</li>
 *   <li><b>PATTERN</b> —— MX 也没结论时，按约定俗成的命名猜
 *       {@code smtp.<域名>} / {@code imap.<域名>}。猜错不致命，
 *       因为下一步的探测器会验证，猜只是给探测器一个起点</li>
 * </ol>
 *
 * <h3>预设是假设，探测是事实</h3>
 * <p>
 * 内置表里的主机与端口是<b>起点而不是承诺</b> —— 服务商随时可能调整端口策略。
 * 因此 {@link MailConnectionProber} 会真的去连一遍，以连得通的那组为准。
 * 这个分工让"预设写错了一个端口"降级为"多花 5 秒"，而不是"用户绑定失败"。
 * </p>
 *
 * <h3>关于无法收录的服务商</h3>
 * <p>
 * Proton Mail 这类不提供公开 SMTP/IMAP 的服务商被<b>显式标注为不支持</b>
 * （{@code smtpSupported/imapSupported = false}）。提前告诉用户"这条路走不通"
 * 比让他反复尝试、反复失败要诚实得多。
 * </p>
 */
@Component
public class MailProviderCatalog {

    /** 授权码/密码是每个服务商自己定义的二次凭据，这里标注为不支持的即是此类 */
    private static final String SOURCE_PRESET = "PRESET";
    private static final String SOURCE_MX = "MX";
    private static final String SOURCE_PATTERN = "PATTERN";

    /** 单个协议最多尝试的端点组合数 —— 每多一个候选就多一次最长 5 秒的连接等待 */
    private static final int MAX_CANDIDATES = 3;

    /** SMTP 常规端口：465 全程 SSL，587 STARTTLS 升级 */
    private static final int SMTP_SSL_PORT = 465;
    private static final int SMTP_STARTTLS_PORT = 587;

    /** IMAP 常规端口：993 全程 SSL，143 STARTTLS 升级 */
    private static final int IMAP_SSL_PORT = 993;
    private static final int IMAP_STARTTLS_PORT = 143;

    /** 域名 → 服务商。一个服务商可注册多个域名，共用同一个 Provider 实例 */
    private final Map<String, Provider> byDomain = new LinkedHashMap<>();

    /** MX 主机名片段 → 服务商 id，按顺序匹配（先匹配到的优先） */
    private final Map<String, String> mxPatterns = new LinkedHashMap<>();

    /** 服务商 id → 服务商 */
    private final Map<String, Provider> byId = new LinkedHashMap<>();

    /** MX 查询结果缓存 —— DNS 是外部依赖，同一个域名不该在一次会话里查两遍 */
    private final Map<String, List<String>> mxCache = new ConcurrentHashMap<>();

    /** 查不到 MX 时也要缓存，否则未知域名每次识别都会重新等一次 DNS 超时 */
    private static final List<String> MX_ABSENT = Collections.emptyList();

    public MailProviderCatalog() {
        registerProviders();
        registerMxPatterns();
    }

    // ==================== 对外入口 ====================

    /**
     * 识别一个邮箱地址对应的服务商与候选连接端点。
     *
     * @param emailAddress 用户填写的邮箱地址
     * @param useMx       是否允许 MX 反查。查询会带来最长数秒的 DNS 等待，
     *                    在"用户边打字边识别"的场景下应当关掉，
     *                    只在用户点了绑定时开启
     */
    public Detection detect(String emailAddress, boolean useMx) {
        String domain = domainOf(emailAddress);
        if (domain == null) {
            return new Detection(null, null, null,
                    Collections.emptyList(), Collections.emptyList());
        }

        Provider provider = byDomain.get(domain);
        String source = SOURCE_PRESET;

        if (provider == null && useMx) {
            provider = byMx(domain);
            source = provider == null ? null : SOURCE_MX;
        }
        if (provider == null) {
            // MX 也没有结论：可能是自有域名自建邮局，或 DNS 不可达。
            // 按命名惯例猜一组，交给探测器验证
            provider = patternGuess(domain);
            source = SOURCE_PATTERN;
        }

        return new Detection(domain, provider, source,
                smtpCandidates(provider, domain), imapCandidates(provider, domain));
    }

    /** 按 id 取服务商（前端展示指引时用） */
    public Provider byProviderId(String id) {
        return id == null ? null : byId.get(id);
    }

    // ==================== 候选端点 ====================

    /**
     * SMTP 候选端点，按命中概率排序。
     * <p>
     * 已知服务商优先用它声明的那组；同时补一个"另一种加密方式"的备选 ——
     * 有些网络环境封禁 465（明文优先的企业防火墙策略），
     * 有些则封禁 587。两种都试比让用户去猜自己的网络要可靠。
     * </p>
     */
    private List<Endpoint> smtpCandidates(Provider provider, String domain) {
        if (provider != null) {
            // 服务商自己就不提供 SMTP（如 Proton）—— 没有可猜的余地
            if (provider.protocolUnavailable) {
                return Collections.emptyList();
            }
            if (provider.isSmtpSupported()) {
                return provider.smtpEndpoints;
            }
        }
        // 未收录：smtp.<域名> 是最普遍的约定，其次是域名本身（部分自建邮局如此）。
        // 注意这里刻意不区分 provider 是否为 null —— patternGuess 造出来的
        // 展示用 Provider 没有端点，它和"完全未知"走的是同一条猜测路径
        Set<Endpoint> candidates = new LinkedHashSet<>();
        candidates.add(new Endpoint("smtp." + domain, SMTP_SSL_PORT, 1));
        candidates.add(new Endpoint("smtp." + domain, SMTP_STARTTLS_PORT, 0));
        candidates.add(new Endpoint(domain, SMTP_SSL_PORT, 1));
        return trim(candidates);
    }

    private List<Endpoint> imapCandidates(Provider provider, String domain) {
        if (provider != null) {
            if (provider.protocolUnavailable) {
                return Collections.emptyList();
            }
            if (provider.isImapSupported()) {
                return provider.imapEndpoints;
            }
        }
        Set<Endpoint> candidates = new LinkedHashSet<>();
        candidates.add(new Endpoint("imap." + domain, IMAP_SSL_PORT, 1));
        candidates.add(new Endpoint("imap." + domain, IMAP_STARTTLS_PORT, 0));
        candidates.add(new Endpoint(domain, IMAP_SSL_PORT, 1));
        return trim(candidates);
    }

    private List<Endpoint> trim(Set<Endpoint> candidates) {
        List<Endpoint> list = new ArrayList<>(candidates);
        return list.size() <= MAX_CANDIDATES ? list : list.subList(0, MAX_CANDIDATES);
    }

    // ==================== MX 反查 ====================

    /**
     * 用 MX 记录反推服务商。
     * <p>
     * 只取"能唯一指向某一家"的片段。片段匹配刻意用 contains 而非相等：
     * 各家 MX 主机名的前后缀不固定（{@code mx1.mxhichina.com}、
     * {@code acme-com.mail.protection.outlook.com}），精确匹配会大面积漏判。
     * </p>
     */
    private Provider byMx(String domain) {
        for (String mxHost : lookupMx(domain)) {
            String lower = mxHost.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> pattern : mxPatterns.entrySet()) {
                if (lower.contains(pattern.getKey())) {
                    Provider provider = byId.get(pattern.getValue());
                    if (provider != null) {
                        System.out.println("[ProviderCatalog] " + domain + " 经 MX(" + mxHost
                                + ") 识别为 " + provider.displayName);
                        return provider;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 查询域名的 MX 主机名。
     * <p>
     * JNDI 的 DNS 查询不设超时可能长时间阻塞（DNS 服务器无响应时尤其明显），
     * 而这里跑在用户的 HTTP 请求线程上。因此显式设置单次查询超时与重试次数，
     * 任何异常都降级为"查不到"—— MX 只是锦上添花，不该让绑定流程失败。
     * </p>
     */
    private List<String> lookupMx(String domain) {
        List<String> cached = mxCache.get(domain);
        if (cached != null) {
            return cached;
        }

        List<String> result = new ArrayList<>();
        InitialDirContext ctx = null;
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            // 单个 DNS 服务器的等待上限与重试次数
            env.put("com.sun.jndi.dns.timeout.initial", "2000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            ctx = new InitialDirContext(env);

            Attributes attributes = ctx.getAttributes(domain, new String[]{"MX"});
            Attribute mx = attributes.get("MX");
            if (mx != null) {
                for (int i = 0; i < mx.size(); i++) {
                    // MX 记录形如 "10 mx1.mxhichina.com."，取最后一段
                    String value = String.valueOf(mx.get(i)).trim();
                    int space = value.lastIndexOf(' ');
                    String host = space >= 0 ? value.substring(space + 1) : value;
                    if (host.endsWith(".")) {
                        host = host.substring(0, host.length() - 1);
                    }
                    if (!host.isEmpty()) {
                        result.add(host);
                    }
                }
            }
        } catch (Exception e) {
            // 无 MX 记录 / DNS 不可达 / 被沙箱拦截，都只意味着"这条路没有结论"
            System.out.println("[ProviderCatalog] " + domain + " 的 MX 查询无结果: " + e.getMessage());
        } finally {
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (Exception ignored) {
                    // 关闭失败无补救意义
                }
            }
        }

        List<String> stored = result.isEmpty() ? MX_ABSENT : result;
        mxCache.put(domain, stored);
        return stored;
    }

    /**
     * 未收录域名时的展示用条目。
     * <p>
     * 刻意不放进 {@link #byId} / {@link #byDomain}：它不是"一个已知的服务商"，
     * 只是为了让前端有名字可展示。它的端点是空的，因此候选列表会走猜测路径
     * （见 {@link #smtpCandidates}）。
     * </p>
     * <p>
     * <b>{@code guideSteps} 必须留空。</b> 那个字段的语义是"怎么拿到授权码"，
     * 前端会把它渲染在「授权码在这里生成」的标题下。对未收录的域名我们并不知道
     * 授权码去哪儿拿 —— 往这里塞"系统会尝试 smtp.xxx"会让用户看到一条
     * 文不对题的指引。这类"系统接下来会做什么"的说明属于 {@code note}。
     * </p>
     */
    private Provider patternGuess(String domain) {
        return new Provider("custom", "自定义域名邮箱", null,
                Collections.emptyList(), null, Collections.emptyList(),
                Collections.emptyList(),
                null,
                "如果全部尝试都失败，请联系你的邮箱管理员确认服务器地址，或展开「手动配置」自行填写",
                "将尝试 smtp." + domain + " / imap." + domain
                        + " 等常见命名，连得通的组合会自动保存");
    }

    public static String domainOf(String emailAddress) {
        if (emailAddress == null) {
            return null;
        }
        int at = emailAddress.lastIndexOf('@');
        if (at < 0 || at == emailAddress.length() - 1) {
            return null;
        }
        return emailAddress.substring(at + 1).trim().toLowerCase(Locale.ROOT);
    }

    // ==================== 内置表 ====================

    private void registerProviders() {
        // ---------- 腾讯 ----------
        register(new Provider("qq", "QQ 邮箱 / Foxmail",
                new Endpoint("smtp.qq.com", SMTP_SSL_PORT, 1),
                endpoints(new Endpoint("smtp.qq.com", SMTP_SSL_PORT, 1),
                        new Endpoint("smtp.qq.com", SMTP_STARTTLS_PORT, 0)),
                new Endpoint("imap.qq.com", IMAP_SSL_PORT, 1),
                endpoints(new Endpoint("imap.qq.com", IMAP_SSL_PORT, 1),
                        new Endpoint("imap.qq.com", IMAP_STARTTLS_PORT, 0)),
                "登录邮箱网页版 → 设置 → 账户 → 开启「IMAP/SMTP服务」→ 按提示用手机发送验证短信 → 生成授权码",
                "https://mail.qq.com",
                "授权码是 16 位小写字母，只显示一次，请当场复制",
                "QQ 邮箱 / Foxmail 都用 smtp.qq.com，不需要分别配置"),
                "qq.com", "vip.qq.com", "foxmail.com");

        register(new Provider("exmail-qq", "腾讯企业邮箱",
                new Endpoint("smtp.exmail.qq.com", SMTP_SSL_PORT, 1),
                endpoints(new Endpoint("smtp.exmail.qq.com", SMTP_SSL_PORT, 1),
                        new Endpoint("smtp.exmail.qq.com", SMTP_STARTTLS_PORT, 0)),
                new Endpoint("imap.exmail.qq.com", IMAP_SSL_PORT, 1),
                endpoints(new Endpoint("imap.exmail.qq.com", IMAP_SSL_PORT, 1),
                        new Endpoint("imap.exmail.qq.com", IMAP_STARTTLS_PORT, 0)),
                "登录邮箱网页版 → 设置 → 客户端设置 → 开启 IMAP/SMTP → 生成「客户端专用密码」",
                "https://exmail.qq.com",
                "若管理员未开放客户端权限，需联系管理员在管理后台开启",
                "企业邮箱的服务器地址与个人版 QQ 邮箱不同，注意区分"),
                "exmail.qq.com");

        // ---------- 网易 ----------
        register(new Provider("163", "网易邮箱（163 / 126 / yeah.net）",
                new Endpoint("smtp.163.com", SMTP_SSL_PORT, 1),
                endpoints(new Endpoint("smtp.163.com", SMTP_SSL_PORT, 1),
                        new Endpoint("smtp.163.com", SMTP_STARTTLS_PORT, 0)),
                new Endpoint("imap.163.com", IMAP_SSL_PORT, 1),
                endpoints(new Endpoint("imap.163.com", IMAP_SSL_PORT, 1),
                        new Endpoint("imap.163.com", IMAP_STARTTLS_PORT, 0)),
                "登录邮箱网页版 → 设置 → POP3/SMTP/IMAP → 开启「IMAP/SMTP服务」→ 新增授权码 → 扫码验证",
                "https://mail.163.com",
                "本系统已自动发送网易要求的 IMAP ID 命令，不会再出现 Unsafe Login 报错",
                "126 与 yeah.net 是网易旗下同一套服务器，用同一个授权码"),
                "163.com", "126.com", "yeah.net");

        register(new Provider("netease-qiye", "网易企业邮箱",
                new Endpoint("smtp.qiye.163.com", SMTP_SSL_PORT, 1),
                endpoints(new Endpoint("smtp.qiye.163.com", SMTP_SSL_PORT, 1),
                        new Endpoint("smtp.qiye.163.com", SMTP_STARTTLS_PORT, 0)),
                new Endpoint("imap.qiye.163.com", IMAP_SSL_PORT, 1),
                endpoints(new Endpoint("imap.qiye.163.com", IMAP_SSL_PORT, 1),
                        new Endpoint("imap.qiye.163.com", IMAP_STARTTLS_PORT, 0)),
                "登录邮箱网页版 → 设置 → 客户端设置 → 开启 IMAP/SMTP → 生成客户端授权码",
                "https://qiye.163.com",
                "企业邮箱可能要求管理员先为账号开放客户端协议",
                "企业邮箱与个人版的服务器地址不同，注意区分"),
                "qiye.163.com");

        // ---------- 海外 ----------
        register(new Provider("gmail", "Gmail",
                new Endpoint("smtp.gmail.com", SMTP_SSL_PORT, 1),
                endpoints(new Endpoint("smtp.gmail.com", SMTP_SSL_PORT, 1),
                        new Endpoint("smtp.gmail.com", SMTP_STARTTLS_PORT, 0)),
                new Endpoint("imap.gmail.com", IMAP_SSL_PORT, 1),
                endpoints(new Endpoint("imap.gmail.com", IMAP_SSL_PORT, 1),
                        new Endpoint("imap.gmail.com", IMAP_STARTTLS_PORT, 0)),
                "先开启两步验证 → 打开「应用专用密码」页面 → 输入应用名称 → 生成 16 位密码",
                "https://myaccount.google.com/apppasswords",
                "必须先开启两步验证，否则「应用专用密码」入口不会出现；Google 账号本身的密码无法用于 SMTP/IMAP",
                "国内网络可能无法直连 Gmail 的 SMTP/IMAP 端口"),
                "gmail.com", "googlemail.com");

        register(new Provider("outlook", "Outlook / Hotmail",
                new Endpoint("smtp.office365.com", SMTP_STARTTLS_PORT, 0),
                endpoints(new Endpoint("smtp.office365.com", SMTP_STARTTLS_PORT, 0),
                        new Endpoint("smtp-mail.outlook.com", SMTP_STARTTLS_PORT, 0),
                        new Endpoint("smtp.office365.com", SMTP_SSL_PORT, 1)),
                new Endpoint("outlook.office365.com", IMAP_SSL_PORT, 1),
                endpoints(new Endpoint("outlook.office365.com", IMAP_SSL_PORT, 1),
                        new Endpoint("outlook.office365.com", IMAP_STARTTLS_PORT, 0)),
                "登录 account.microsoft.com → 安全 → 高级安全选项 → 开启两步验证 → 创建新的「应用密码」",
                "https://account.microsoft.com/security",
                "组织（公司/学校）账号可能已被管理员禁用密码登录，那种情况只能走 OAuth2 授权",
                "个人版 Outlook 必须用 STARTTLS(587)，用 465 会被拒"),
                "outlook.com", "hotmail.com", "live.com", "live.cn", "msn.com");

        register(new Provider("icloud", "iCloud 邮箱",
                new Endpoint("smtp.mail.me.com", SMTP_STARTTLS_PORT, 0),
                endpoints(new Endpoint("smtp.mail.me.com", SMTP_STARTTLS_PORT, 0),
                        new Endpoint("smtp.mail.me.com", SMTP_SSL_PORT, 1)),
                new Endpoint("imap.mail.me.com", IMAP_SSL_PORT, 1),
                endpoints(new Endpoint("imap.mail.me.com", IMAP_SSL_PORT, 1)),
                "登录 appleid.apple.com → 登录与安全 → App 专用密码 → 生成",
                "https://appleid.apple.com",
                "必须使用 App 专用密码，Apple ID 的登录密码无法通过 SMTP/IMAP 认证",
                "iCloud 的 SMTP 用 587 + STARTTLS"),
                "icloud.com", "me.com", "mac.com");

        register(new Provider("zoho", "Zoho 邮箱",
                new Endpoint("smtp.zoho.com", SMTP_SSL_PORT, 1),
                endpoints(new Endpoint("smtp.zoho.com", SMTP_SSL_PORT, 1),
                        new Endpoint("smtp.zoho.com", SMTP_STARTTLS_PORT, 0)),
                new Endpoint("imap.zoho.com", IMAP_SSL_PORT, 1),
                endpoints(new Endpoint("imap.zoho.com", IMAP_SSL_PORT, 1),
                        new Endpoint("imap.zoho.com", IMAP_STARTTLS_PORT, 0)),
                "登录 accounts.zoho.com → 安全 → App 专用密码 → 生成",
                "https://mail.zoho.com",
                "开启两步验证后必须使用 App 专用密码",
                "企业版域名会显示为 zoho.com.cn，服务器地址不变"),
                "zoho.com", "zohomail.com", "zoho.com.cn");

        register(new Provider("yandex", "Yandex 邮箱",
                new Endpoint("smtp.yandex.com", SMTP_SSL_PORT, 1),
                endpoints(new Endpoint("smtp.yandex.com", SMTP_SSL_PORT, 1),
                        new Endpoint("smtp.yandex.com", SMTP_STARTTLS_PORT, 0)),
                new Endpoint("imap.yandex.com", IMAP_SSL_PORT, 1),
                endpoints(new Endpoint("imap.yandex.com", IMAP_SSL_PORT, 1),
                        new Endpoint("imap.yandex.com", IMAP_STARTTLS_PORT, 0)),
                "登录 Yandex ID → 安全 → 应用密码 → 选择「邮件」→ 生成",
                "https://mail.yandex.com",
                "必须先创建应用密码，账号密码会被拒绝",
                null),
                "yandex.com", "yandex.ru", "ya.ru");

        // ---------- 国内其他 ----------
        register(new Provider("sina", "新浪邮箱",
                new Endpoint("smtp.sina.com", SMTP_SSL_PORT, 1),
                endpoints(new Endpoint("smtp.sina.com", SMTP_SSL_PORT, 1),
                        new Endpoint("smtp.sina.com", SMTP_STARTTLS_PORT, 0)),
                new Endpoint("imap.sina.com", IMAP_SSL_PORT, 1),
                endpoints(new Endpoint("imap.sina.com", IMAP_SSL_PORT, 1)),
                "登录邮箱网页版 → 设置 → 客户端POP/IMAP/SMTP → 开启 IMAP/SMTP 服务",
                "https://mail.sina.com.cn",
                "新浪免费邮箱的 IMAP 权限需要手动申请，部分老账号不提供",
                "VIP 邮箱与免费邮箱的服务器地址相同"),
                "sina.com", "sina.cn", "vip.sina.com");

        register(new Provider("sohu", "搜狐邮箱",
                new Endpoint("smtp.sohu.com", SMTP_SSL_PORT, 1),
                endpoints(new Endpoint("smtp.sohu.com", SMTP_SSL_PORT, 1),
                        new Endpoint("smtp.sohu.com", SMTP_STARTTLS_PORT, 0)),
                new Endpoint("imap.sohu.com", IMAP_SSL_PORT, 1),
                endpoints(new Endpoint("imap.sohu.com", IMAP_SSL_PORT, 1)),
                "登录邮箱网页版 → 设置 → 账户 → 开启 IMAP/SMTP 服务 → 设置独立密码",
                "https://mail.sohu.com",
                "搜狐邮箱的「独立密码」即此处需要的授权码，与登录密码不同",
                null),
                "sohu.com");

        register(new Provider("139", "中国移动 139 邮箱",
                new Endpoint("smtp.139.com", SMTP_SSL_PORT, 1),
                endpoints(new Endpoint("smtp.139.com", SMTP_SSL_PORT, 1),
                        new Endpoint("smtp.139.com", SMTP_STARTTLS_PORT, 0)),
                new Endpoint("imap.139.com", IMAP_SSL_PORT, 1),
                endpoints(new Endpoint("imap.139.com", IMAP_SSL_PORT, 1)),
                "登录邮箱网页版 → 设置 → 账户 → 开启 IMAP/SMTP 服务 → 获取授权码",
                "https://mail.10086.cn",
                "139 邮箱要求先用绑定手机号获取授权码，登录密码不可用于客户端",
                null),
                "139.com");

        register(new Provider("189", "中国电信 189 邮箱",
                new Endpoint("smtp.189.cn", SMTP_SSL_PORT, 1),
                endpoints(new Endpoint("smtp.189.cn", SMTP_SSL_PORT, 1),
                        new Endpoint("smtp.189.cn", SMTP_STARTTLS_PORT, 0)),
                new Endpoint("imap.189.cn", IMAP_SSL_PORT, 1),
                endpoints(new Endpoint("imap.189.cn", IMAP_SSL_PORT, 1)),
                "登录邮箱网页版 → 设置 → 客户端设置 → 开启 IMAP/SMTP 服务",
                "https://mail.189.cn",
                null,
                null),
                "189.cn");

        register(new Provider("21cn", "21CN 邮箱",
                new Endpoint("smtp.21cn.com", SMTP_SSL_PORT, 1),
                endpoints(new Endpoint("smtp.21cn.com", SMTP_SSL_PORT, 1),
                        new Endpoint("smtp.21cn.com", SMTP_STARTTLS_PORT, 0)),
                new Endpoint("imap.21cn.com", IMAP_SSL_PORT, 1),
                endpoints(new Endpoint("imap.21cn.com", IMAP_SSL_PORT, 1)),
                "登录邮箱网页版 → 设置 → 账户 → 开启 IMAP/SMTP 服务",
                "https://mail.21cn.com",
                null,
                null),
                "21cn.com");

        register(new Provider("263", "263 邮箱",
                new Endpoint("smtp.263.net", SMTP_SSL_PORT, 1),
                endpoints(new Endpoint("smtp.263.net", SMTP_SSL_PORT, 1),
                        new Endpoint("smtp.263.net", SMTP_STARTTLS_PORT, 0)),
                new Endpoint("imap.263.net", IMAP_SSL_PORT, 1),
                endpoints(new Endpoint("imap.263.net", IMAP_SSL_PORT, 1)),
                "登录邮箱网页版 → 设置 → 客户端设置 → 开启 IMAP/SMTP 服务",
                "https://mail.263.net",
                null,
                null),
                "263.net");

        register(new Provider("aliyun", "阿里云企业邮箱",
                new Endpoint("smtp.qiye.aliyun.com", SMTP_SSL_PORT, 1),
                endpoints(new Endpoint("smtp.qiye.aliyun.com", SMTP_SSL_PORT, 1),
                        new Endpoint("smtp.qiye.aliyun.com", SMTP_STARTTLS_PORT, 0)),
                new Endpoint("imap.qiye.aliyun.com", IMAP_SSL_PORT, 1),
                endpoints(new Endpoint("imap.qiye.aliyun.com", IMAP_SSL_PORT, 1)),
                "登录企业邮箱网页版 → 设置 → 客户端设置 → 开启 IMAP/SMTP → 生成客户端密码",
                "https://qiye.aliyun.com",
                "阿里云个人邮箱（@aliyun.com）已于 2022 年停止服务，只有企业邮箱可用",
                null),
                "aliyun.com", "mails.aliyun.com");

        // ---------- 明确不支持的服务商 ----------
        // 提前说"走不通"，比让用户反复试、反复失败要诚实
        register(new Provider("proton", "Proton Mail",
                null, Collections.emptyList(), null, Collections.emptyList(),
                Collections.singletonList("Proton 官方不提供公开的 SMTP/IMAP 接口"),
                "https://proton.me/mail/bridge",
                "需要在电脑上安装 Proton Mail Bridge，由它在本机提供 SMTP/IMAP 服务，"
                        + "届时可手动填入 Bridge 给出的本地地址与密码",
                "Proton Mail 出于端到端加密的设计，不开放标准邮件协议",
                true),
                "protonmail.com", "proton.me");
    }

    private void registerMxPatterns() {
        // 顺序即优先级：先匹配到的胜出，因此把特征更明确的放在前面
        mxPatterns.put("exmail.qq.com", "exmail-qq");
        mxPatterns.put("qiye.163.com", "netease-qiye");
        mxPatterns.put("mxhichina", "aliyun");            // 阿里云企业邮箱
        mxPatterns.put("mail.protection.outlook", "outlook"); // Microsoft 365
        mxPatterns.put("outlook", "outlook");
        mxPatterns.put("google", "gmail");                // Google Workspace
        mxPatterns.put("googlemail", "gmail");
        mxPatterns.put("qq.com", "qq");
        mxPatterns.put("foxmail", "qq");
        mxPatterns.put("163", "163");                     // 163mx00.mxmail.netease.com 等
        mxPatterns.put("netease", "163");
        mxPatterns.put("126", "163");
        mxPatterns.put("zoho", "zoho");
        mxPatterns.put("yandex", "yandex");
        mxPatterns.put("icloud", "icloud");
        mxPatterns.put("me.com", "icloud");
    }

    /**
     * 注册一个服务商，并把它的所有域名指向同一个实例。
     */
    private void register(Provider provider, String... domains) {
        byId.put(provider.id, provider);
        for (String domain : domains) {
            byDomain.put(domain, provider);
        }
    }

    private static List<Endpoint> endpoints(Endpoint... endpoints) {
        return Collections.unmodifiableList(Arrays.asList(endpoints));
    }

    // ==================== 值类型 ====================

    /**
     * 一个待尝试的连接端点。
     *
     * @param ssl 1=全程 SSL（465/993），0=明文连接后 STARTTLS 升级（587/143）
     */
    public record Endpoint(String host, int port, int ssl) {
        public boolean isSsl() {
            return ssl == 1;
        }

        /** 展示用，如 {@code smtp.qq.com:465 (SSL)} */
        public String display() {
            return host + ":" + port + (isSsl() ? " (SSL)" : " (STARTTLS)");
        }
    }

    /** 一个服务商的默认配置与授权码获取指引 */
    public static final class Provider {

        final String id;
        final String displayName;

        /** 主端点（第一个候选），用于展示与落库的默认值 */
        final Endpoint smtpPrimary;
        final List<Endpoint> smtpEndpoints;

        final Endpoint imapPrimary;
        final List<Endpoint> imapEndpoints;

        final List<String> guideSteps;
        final String guideUrl;

        /** 该服务商特有的坑，例如"必须先开两步验证" */
        final String warning;

        /** 中性提示，例如"用 587 而非 465" */
        final String note;

        /**
         * 服务商在协议层就不开放 —— 与"未收录、需要猜"是两回事。
         * <p>
         * 前者（Proton）要直接告诉用户"这条路走不通"，不能拿猜测的
         * {@code smtp.protonmail.com} 去撞运气；后者（自有域名自建邮局）
         * 恰恰应该去猜。少了这个区分，Proton 用户会看到一堆看不懂的连接超时。
         * </p>
         */
        final boolean protocolUnavailable;

        Provider(String id, String displayName,
                 Endpoint smtpPrimary, List<Endpoint> smtpEndpoints,
                 Endpoint imapPrimary, List<Endpoint> imapEndpoints,
                 List<String> guideSteps, String guideUrl, String warning, String note) {
            this(id, displayName, smtpPrimary, smtpEndpoints, imapPrimary, imapEndpoints,
                    guideSteps, guideUrl, warning, note, false);
        }

        Provider(String id, String displayName,
                 Endpoint smtpPrimary, List<Endpoint> smtpEndpoints,
                 Endpoint imapPrimary, List<Endpoint> imapEndpoints,
                 List<String> guideSteps, String guideUrl, String warning, String note,
                 boolean protocolUnavailable) {
            this.id = id;
            this.displayName = displayName;
            this.smtpPrimary = smtpPrimary;
            this.smtpEndpoints = smtpEndpoints;
            this.imapPrimary = imapPrimary;
            this.imapEndpoints = imapEndpoints;
            this.guideSteps = guideSteps;
            this.guideUrl = guideUrl;
            this.warning = warning;
            this.note = note;
            this.protocolUnavailable = protocolUnavailable;
        }

        /** 简写构造：单一端点（无备选）时用 */
        Provider(String id, String displayName,
                 Endpoint smtpPrimary, List<Endpoint> smtpEndpoints,
                 Endpoint imapPrimary, List<Endpoint> imapEndpoints,
                 String guideStep, String guideUrl, String warning, String note) {
            this(id, displayName, smtpPrimary, smtpEndpoints, imapPrimary, imapEndpoints,
                    Collections.singletonList(guideStep), guideUrl, warning, note);
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public Endpoint getSmtpPrimary() {
            return smtpPrimary;
        }

        public Endpoint getImapPrimary() {
            return imapPrimary;
        }

        public List<Endpoint> getSmtpEndpoints() {
            return smtpEndpoints;
        }

        public List<Endpoint> getImapEndpoints() {
            return imapEndpoints;
        }

        public List<String> getGuideSteps() {
            return guideSteps;
        }

        public String getGuideUrl() {
            return guideUrl;
        }

        public String getWarning() {
            return warning;
        }

        public String getNote() {
            return note;
        }

        /** 是否提供 SMTP 发信 —— false 表示这条路在服务商侧就走不通 */
        public boolean isSmtpSupported() {
            return smtpPrimary != null && !smtpEndpoints.isEmpty();
        }

        public boolean isImapSupported() {
            return imapPrimary != null && !imapEndpoints.isEmpty();
        }
    }

    /** 一次识别的结果 */
    public static final class Detection {

        final String domain;
        final Provider provider;

        /** PRESET / MX / PATTERN；provider 为 null 时无意义 */
        final String source;

        final List<Endpoint> smtpCandidates;
        final List<Endpoint> imapCandidates;

        Detection(String domain, Provider provider, String source,
                  List<Endpoint> smtpCandidates, List<Endpoint> imapCandidates) {
            this.domain = domain;
            this.provider = provider;
            this.source = source;
            this.smtpCandidates = smtpCandidates;
            this.imapCandidates = imapCandidates;
        }

        public String getDomain() {
            return domain;
        }

        public Provider getProvider() {
            return provider;
        }

        public String getSource() {
            return source;
        }

        public List<Endpoint> getSmtpCandidates() {
            return smtpCandidates;
        }

        public List<Endpoint> getImapCandidates() {
            return imapCandidates;
        }

        /** 是否真的识别出了服务商（PRESET/MX）。PATTERN 只是猜测，不算识别 */
        public boolean isRecognized() {
            return provider != null && !SOURCE_PATTERN.equals(source);
        }

        /**
         * 该协议是否值得一试。
         * <p>
         * 判据是"有没有候选端点"而不是服务商的声明 —— 这个定义天然自洽：
         * 有候选就意味着探测流程会去连，没候选就是真的没得试。
         * </p>
         */
        public boolean isSmtpSupported() {
            return !smtpCandidates.isEmpty();
        }

        public boolean isImapSupported() {
            return !imapCandidates.isEmpty();
        }
    }
}

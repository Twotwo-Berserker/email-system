package com.mailsystem.service.impl;

import com.mailsystem.config.InboundProperties;
import com.mailsystem.dto.CloudflareBindRequest;
import com.mailsystem.dto.InboundCapabilityView;
import com.mailsystem.dto.MailAccountRequest;
import com.mailsystem.dto.MailAccountView;
import com.mailsystem.dto.ProviderInfoView;
import com.mailsystem.dto.QuickBindRequest;
import com.mailsystem.dto.QuickBindResult;
import com.mailsystem.entity.MailAccount;
import com.mailsystem.mapper.MailAccountMapper;
import com.mailsystem.service.MailAccountService;
import com.mailsystem.service.OutboundRelayService;
import com.mailsystem.util.CryptoUtil;
import com.mailsystem.util.MailAddressUtil;
import com.mailsystem.util.MailConnectionFactory;
import com.mailsystem.util.MailConnectionProber;
import com.mailsystem.util.MailConnectionProber.ProbeResult;
import com.mailsystem.util.MailErrors;
import com.mailsystem.util.MailProviderCatalog;
import com.mailsystem.util.MailProviderCatalog.Detection;
import com.mailsystem.util.MailProviderCatalog.Endpoint;
import com.mailsystem.util.MailProviderCatalog.Provider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.mail.Store;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 邮箱账户服务实现
 */
@Service
public class MailAccountServiceImpl implements MailAccountService {

    /** 授权码提交值含此标记即视为"不修改"，与 LLM 配置的处理一致 */
    private static final String MASK_MARKER = "****";

    private static final int MAX_ERROR_CHARS = 500;

    /** 探测任务的等待上限 —— 探测自身有 4+6 秒超时，留足余量即可 */
    private static final long PROBE_TIMEOUT_SECONDS = 90L;

    /**
     * 本域地址的用户名部分。
     * <p>
     * 刻意比 RFC 5322 窄：这里不追求"接受一切合法地址"，而是要挡住会带来麻烦的
     * 写法。连续的点、点开头的用户名在各家邮件系统里行为不一，允许它们只会
     * 制造"领得到、寄不出去"的地址。大写已被 {@link MailAddressUtil#normalize}
     * 统一转成小写，因此这里只认小写。
     * </p>
     */
    private static final Pattern LOCAL_PART_PATTERN =
            Pattern.compile("^[a-z0-9]([a-z0-9._+-]*[a-z0-9])?$");

    @Autowired
    private MailAccountMapper mailAccountMapper;

    @Autowired
    private InboundProperties inbound;

    @Autowired
    private OutboundRelayService outboundRelayService;

    @Autowired
    private CryptoUtil cryptoUtil;

    @Autowired
    private MailConnectionFactory connectionFactory;

    @Autowired
    private MailProviderCatalog providerCatalog;

    @Autowired
    private MailConnectionProber connectionProber;

    @Autowired
    @Qualifier("mailProbeExecutor")
    private ThreadPoolTaskExecutor probeExecutor;

    @Override
    public List<MailAccountView> listForUser(Long userId, String ownerEmail) {
        return mailAccountMapper.selectByUserId(userId).stream()
                .map(a -> {
                    MailAccountView v = MailAccountView.from(a);
                    // 用户端不需要 ownerEmail（就是他自己的邮箱），保持一致地填上
                    v.setOwnerEmail(ownerEmail);
                    return v;
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public MailAccountView create(Long userId, MailAccountRequest req) {
        String address = req.getEmailAddress().trim();
        MailAccount existing = mailAccountMapper.selectByUserIdAndEmail(userId, address);
        if (existing != null) {
            throw new RuntimeException("该邮箱已绑定，请直接修改或先解绑");
        }
        if (isBlank(req.getSmtpHost()) && isBlank(req.getImapHost())) {
            throw new RuntimeException("请至少填写 SMTP 或 IMAP 服务器地址");
        }

        MailAccount account = new MailAccount();
        account.setUserId(userId);
        account.setEmailAddress(address);
        applyRequest(account, req, null);
        account.setImapLastUid(0L);
        mailAccountMapper.insert(account);

        System.out.println("[MailAccount] 用户#" + userId + " 绑定邮箱 " + address);
        return MailAccountView.from(account);
    }

    // ==================== 服务商识别 ====================

    @Override
    public ProviderInfoView detectProvider(String emailAddress, boolean useMx) {
        String address = emailAddress == null ? "" : emailAddress.trim();
        Detection detection = providerCatalog.detect(address, useMx);
        Provider provider = detection.getProvider();

        ProviderInfoView view = new ProviderInfoView();
        view.setEmailAddress(address);
        view.setDomain(detection.getDomain());
        view.setRecognized(detection.isRecognized());
        view.setSource(detection.getSource());
        view.setSmtpSupported(detection.isSmtpSupported());
        view.setImapSupported(detection.isImapSupported());
        view.setSmtpCandidates(displayOf(detection.getSmtpCandidates()));
        view.setImapCandidates(displayOf(detection.getImapCandidates()));

        if (provider != null) {
            view.setProviderId(provider.getId());
            view.setProviderName(provider.getDisplayName());
            view.setGuideSteps(provider.getGuideSteps());
            view.setGuideUrl(provider.getGuideUrl());
            view.setWarning(provider.getWarning());
            view.setNote(provider.getNote());
            if (provider.isSmtpSupported()) {
                applyEndpoint(view, provider.getSmtpPrimary(), true);
            }
            if (provider.isImapSupported()) {
                applyEndpoint(view, provider.getImapPrimary(), false);
            }
        }

        // 候选列表为空 = 该协议在服务商侧就走不通，把原因说清楚
        if (!detection.isSmtpSupported() || !detection.isImapSupported()) {
            view.setUnsupportedReason(provider == null
                    ? "未能确定该邮箱的可达服务器"
                    : provider.getWarning());
        }

        // 未识别时不再另写提示 —— patternGuess 给出的 note 里带着具体的域名
        // （"将尝试 smtp.xxx / imap.xxx"），比在这里重述一句更精确
        return view;
    }

    private void applyEndpoint(ProviderInfoView view, Endpoint endpoint, boolean smtp) {
        if (endpoint == null) {
            return;
        }
        if (smtp) {
            view.setSmtpHost(endpoint.host());
            view.setSmtpPort(endpoint.port());
            view.setSmtpSsl(endpoint.ssl());
        } else {
            view.setImapHost(endpoint.host());
            view.setImapPort(endpoint.port());
            view.setImapSsl(endpoint.ssl());
        }
    }

    private List<String> displayOf(List<Endpoint> endpoints) {
        return endpoints.stream().map(Endpoint::display).collect(Collectors.toList());
    }

    // ==================== 一键绑定 ====================

    /**
     * 一键绑定。
     * <p>
     * 刻意<b>不加</b> {@code @Transactional}：这个方法的主体是两次网络探测，
     * 耗时可长达数十秒。包进事务会让一个数据库连接在整个探测期间被占着，
     * 与 {@code ImapReceiveServiceImpl} 里"收信不带事务"是同一条理由。
     * </p>
     * <p>
     * 去掉事务在这里没有代价 —— 方法里只有一条 insert，本身即原子；
     * 前面的唯一性检查即便被并发绕过，库上的唯一键仍是最终保障。
     * </p>
     */
    @Override
    public QuickBindResult quickBind(Long userId, QuickBindRequest req) {
        String address = req.getEmailAddress().trim();
        if (mailAccountMapper.selectByUserIdAndEmail(userId, address) != null) {
            throw new RuntimeException("该邮箱已绑定，请直接修改或先解绑");
        }

        Detection detection = providerCatalog.detect(address, true);
        Provider provider = detection.getProvider();

        if (!detection.isSmtpSupported() && !detection.isImapSupported()) {
            throw new RuntimeException(provider == null
                    ? "未能确定该邮箱的收发服务器，请展开「手动配置」自行填写"
                    : provider.getDisplayName() + " 不支持标准的邮件收发协议。"
                    + nullToEmpty(provider.getWarning()));
        }

        boolean wantReceive = req.getReceiveEnabled() == null || req.getReceiveEnabled();
        String password = req.getPassword().trim();

        // 首次探测
        Probing probing = probe(address, password, detection, wantReceive);

        // 授权码里夹带空格是复制粘贴的常见副产物（Gmail 的应用专用密码本身
        // 就是 4 组 4 位、带空格展示的）。首次探测若因认证失败而全灭，
        // 就用去掉空格的重试一次 —— 而不是一上来就替用户删掉空格，
        // 那会破坏一个本来正确的、确实含空格的密码
        if (probing.allAuthFailed() && containsInternalWhitespace(password)) {
            String compact = password.replaceAll("\\s+", "");
            System.out.println("[MailAccount] 首次探测认证失败且授权码含空格，去空格后重试");
            Probing retry = probe(address, compact, detection, wantReceive);
            if (retry.anyOk()) {
                probing = retry;
                password = compact;
            }
        }

        if (!probing.anyOk()) {
            throw new RuntimeException(buildFailureMessage(detection, probing));
        }

        MailAccount account = new MailAccount();
        account.setUserId(userId);
        account.setEmailAddress(address);
        account.setDisplayName(trimToNull(req.getDisplayName()));
        account.setEnabled(1);
        account.setImapLastUid(0L);

        QuickBindResult result = new QuickBindResult();

        // 只把真正验证通过的一侧写入配置。这是与两个既有查询的契约：
        // selectPrimaryForSend 要求 smtp_host 非空、selectAllEnabledForSync
        // 要求 imap_host 非空 —— 没通过验证的一侧留空，发信/收信流程
        // 会自动跳过它，而不是拿着一个连不上的地址反复失败
        if (probing.smtp != null && probing.smtp.isOk()) {
            Endpoint endpoint = probing.smtp.getWorking();
            account.setSmtpHost(endpoint.host());
            account.setSmtpPort(endpoint.port());
            account.setSmtpSsl(endpoint.ssl());
            account.setSmtpUsername(address);
            account.setSmtpPasswordEnc(cryptoUtil.encrypt(password));
            result.setSmtpOk(true);
            result.setSmtpMessage(probing.smtp.summary());
        } else if (probing.smtp != null) {
            result.setSmtpOk(false);
            result.setSmtpMessage(probing.smtp.summary());
            result.addNotice("发信（SMTP）未通过验证，该邮箱暂时只能收信不能发信。"
                    + "常见原因是服务商未开启 SMTP 服务。");
        }

        if (probing.imap != null && probing.imap.isOk()) {
            Endpoint endpoint = probing.imap.getWorking();
            account.setImapHost(endpoint.host());
            account.setImapPort(endpoint.port());
            account.setImapSsl(endpoint.ssl());
            account.setImapUsername(address);
            account.setImapPasswordEnc(cryptoUtil.encrypt(password));
            result.setImapOk(true);
            result.setImapMessage(probing.imap.summary());
        } else if (!wantReceive) {
            result.addNotice("按你的选择，该邮箱只用于发信，不会收取来信。");
        } else if (probing.imap != null) {
            result.setImapOk(false);
            result.setImapMessage(probing.imap.summary());
            result.addNotice("收信（IMAP）未通过验证，该邮箱暂时只能发信不能收信。"
                    + "部分企业邮箱默认关闭 IMAP，需联系管理员开启。");
        } else {
            // 用户要收信，但该服务商压根不提供 IMAP（detect 阶段就没产出候选）
            result.setImapOk(false);
            result.addNotice("该邮箱服务商不提供 IMAP 收信通道，这个邮箱将只用于对外发信。");
        }

        // 只在"没识别出来"时提示。识别成功且两侧都通过时不再多说一句 ——
        // 那时候用户要的只是一个"成了"，多余的说明反而要再点一次"知道了"
        if (!detection.isRecognized()) {
            result.addNotice("未能识别该邮箱的服务商，服务器地址是由连接测试试出来的。"
                    + "若之后收发异常，可展开「手动配置」核对。");
        }

        mailAccountMapper.insert(account);
        result.setAccount(MailAccountView.from(account));

        System.out.println("[MailAccount] 用户#" + userId + " 一键绑定 " + address
                + " SMTP=" + (result.isSmtpOk() ? "OK" : "未通过")
                + " IMAP=" + (result.isImapOk() ? "OK" : "未通过"));
        return result;
    }

    /** 一次完整的双协议探测 */
    private Probing probe(String address, String password, Detection detection, boolean wantReceive) {
        CompletableFuture<ProbeResult> smtpFuture = detection.isSmtpSupported()
                ? CompletableFuture.supplyAsync(() -> connectionProber.probeSmtp(
                        detection.getSmtpCandidates(), address, password), probeExecutor)
                : CompletableFuture.completedFuture(null);

        // IMAP 与 SMTP 并行：两者互不依赖，串行会让绑定的等待时间翻倍
        CompletableFuture<ProbeResult> imapFuture =
                (wantReceive && detection.isImapSupported())
                        ? CompletableFuture.supplyAsync(() -> connectionProber.probeImap(
                                detection.getImapCandidates(), address, password), probeExecutor)
                        : CompletableFuture.completedFuture(null);

        return new Probing(join(smtpFuture), join(imapFuture));
    }

    /**
     * 等待探测结果。
     * <p>
     * 探测自身有超时，理论上不会卡住；但线程池在极端排队下可能迟迟不调度，
     * 此时宁可让这次绑定失败，也不能把 HTTP 请求线程永久挂起。
     * </p>
     */
    private ProbeResult join(CompletableFuture<ProbeResult> future) {
        try {
            return future.get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("[MailAccount] 等待探测结果失败: " + MailErrors.describe(e));
            return null;
        }
    }

    /**
     * 构造失败提示。
     * <p>
     * 认证失败时把该服务商的授权码获取路径直接附上 —— 这是整个流程里
     * 用户唯一需要"知道点什么"的地方，与其让他自己搜，不如直接告诉他。
     * </p>
     */
    private String buildFailureMessage(Detection detection, Probing probing) {
        boolean authProblem = probing.allAuthFailed();

        StringBuilder sb = new StringBuilder();
        sb.append(authProblem
                ? "授权码验证未通过。如果你填的是邮箱的登录密码，请改为授权码"
                + "（Gmail 叫「应用专用密码」，网易/QQ 叫「授权码」）。"
                : "无法连接到邮箱服务器，请检查网络后重试。");

        if (probing.smtp != null) {
            sb.append("\n发信：").append(probing.smtp.summary());
        }
        if (probing.imap != null) {
            sb.append("\n收信：").append(probing.imap.summary());
        }

        Provider provider = detection.getProvider();
        if (authProblem && provider != null) {
            List<String> steps = provider.getGuideSteps();
            if (steps != null && !steps.isEmpty()) {
                sb.append("\n\n获取授权码的步骤：");
                for (int i = 0; i < steps.size(); i++) {
                    sb.append("\n").append(i + 1).append(". ").append(steps.get(i));
                }
            }
            if (provider.getGuideUrl() != null) {
                sb.append("\n入口：").append(provider.getGuideUrl());
            }
        }
        return sb.toString();
    }

    /** 空格是否出现在首尾之外（首尾空格直接 trim 即可，不需要重试） */
    private boolean containsInternalWhitespace(String s) {
        String trimmed = s.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isWhitespace(trimmed.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** 一次双协议探测的结果对 */
    private static final class Probing {
        final ProbeResult smtp;
        final ProbeResult imap;

        Probing(ProbeResult smtp, ProbeResult imap) {
            this.smtp = smtp;
            this.imap = imap;
        }

        boolean anyOk() {
            return (smtp != null && smtp.isOk()) || (imap != null && imap.isOk());
        }

        /** 是否"试过的每一条都是认证失败" —— 决定了提示该说"授权码错了"还是"网络不通" */
        boolean allAuthFailed() {
            boolean tried = false;
            if (smtp != null) {
                tried = true;
                if (!smtp.isAuthFailed()) {
                    return false;
                }
            }
            if (imap != null) {
                tried = true;
                if (!imap.isAuthFailed()) {
                    return false;
                }
            }
            return tried;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @Override
    @Transactional
    public MailAccountView update(Long userId, Long accountId, MailAccountRequest req) {
        MailAccount account = requireOwned(userId, accountId);
        String address = req.getEmailAddress().trim();

        // 改邮箱地址时要重新检查唯一性（唯一键在库上，但提前给出可读的报错）
        if (!address.equals(account.getEmailAddress())) {
            MailAccount other = mailAccountMapper.selectByUserIdAndEmail(userId, address);
            if (other != null) {
                throw new RuntimeException("该邮箱已绑定，请直接修改或先解绑");
            }
        }
        boolean mailboxChanged = isMailboxChanged(account, address, req);

        account.setEmailAddress(address);
        applyRequest(account, req, account);
        mailAccountMapper.updateById(account);

        // 换了邮箱（或换了 IMAP 服务器/登录名）就是换了一个信箱：
        // UID 由每个邮箱各自分配，旧水位线在新信箱里毫无意义 ——
        // 拿旧值当起点会跳过新信箱里 UID 更小的邮件（永久丢信），
        // 也可能把已收过的重拉一遍。归零后从回溯范围重新同步。
        // 注意这里是"可能是另一个信箱"，授权码变更不算，见 isMailboxChanged
        if (mailboxChanged) {
            System.out.println("[MailAccount] 账户#" + accountId
                    + " 的信箱配置已变更，重置 IMAP 水位线");
            mailAccountMapper.resetSyncState(accountId);
            // 同步清掉内存里的旧状态，让本次响应就反映"从未同步过"，
            // 而不是把旧信箱的报错回显给用户。updateById 已执行完毕，
            // 这几行只影响返回值，不会落库
            account.setImapLastUid(0L);
            account.setLastSyncTime(null);
            account.setLastSyncStatus(null);
            account.setLastSyncError(null);
        }
        return MailAccountView.from(account);
    }

    /**
     * 本次修改是否指向了另一个信箱。
     * <p>
     * 比较的是"决定拉取哪个信箱"的三个字段。授权码变更<b>不算</b> ——
     * 换授权码不影响 UID 空间，重置水位线只会把已收过的邮件重拉一遍。
     * </p>
     */
    private boolean isMailboxChanged(MailAccount current, String newAddress, MailAccountRequest req) {
        if (!newAddress.equals(current.getEmailAddress())) {
            return true;
        }
        if (!equalsTrimmed(req.getImapHost(), current.getImapHost())) {
            return true;
        }
        // 未填写的登录名会回落成邮箱地址，因此用实际生效值比较
        String newUsername = isBlank(req.getImapUsername())
                ? newAddress : req.getImapUsername().trim();
        String oldUsername = isBlank(current.getImapUsername())
                ? current.getEmailAddress() : current.getImapUsername();
        return !newUsername.equals(oldUsername);
    }

    private static boolean equalsTrimmed(String a, String b) {
        String left = a == null ? null : a.trim();
        String right = b == null ? null : b.trim();
        if (left == null || left.isEmpty()) {
            return right == null || right.isEmpty();
        }
        return left.equals(right);
    }

    @Override
    @Transactional
    public void delete(Long userId, Long accountId) {
        MailAccount account = requireOwned(userId, accountId);
        mailAccountMapper.deleteById(accountId);
        System.out.println("[MailAccount] 用户#" + userId + " 解绑邮箱 " + account.getEmailAddress());
    }

    // ==================== 本域地址（无需授权码） ====================

    @Override
    @Transactional
    public MailAccountView bindCloudflareAddress(Long userId, CloudflareBindRequest req) {
        if (!inbound.isEnabled()) {
            throw new RuntimeException("本实例未开启本域邮箱收信功能，请联系管理员部署 Cloudflare Email Routing"
                    + "（见 Cloudflare.md）");
        }
        String address = resolveLocalAddress(req.getAddress());

        // 同一个人重复领同一个地址：先给出明确提示，而不是等唯一键报一个数据库层面的错
        if (mailAccountMapper.selectByUserIdAndEmail(userId, address) != null) {
            throw new RuntimeException("你已经拥有该地址了");
        }

        MailAccount account = new MailAccount();
        account.setUserId(userId);
        account.setEmailAddress(address);
        account.setProviderType(MailAccount.PROVIDER_CLOUDFLARE);
        account.setDisplayName(trimToNull(req.getDisplayName()));
        account.setEnabled(1);
        // 与绑定外部邮箱保持一致：水位线字段置 0 而不是留 null。
        // 本域地址不参与 IMAP 轮询，这个值不会被读到，但留 null 会让
        // 管理端列表上出现一个意义不明的空白
        account.setImapLastUid(0L);

        try {
            mailAccountMapper.insert(account);
        } catch (DuplicateKeyException e) {
            // uk_user_email 上面已经挡过了，能走到这里就是 uk_cloudflare_address ——
            // 这个地址被<b>别人</b>领走了。对外只说这一句：具体是哪个唯一键冲突
            // 属于别人的数据，不该从报错里漏出去
            throw new RuntimeException("该地址已被占用，请换一个");
        }

        System.out.println("[MailAccount] 用户#" + userId + " 领取本域地址 " + address);
        return MailAccountView.from(account);
    }

    /**
     * 把用户输入解析成一个合法的本域地址。
     * <p>
     * 两种写法都接受：完整地址，或只写用户名（拼上默认域名）。见
     * {@code CloudflareBindRequest#getAddress}。
     * </p>
     */
    private String resolveLocalAddress(String raw) {
        if (isBlank(raw)) {
            throw new RuntimeException("请填写要领取的地址");
        }
        String input = raw.trim();

        String address;
        if (input.contains("@")) {
            address = MailAddressUtil.normalize(input);
        } else {
            String domain = inbound.getDefaultDomain();
            if (domain == null) {
                throw new RuntimeException("本实例未配置入站域名，无法领取地址");
            }
            address = MailAddressUtil.normalize(input + "@" + domain);
        }
        if (address == null) {
            throw new RuntimeException("邮箱地址格式不正确");
        }

        String localPart = MailAddressUtil.localPartOf(address);
        if (localPart == null || !LOCAL_PART_PATTERN.matcher(localPart).matches()) {
            throw new RuntimeException("用户名只能包含小写字母、数字与 . _ + -，且首尾不能是点");
        }

        String domain = MailAddressUtil.domainOf(address);
        if (!inbound.isLocalDomain(domain)) {
            // 把允许的域名列出来：只说"域名不对"会让用户以为是拼错了，
            // 而实际原因往往是"这个地址不属于本系统"
            throw new RuntimeException("只能领取本系统域名下的地址（"
                    + String.join("、", inbound.getDomains()) + "），" + domain + " 不在其中");
        }
        return address;
    }

    @Override
    public InboundCapabilityView inboundCapabilities() {
        InboundCapabilityView view = new InboundCapabilityView();
        view.setDomains(inbound.getDomains());
        // 用 isReady 而非 isEnabled：少配了密钥或域名时，领址入口即使显示出来也收不到信
        view.setInboundEnabled(inbound.isReady());

        boolean outboundReady = outboundRelayService.isAvailable();
        view.setOutboundAvailable(outboundReady);
        view.setOutboundTransport(outboundRelayService.transportName());
        view.setMessage(describeInboundCapability(outboundReady));
        return view;
    }

    /** 生成给用户看的一句话。原因分档写清楚，避免所有异常都归到"功能不可用" */
    private String describeInboundCapability(boolean outboundReady) {
        if (!inbound.isEnabled()) {
            return "本实例未开启本域邮箱（未部署 Cloudflare Email Routing）";
        }
        if (!inbound.hasSharedSecret()) {
            return "本实例已开启本域收信，但未配置 INBOUND_SHARED_SECRET，来信无法验证来源，暂时不可用";
        }
        if (inbound.getDefaultDomain() == null) {
            return "本实例已开启本域收信，但未配置 INBOUND_DOMAINS，不知道收哪个域名的信，暂时不可用";
        }
        if (outboundReady) {
            return "可领取本系统域名下的地址。收信无需授权码；发信通过本系统的中继（"
                    + outboundRelayService.transportName() + "）";
        }
        return "可领取本系统域名下的地址，收信无需授权码。本实例未配置发信中继，"
                + "该地址暂时只能收信、不能对外发信";
    }

    @Override
    public Map<String, Object> testConnection(Long userId, Long accountId) {
        MailAccount account = requireOwned(userId, accountId);
        Map<String, Object> result = new LinkedHashMap<>();

        // ---- SMTP ----
        if (isBlank(account.getSmtpHost())) {
            result.put("smtpOk", false);
            result.put("smtpError", "未配置 SMTP 服务器");
        } else {
            try {
                JavaMailSenderImpl sender = connectionFactory.buildSender(
                        account, decryptSmtpPassword(account));
                sender.testConnection();
                result.put("smtpOk", true);
            } catch (Exception e) {
                result.put("smtpOk", false);
                result.put("smtpError", friendlySmtpError(account, e));
            }
        }

        // ---- IMAP ----
        if (isBlank(account.getImapHost())) {
            result.put("imapOk", false);
            result.put("imapError", "未配置 IMAP 服务器");
        } else {
            Store store = null;
            try {
                String password = decryptImapPassword(account);
                if (password == null) {
                    result.put("imapOk", false);
                    result.put("imapError", "IMAP 授权码解密失败，请重新填写授权码");
                } else {
                    store = connectionFactory.openImapStore(account, password);
                    result.put("imapOk", true);
                }
            } catch (Exception e) {
                result.put("imapOk", false);
                result.put("imapError", friendlyImapError(account, e));
            } finally {
                closeQuietly(store);
            }
        }
        return result;
    }

    @Override
    public List<MailAccountView> listAllForAdmin() {
        return mailAccountMapper.selectAllWithOwner().stream()
                .map(MailAccountView::from)
                .collect(Collectors.toList());
    }

    @Override
    public MailAccount requireById(Long accountId) {
        MailAccount account = mailAccountMapper.selectById(accountId);
        if (account == null) {
            throw new RuntimeException("邮箱账户不存在");
        }
        return account;
    }

    @Override
    @Transactional
    public void deleteForAdmin(Long accountId) {
        MailAccount account = requireById(accountId);
        mailAccountMapper.deleteById(accountId);
        System.out.println("[MailAccount] 管理员解绑账户#" + accountId
                + " (" + account.getEmailAddress() + ", 用户#" + account.getUserId() + ")");
    }

    @Override
    public MailAccount findPrimaryForSend(Long userId) {
        return mailAccountMapper.selectPrimaryForSend(userId);
    }

    @Override
    public MailAccount requireOwned(Long userId, Long accountId) {
        MailAccount account = mailAccountMapper.selectById(accountId);
        if (account == null) {
            throw new RuntimeException("邮箱账户不存在");
        }
        // 越权访问与不存在返回同一个错误信息：不泄露"这个 id 存在但不属于你"
        if (!account.getUserId().equals(userId)) {
            throw new RuntimeException("邮箱账户不存在");
        }
        return account;
    }

    @Override
    public String decryptSmtpPassword(MailAccount account) {
        // 解密失败在这里不吞异常：发信必须让用户看到"授权码失效"，
        // 而不是收到一封永远发不出去、也没有原因的邮件
        return cryptoUtil.decrypt(account.getSmtpPasswordEnc());
    }

    @Override
    public String decryptImapPassword(MailAccount account) {
        try {
            return cryptoUtil.decrypt(account.getImapPasswordEnc());
        } catch (Exception e) {
            // 轮询场景相反：一个账户的授权码失效不该让整轮同步中断
            System.err.println("[MailAccount] IMAP 授权码解密失败 accountId=" + account.getId()
                    + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public void recordSyncResult(Long accountId, String status, String error, Long lastUid) {
        mailAccountMapper.updateSyncState(accountId, LocalDateTime.now(), status,
                truncate(error, MAX_ERROR_CHARS), lastUid);
    }

    // ==================== 私有方法 ====================

    /**
     * 把请求字段合并到实体。
     *
     * @param current 修改场景下已存在的实体（用于"授权码留空即不修改"的判断）；
     *                新增场景传 null
     */
    private void applyRequest(MailAccount account, MailAccountRequest req, MailAccount current) {
        account.setDisplayName(trimToNull(req.getDisplayName()));

        account.setSmtpHost(trimToNull(req.getSmtpHost()));
        account.setSmtpPort(req.getSmtpPort());
        account.setSmtpSsl(req.getSmtpSsl() == null ? 1 : req.getSmtpSsl());
        account.setSmtpUsername(trimToNull(req.getSmtpUsername()));
        account.setSmtpPasswordEnc(resolvePassword(
                req.getSmtpPassword(), current == null ? null : current.getSmtpPasswordEnc()));

        account.setImapHost(trimToNull(req.getImapHost()));
        account.setImapPort(req.getImapPort());
        account.setImapSsl(req.getImapSsl() == null ? 1 : req.getImapSsl());
        account.setImapUsername(trimToNull(req.getImapUsername()));
        account.setImapPasswordEnc(resolvePassword(
                req.getImapPassword(), current == null ? null : current.getImapPasswordEnc()));

        // enabled 只有显式传 false 才关闭；未传时：新增默认启用，修改时保持原值
        if (req.getEnabled() != null) {
            account.setEnabled(req.getEnabled() ? 1 : 0);
        } else if (current == null) {
            account.setEnabled(1);
        } else {
            account.setEnabled(current.getEnabled());
        }
    }

    /**
     * 新授权码的最终存储值。
     * <p>
     * 留空或回传掩码都表示"不修改"—— 接口本来就不回传授权码，
     * 但前端可能把占位符当值提交，这里一并兜住。
     * 否则会把库里的密文覆盖成 "****" 这种垃圾值，且是静默发生的。
     * </p>
     */
    private String resolvePassword(String submitted, String currentCipher) {
        if (isBlank(submitted) || submitted.contains(MASK_MARKER)) {
            return currentCipher;
        }
        return cryptoUtil.encrypt(submitted.trim());
    }

    /**
     * 认证失败时的提示。
     * <p>
     * 在通用文案之外，尽量带上该服务商的具体操作路径 —— "请确认填写的是授权码"
     * 这句话对已经这么做的用户毫无帮助，而"去 设置 → 账户 → 开启 IMAP/SMTP"
     * 是照着做就能解决的。
     * </p>
     */
    private String friendlySmtpError(MailAccount account, Exception e) {
        if (MailErrors.isAuthFailure(e)) {
            return "SMTP 认证失败：请确认填写的是授权码而非登录密码"
                    + guideSuffix(account, "SMTP");
        }
        return "SMTP 连接失败：" + MailErrors.describe(e);
    }

    private String friendlyImapError(MailAccount account, Exception e) {
        if (MailErrors.isAuthFailure(e)) {
            return "IMAP 认证失败：请确认填写的是授权码而非登录密码"
                    + guideSuffix(account, "IMAP");
        }
        return "IMAP 连接失败：" + MailErrors.describe(e);
    }

    /** 把该邮箱服务商的授权码获取路径拼成一句话；查不到服务商时退回通用文案 */
    private String guideSuffix(MailAccount account, String protocol) {
        Provider provider = providerCatalog.detect(account.getEmailAddress(), false).getProvider();
        if (provider == null) {
            return "，并已在邮箱服务商后台开启 " + protocol + " 服务";
        }
        StringBuilder sb = new StringBuilder("。");
        sb.append(provider.getDisplayName()).append("：");
        List<String> steps = provider.getGuideSteps();
        if (steps != null && !steps.isEmpty()) {
            sb.append(steps.get(0));
        } else {
            sb.append("请在邮箱设置中开启 ").append(protocol).append(" 服务并生成授权码");
        }
        if (provider.getGuideUrl() != null) {
            sb.append("（").append(provider.getGuideUrl()).append("）");
        }
        return sb.toString();
    }

    private void closeQuietly(Store store) {
        if (store != null) {
            try {
                store.close();
            } catch (Exception ignored) {
                // 关闭失败无补救意义
            }
        }
    }

    private static String truncate(String s, int max) {
        return MailErrors.truncate(s, max);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}

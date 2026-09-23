package com.mailsystem.service;

import com.mailsystem.dto.CloudflareBindRequest;
import com.mailsystem.dto.InboundCapabilityView;
import com.mailsystem.dto.MailAccountRequest;
import com.mailsystem.dto.MailAccountView;
import com.mailsystem.dto.ProviderInfoView;
import com.mailsystem.dto.QuickBindRequest;
import com.mailsystem.dto.QuickBindResult;
import com.mailsystem.entity.MailAccount;

import java.util.List;
import java.util.Map;

/**
 * 邮箱账户服务 —— 两条获得邮箱的途径
 *
 * <h3>一、绑定外部邮箱（{@code IMAP_SMTP}）</h3>
 * <p>
 * 用户填自己已有邮箱的地址与授权码，系统用它发信（SMTP）与收信（IMAP）。
 * "一键绑定"会先真实探测再落库，避免留下一个连不上的账户。
 * </p>
 *
 * <h3>二、领取本域地址（{@code CLOUDFLARE}）</h3>
 * <p>
 * 用户领一个本系统域名下的地址，收信由 Cloudflare Email Routing 推送进来，
 * 发信走项目级中继。<b>全程无需授权码</b> —— 系统就是收信方本人，
 * 不存在需要登录的第三方服务器。见 {@link #bindCloudflareAddress}。
 * </p>
 */
public interface MailAccountService {

    /**
     * 当前用户的账户列表（授权码字段已脱敏）
     */
    List<MailAccountView> listForUser(Long userId, String ownerEmail);

    /**
     * 新增账户。授权码加密落库，端口缺省时按 SSL 开关填充。
     */
    MailAccountView create(Long userId, MailAccountRequest req);

    /**
     * 识别邮箱地址对应的服务商与推荐配置（不建连接、不落库）。
     *
     * @param useMx 是否允许 MX 记录反查。查询会带来最长数秒的 DNS 等待，
     *              前端"边打字边识别"时应传 false，用户点绑定时传 true
     */
    ProviderInfoView detectProvider(String emailAddress, boolean useMx);

    /**
     * 一键绑定：只凭邮箱地址与授权码完成全部配置。
     * <p>
     * 流程为"识别服务商 → 推断候选端点 → 真实连接探测 → 以连得通的那组落库"。
     * 两侧都探测失败时抛异常且不落库 —— 不留下一个每 3 分钟失败一次的账户。
     * </p>
     */
    QuickBindResult quickBind(Long userId, QuickBindRequest req);

    /**
     * 修改账户。授权码为空或含掩码标记时保持不变。
     */
    MailAccountView update(Long userId, Long accountId, MailAccountRequest req);

    /**
     * 领取一个本系统域名下的地址（{@code CLOUDFLARE} 类型账户）。
     *
     * <h3>与 {@link #quickBind} 的区别</h3>
     * <p>
     * 这里<b>没有探测、没有授权码、没有外部连接</b>。收信由 Cloudflare 在边缘
     * 接收后推给本系统，发信走项目级中继 —— 系统从不登录别人的服务器，
     * 因此没有任何"连得上连不上"需要验证。方法本身只做三件事：
     * 校验地址落在一个合法的入站域名下、确认没被别人占用、落库。
     * </p>
     * <p>
     * 唯一需要外部条件的时刻在<b>发信</b>时：本域地址自己没有 SMTP 配置，
     * 它的发信能力取决于项目级中继是否就绪。这个判断刻意不放在这里 ——
     * 中继可以事后补配，不该成为"当时没配就不让领地址"的理由。
     * </p>
     *
     * @throws RuntimeException 地址格式非法、域名不属于本实例、已被占用，
     *                          或本实例未开启入站接收
     */
    MailAccountView bindCloudflareAddress(Long userId, CloudflareBindRequest req);

    /**
     * 本域邮箱的能力说明：能不能领地址、领了能不能发信。
     * <p>
     * 供前端决定"是否显示该入口"以及"显示成什么样"。
     * </p>
     */
    InboundCapabilityView inboundCapabilities();

    /**
     * 解绑账户
     */
    void delete(Long userId, Long accountId);

    /**
     * 测试 SMTP 与 IMAP 连通性（不落库、不改状态）
     *
     * @return {@code {smtpOk, smtpError, imapOk, imapError}}
     */
    Map<String, Object> testConnection(Long userId, Long accountId);

    /**
     * 管理端：所有用户的账户列表（授权码字段已脱敏）
     */
    List<MailAccountView> listAllForAdmin();

    /**
     * 按 ID 取账户（管理端用，不做归属校验 —— 管理员可以操作任何账户）
     */
    MailAccount requireById(Long accountId);

    /**
     * 管理端解绑账户。
     * <p>
     * 与 {@link #delete} 的区别只是不做归属校验 —— 管理员是在用户
     * 自己改不动（比如彻底忘了授权码）时的兜底手段。
     * </p>
     */
    void deleteForAdmin(Long accountId);

    /**
     * 取该用户的发信账户（第一个启用且配了 SMTP 的）。
     *
     * @return 没有可用账户时返回 null
     */
    MailAccount findPrimaryForSend(Long userId);

    /**
     * 取账户并校验归属，越权访问直接抛异常。
     */
    MailAccount requireOwned(Long userId, Long accountId);

    /**
     * 解密 SMTP 授权码。解密失败会抛异常 —— 调用方（发信）必须让用户
     * 知道"授权码失效"而不是静默发不出去。
     */
    String decryptSmtpPassword(MailAccount account);

    /**
     * 解密 IMAP 授权码。解密失败返回 null，由轮询把该账户标记为失败，
     * 不影响其余账户继续同步。
     */
    String decryptImapPassword(MailAccount account);

    /**
     * 记录一次同步结果。
     *
     * @param lastUid 新的水位线；null 表示不推进水位线（同步失败时用）
     */
    void recordSyncResult(Long accountId, String status, String error, Long lastUid);
}

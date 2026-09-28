package com.mailsystem.service;

import com.mailsystem.entity.MailAccount;

/**
 * IMAP 收信服务
 */
public interface ImapReceiveService {

    /**
     * 同步单个账户。自行捕获全部异常并写入 {@code last_sync_status} /
     * {@code last_sync_error}，不向调用方抛出。
     * <p>
     * 没有配置 IMAP 服务器的账户（见 {@link MailAccount#isImapConfigured()}）
     * 直接跳过，不写任何同步状态、不产生报错。
     * </p>
     *
     * @return 本次新增入库的邮件数
     */
    int syncAccount(MailAccount account);

    /**
     * 该账户为什么不需要（也无法）同步；可以同步时返回 {@code null}。
     * <p>
     * 手动同步接口用它来回答"点了按钮，为什么什么都没发生"。本域地址的来信
     * 由 Cloudflare 推送、只绑了发信的账户没有收件箱 —— 这两者都不是故障，
     * 却被同一句"IMAP 授权码缺失或解密失败，请重新填写"报成了认证失败，
     * 用户会照着一个根本不存在的授权码去找。把判断收在收信服务里，
     * 界面与接口就不必各自猜一遍账户是什么类型。
     * </p>
     *
     * @return 面向用户的一句话，或 {@code null}（表示该账户可以同步）
     */
    String unsyncableReason(MailAccount account);

    /**
     * 同步所有启用且配置了 IMAP 的账户（定时轮询入口与手动触发共用）。
     *
     * @return 本次新增入库的邮件总数
     */
    int syncAllEnabled();
}

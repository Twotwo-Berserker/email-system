package com.mailsystem.service;

import com.mailsystem.entity.MailAccount;

/**
 * IMAP 收信服务
 */
public interface ImapReceiveService {

    /**
     * 同步单个账户。自行捕获全部异常并写入 {@code last_sync_status} /
     * {@code last_sync_error}，不向调用方抛出。
     *
     * @return 本次新增入库的邮件数
     */
    int syncAccount(MailAccount account);

    /**
     * 同步所有启用且配置了 IMAP 的账户（定时轮询入口与手动触发共用）。
     *
     * @return 本次新增入库的邮件总数
     */
    int syncAllEnabled();
}

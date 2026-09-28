package com.mailsystem.service.impl;

import com.mailsystem.entity.MailAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * "哪些账户会被同步"这条判断的测试
 *
 * <h3>为什么单独测这一层</h3>
 * <p>
 * 本域地址（收信走 Cloudflare 推送）与只在 quick-bind 里通过了 SMTP 的账户
 * 都没有 IMAP 服务器，它们不该参与收信。一旦这条判断失效，同步流程会走到
 * "授权码为空"的分支，把一次正常的"无需同步"记成 {@code AUTH_FAILED}，
 * 界面上就出现一条红色的「IMAP 授权码缺失或解密失败，请重新填写」——
 * 而本域地址从来就没有过授权码，用户只能照着一个不存在的东西反复重填。
 * </p>
 * <p>
 * 不依赖 Spring：{@code unsyncableReason} 只读实体字段；跳过路径上
 * {@code syncAccount} 刻意不碰任何依赖，所以这里可以让它们全是 null ——
 * 反过来，跳过判断一旦失灵，这个测试会以 NPE 失败。
 * </p>
 */
class ImapReceiveServiceImplTest {

    private final ImapReceiveServiceImpl service = new ImapReceiveServiceImpl();

    @Test
    @DisplayName("本域地址：跳过同步，且提示的是推送收信而不是授权码")
    void skipsCloudflareAddress() {
        MailAccount account = account("alice@twotwomail.dpdns.org", MailAccount.PROVIDER_CLOUDFLARE);

        String reason = service.unsyncableReason(account);
        assertNotNull(reason);
        assertFalse(reason.contains("授权码"), "本域地址没有授权码可填，不该往这个方向提示");

        assertEquals(0, service.syncAccount(account));
    }

    @Test
    @DisplayName("只绑了发信的账户：跳过同步，且不说成认证失败")
    void skipsAccountWithoutImapHost() {
        MailAccount account = account("someone@qq.com", MailAccount.PROVIDER_IMAP_SMTP);
        account.setSmtpHost("smtp.qq.com");

        String reason = service.unsyncableReason(account);
        assertNotNull(reason);
        // "认证失败"与"没配收信"是两件事：前者要用户重新生成授权码，
        // 后者要用户补一个服务器地址，提示语不能混
        assertFalse(reason.contains("解密失败"), "这不是授权码的问题");

        assertEquals(0, service.syncAccount(account));
    }

    @Test
    @DisplayName("配了 IMAP 服务器的账户照常同步，不会被跳过")
    void syncableAccountHasNoReason() {
        MailAccount account = account("someone@qq.com", MailAccount.PROVIDER_IMAP_SMTP);
        account.setImapHost("imap.qq.com");

        assertNull(service.unsyncableReason(account));
    }

    @Test
    @DisplayName("imap_host 只有空白字符：等同于没配")
    void blankImapHostCountsAsMissing() {
        MailAccount account = account("someone@qq.com", MailAccount.PROVIDER_IMAP_SMTP);
        account.setImapHost("   ");

        assertNotNull(service.unsyncableReason(account));
    }

    private static MailAccount account(String address, String providerType) {
        MailAccount account = new MailAccount();
        account.setId(1L);
        account.setEmailAddress(address);
        account.setProviderType(providerType);
        return account;
    }
}

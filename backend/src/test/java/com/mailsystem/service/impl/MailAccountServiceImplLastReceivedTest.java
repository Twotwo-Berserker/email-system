package com.mailsystem.service.impl;

import com.mailsystem.dto.AccountLastReceived;
import com.mailsystem.dto.MailAccountView;
import com.mailsystem.entity.MailAccount;
import com.mailsystem.mapper.MailAccountMapper;
import com.mailsystem.mapper.MailMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账户列表里"最近收信"时间的测试
 *
 * <h3>为什么单独测这一层</h3>
 * <p>
 * 一个字段要有两个来源，正是容易出错的地方：参与 IMAP 同步的账户由同步写入
 * {@code last_sync_time}，本域地址没有可轮询的收件箱，只能从收到过的信上现算。
 * 两者搞混的后果不会报错，只会让界面骗人 —— 要么本域地址永远显示
 * "尚未收到邮件"（收得到，却说没收到），要么把 A 账户的时间显示成 B 账户的。
 * </p>
 * <p>
 * 用 mock 而不是 Spring：这里要验的是"谁的值填给谁"，不是 SQL 能不能跑。
 * </p>
 */
class MailAccountServiceImplLastReceivedTest {

    private final MailAccountMapper accountMapper = mock(MailAccountMapper.class);
    private final MailMapper mailMapper = mock(MailMapper.class);

    private MailAccountServiceImpl service() {
        MailAccountServiceImpl service = new MailAccountServiceImpl();
        ReflectionTestUtils.setField(service, "mailAccountMapper", accountMapper);
        ReflectionTestUtils.setField(service, "mailMapper", mailMapper);
        return service;
    }

    @Test
    @DisplayName("本域地址的最近收信时间来自来信，IMAP 账户仍用自己的同步时间")
    void fillsLastReceivedOnlyForAccountsWithoutImap() {
        LocalDateTime syncedAt = LocalDateTime.of(2026, 9, 20, 8, 0);
        LocalDateTime receivedAt = LocalDateTime.of(2026, 9, 28, 15, 30);

        MailAccount local = account(7L, "alice@twotwomail.dpdns.org", MailAccount.PROVIDER_CLOUDFLARE);
        MailAccount bound = account(8L, "someone@qq.com", MailAccount.PROVIDER_IMAP_SMTP);
        bound.setImapHost("imap.qq.com");
        bound.setLastSyncTime(syncedAt);

        when(accountMapper.selectByUserId(1L)).thenReturn(Arrays.asList(local, bound));
        when(mailMapper.selectLastReceivedByAccountIds(anyList()))
                .thenReturn(Collections.singletonList(row(7L, receivedAt)));

        List<MailAccountView> views = service().listForUser(1L, "me@example.com");

        assertEquals(receivedAt, views.get(0).getLastSyncTime(), "本域地址应显示最近收到信的时间");
        assertEquals(syncedAt, views.get(1).getLastSyncTime(), "IMAP 账户的值不该被覆盖");
    }

    @Test
    @DisplayName("只查需要现算的账户，不把 IMAP 账户也塞进查询")
    void queriesOnlyAccountsWithoutImap() {
        MailAccount local = account(7L, "alice@twotwomail.dpdns.org", MailAccount.PROVIDER_CLOUDFLARE);
        MailAccount bound = account(8L, "someone@qq.com", MailAccount.PROVIDER_IMAP_SMTP);
        bound.setImapHost("imap.qq.com");

        when(accountMapper.selectByUserId(1L)).thenReturn(Arrays.asList(local, bound));
        when(mailMapper.selectLastReceivedByAccountIds(anyList()))
                .thenReturn(Collections.emptyList());

        service().listForUser(1L, "me@example.com");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(mailMapper).selectLastReceivedByAccountIds(captor.capture());
        assertEquals(Collections.singletonList(7L), captor.getValue());
    }

    @Test
    @DisplayName("一个账户都没收到过信：保持为空，界面才能显示尚未收到邮件")
    void leavesNullWhenNothingReceivedYet() {
        MailAccount local = account(7L, "alice@twotwomail.dpdns.org", MailAccount.PROVIDER_CLOUDFLARE);

        when(accountMapper.selectByUserId(1L)).thenReturn(Collections.singletonList(local));
        // 聚合查询里 MAX() 对空组不产生行，因此这里返回空列表而不是 null 值
        when(mailMapper.selectLastReceivedByAccountIds(anyList()))
                .thenReturn(Collections.emptyList());

        List<MailAccountView> views = service().listForUser(1L, "me@example.com");

        assertNull(views.get(0).getLastSyncTime());
    }

    @Test
    @DisplayName("全是 IMAP 账户时压根不查来信表")
    void doesNotQueryWhenNoLocalAddress() {
        MailAccount bound = account(8L, "someone@qq.com", MailAccount.PROVIDER_IMAP_SMTP);
        bound.setImapHost("imap.qq.com");

        when(accountMapper.selectByUserId(1L)).thenReturn(Collections.singletonList(bound));

        service().listForUser(1L, "me@example.com");

        verify(mailMapper, org.mockito.Mockito.never()).selectLastReceivedByAccountIds(anyList());
    }

    private static MailAccount account(Long id, String address, String providerType) {
        MailAccount account = new MailAccount();
        account.setId(id);
        account.setUserId(1L);
        account.setEmailAddress(address);
        account.setProviderType(providerType);
        return account;
    }

    private static AccountLastReceived row(Long accountId, LocalDateTime lastReceived) {
        AccountLastReceived row = new AccountLastReceived();
        row.setAccountId(accountId);
        row.setLastReceived(lastReceived);
        return row;
    }
}

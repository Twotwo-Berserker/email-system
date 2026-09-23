package com.mailsystem.service.impl;

import com.mailsystem.entity.Mail;
import com.mailsystem.entity.MailAccount;
import com.mailsystem.entity.MailStatus;
import com.mailsystem.event.MailAnalysisEvent;
import com.mailsystem.mapper.MailAccountMapper;
import com.mailsystem.mapper.MailMapper;
import com.mailsystem.mapper.MailStatusMapper;
import com.mailsystem.service.AttachmentService;
import com.mailsystem.service.ImapReceiveService;
import com.mailsystem.service.MailAccountService;
import com.mailsystem.util.MailConnectionFactory;
import com.mailsystem.util.MailErrors;
import com.mailsystem.util.MimeParser;
import com.mailsystem.util.ParsedMail;
import com.mailsystem.websocket.MailNotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Store;
import javax.mail.UIDFolder;
import javax.mail.search.ComparisonTerm;
import javax.mail.search.ReceivedDateTerm;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * IMAP 收信实现 —— 轮询 INBOX + UID 水位线增量 + {@code Message-ID} 去重
 *
 * <h3>增量拉取</h3>
 * <p>
 * 每个账户维护一个 {@code imap_last_uid} 水位线，每次只取
 * {@code UID > last_uid} 的邮件。首次同步（水位线为 0）用
 * {@code app.imap.fetch-days} 限定回溯范围，并按
 * {@code app.imap.max-fetch-per-run} 截断 —— 否则一个用了十年的邮箱
 * 会在第一次轮询时把整个收件箱拉进来。
 * </p>
 *
 * <h3>水位线只在成功时推进</h3>
 * <p>
 * 同步中途失败时必须保持原水位线。若失败也推进，那批没入库的邮件会因为
 * {@code UID <= last_uid} 而<b>永远</b>不再被拉取，等于静默丢信。因此
 * {@code recordSyncResult} 在失败路径上传 {@code null} 水位线。
 * </p>
 *
 * <h3>并发保护</h3>
 * <p>
 * 同一账户不允许并发同步：水位线是"读-改-写"，两个线程并发会互相覆盖，
 * 导致丢信。用 {@link #inFlightAccounts} 做账户级互斥，而不是给整个方法
 * 加锁 —— 账户之间相互独立，串行只会互相拖慢。
 * </p>
 *
 * <h3>事务</h3>
 * <p>
 * 落库刻意<b>不加</b> {@code @Transactional}：去重依赖唯一键冲突抛
 * {@link DuplicateKeyException}，而在事务里这会把自己所在的整个事务标记为
 * rollback-only，后续写入全部失败。这里每封邮件独立提交，一封重复或解析
 * 失败不影响其余邮件。
 * </p>
 */
@Service
public class ImapReceiveServiceImpl implements ImapReceiveService {

    /** 错误信息写入 {@code last_sync_error} 时的截断长度（列宽 512） */
    private static final int MAX_ERROR_CHARS = 500;

    /** 等待单个账户同步完成的超时时间 */
    private static final long SYNC_TIMEOUT_MINUTES = 10L;

    @Autowired
    private MailAccountMapper mailAccountMapper;

    @Autowired
    private MailMapper mailMapper;

    @Autowired
    private MailStatusMapper mailStatusMapper;

    @Autowired
    private MailAccountService mailAccountService;

    @Autowired
    private MailConnectionFactory connectionFactory;

    @Autowired
    private AttachmentService attachmentService;

    /** MIME 解析与 Cloudflare 收信链路共用（见 {@link MimeParser}） */
    @Autowired
    private MimeParser mimeParser;

    /** WebSocket 在无 Broker 的场景下可能不可用，缺失时降级为只落库 */
    @Autowired(required = false)
    private MailNotificationService notificationService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    @Qualifier("mailSyncExecutor")
    private ThreadPoolTaskExecutor syncExecutor;

    /** 首次同步回溯天数 */
    @Value("${app.imap.fetch-days:7}")
    private int fetchDays;

    /** 单轮单账户最多处理的邮件数 */
    @Value("${app.imap.max-fetch-per-run:200}")
    private int maxFetchPerRun;

    /** 正在同步的账户 ID —— 账户级互斥，防止水位线读改写竞争 */
    private final Set<Long> inFlightAccounts = ConcurrentHashMap.newKeySet();

    /**
     * 定时轮询入口。
     * <p>
     * 用 {@code fixedDelay} 而非 {@code fixedRate}：前者是"上一次执行结束后
     * 再等 N 毫秒"，后者是"每 N 毫秒启动一次"。同步耗时取决于邮箱大小与
     * 网络，用 fixedRate 会在某次变慢时开始堆叠执行。
     * </p>
     * <p>
     * 首次执行刻意延迟（默认 30 秒），让应用完成启动与建连 —— 启动瞬间就
     * 去连 IMAP，一旦失败会在日志里留下误导性的报错。
     * </p>
     */
    @Scheduled(fixedDelayString = "${app.imap.sync-interval-ms:180000}",
            initialDelayString = "${app.imap.initial-delay-ms:30000}")
    public void scheduledSync() {
        try {
            syncAllEnabled();
        } catch (Exception e) {
            // 定时任务里逃逸的异常 Spring 会记日志，但不会中断后续调度。
            // 这里显式打印以带上 [ImapReceive] 前缀，便于按模块检索
            System.err.println("[ImapReceive] 本轮同步异常: " + e.getMessage());
        }
    }

    @Override
    public int syncAllEnabled() {
        List<MailAccount> accounts = mailAccountMapper.selectAllEnabledForSync();
        if (accounts.isEmpty()) {
            return 0;
        }

        // 账户之间并行：串行会让"某个账户的服务器很慢"拖累其他所有账户的收信
        List<CompletableFuture<Integer>> futures = new ArrayList<>();
        for (MailAccount account : accounts) {
            futures.add(CompletableFuture.supplyAsync(() -> syncAccount(account), syncExecutor));
        }

        int total = 0;
        for (CompletableFuture<Integer> future : futures) {
            try {
                // 给足时间但不无限等：卡住的任务不该让调度线程永久挂起
                total += future.get(SYNC_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            } catch (Exception e) {
                System.err.println("[ImapReceive] 等待同步任务结果失败: " + e.getMessage());
            }
        }
        return total;
    }

    @Override
    public int syncAccount(MailAccount account) {
        if (!inFlightAccounts.add(account.getId())) {
            System.out.println("[ImapReceive] 账户#" + account.getId() + " 正在同步中，跳过本次");
            return 0;
        }
        try {
            return doSync(account);
        } catch (Exception e) {
            String reason = MailErrors.truncate(MailErrors.describe(e), MAX_ERROR_CHARS);
            System.err.println("[ImapReceive] 账户#" + account.getId() + " ("
                    + account.getEmailAddress() + ") 同步失败: " + reason);
            // 失败不推进水位线，见类注释
            mailAccountService.recordSyncResult(account.getId(),
                    MailErrors.isAuthFailure(e) ? MailAccount.SYNC_AUTH_FAILED : MailAccount.SYNC_FAILED,
                    reason, null);
            return 0;
        } finally {
            inFlightAccounts.remove(account.getId());
        }
    }

    // ==================== 同步主流程 ====================

    private int doSync(MailAccount account) throws Exception {
        String password = mailAccountService.decryptImapPassword(account);
        if (password == null || password.isEmpty()) {
            mailAccountService.recordSyncResult(account.getId(), MailAccount.SYNC_AUTH_FAILED,
                    "IMAP 授权码缺失或解密失败，请重新填写", null);
            return 0;
        }

        Store store = null;
        Folder inbox = null;
        try {
            store = connectionFactory.openImapStore(account, password);
            inbox = store.getFolder("INBOX");
            // READ_ONLY：本项目只读不写，不设置 \Seen 标记 ——
            // 否则用户在手机/网页端会看到邮件"被读过了"
            inbox.open(Folder.READ_ONLY);

            long maxUid = currentWatermark(account);
            int saved = 0;

            for (Message message : collectCandidates(account, inbox)) {
                long uid = uidOf(inbox, message);
                try {
                    if (saveIncoming(account, message, uid)) {
                        saved++;
                    }
                    // 重复邮件（走到了这里的 return false）库里已有，
                    // 水位线照常推进
                    maxUid = Math.max(maxUid, uid);
                } catch (DuplicateKeyException e) {
                    // 唯一键拦下的重复：正常情况（IMAP 服务器重复投递，
                    // 或上一轮拉取后水位线未能推进），不作为错误
                    System.out.println("[ImapReceive] 邮件已存在，跳过 uid=" + uid);
                    maxUid = Math.max(maxUid, uid);
                } catch (Exception e) {
                    // 单封解析/落库失败不该中断整轮，也不该把水位线推过它 ——
                    // 推过去这封信就永远拉不回来了。break 让本轮到此为止，
                    // 下一轮从这封重新开始
                    System.err.println("[ImapReceive] 处理邮件失败 uid=" + uid + ": " + e.getMessage());
                    break;
                }
            }

            mailAccountService.recordSyncResult(account.getId(), MailAccount.SYNC_SUCCESS, null, maxUid);
            System.out.println("[ImapReceive] 账户#" + account.getId() + " ("
                    + account.getEmailAddress() + ") 同步完成，新增 " + saved + " 封，水位线 " + maxUid);
            return saved;
        } finally {
            closeQuietly(inbox, store);
        }
    }

    /**
     * 选出本轮要处理的邮件，按 UID 升序（即时间顺序）。
     * <p>
     * 排序是必要的：水位线必须单调推进，乱序处理时"先处理大 UID 再处理小
     * UID"会让水位线停在中间值，导致重复拉取。
     * </p>
     */
    private List<Message> collectCandidates(MailAccount account, Folder inbox) throws Exception {
        long watermark = currentWatermark(account);
        Message[] messages;

        if (watermark > 0) {
            // 增量：取 UID 大于水位线的全部邮件
            messages = ((UIDFolder) inbox).getMessagesByUID(watermark + 1, UIDFolder.LASTUID);
        } else {
            // 首次同步：按天回溯，避免把历史邮件全部拉进来
            Date since = Date.from(LocalDateTime.now().minusDays(Math.max(fetchDays, 1))
                    .atZone(ZoneId.systemDefault()).toInstant());
            messages = inbox.search(new ReceivedDateTerm(ComparisonTerm.GE, since));
        }

        List<Message> list = new ArrayList<>(Arrays.asList(messages));
        list.sort((a, b) -> Long.compare(uidOf(inbox, a), uidOf(inbox, b)));

        if (list.size() > maxFetchPerRun) {
            System.out.println("[ImapReceive] 本次待处理 " + list.size() + " 封，按上限截断到 "
                    + maxFetchPerRun + " 封，剩余部分下轮继续");
            return list.subList(0, maxFetchPerRun);
        }
        return list;
    }

    /**
     * 解析并落库一封外部来信。
     *
     * @return 是否新入库（解析前发现已存在时返回 false）
     */
    private boolean saveIncoming(MailAccount account, Message message, long uid) throws Exception {
        // 前置去重：省掉一次解析正文与下载附件的开销（唯一键是最终保障）
        // 解析前先取一次 Message-ID，代价只是一次头部读取
        String messageId = mimeParser.normalizeMessageId(headerOf(message, "Message-ID"));
        if (messageId != null && mailMapper.countByExternalMsgId(messageId) > 0) {
            return false;
        }

        ParsedMail parsed = mimeParser.parse(message);

        Mail mail = new Mail();
        // 外部来信没有本站发件人 —— 刻意不建"影子用户"，
        // 否则用户列表、登录、配额等以 user 为中心的逻辑都会被污染
        mail.setSenderId(null);
        mail.setSenderEmail(parsed.getFromAddress() == null ? "unknown@external" : parsed.getFromAddress());
        // receiver_ids 是 NOT NULL：把归属用户填进去，这样既有的收件箱查询
        // （INNER JOIN mail_status）无需改动即可命中
        mail.setReceiverIds(String.valueOf(account.getUserId()));
        mail.setSubject(parsed.getSubject() == null || parsed.getSubject().isEmpty()
                ? "(无主题)" : parsed.getSubject());
        mail.setBody(parsed.resolveBody());
        mail.setSendTime(parsed.getSentTime() == null ? LocalDateTime.now() : parsed.getSentTime());
        mail.setStatus(1);
        mail.setDirection(Mail.DIRECTION_EXTERNAL);
        mail.setExternalFrom(parsed.getFromAddress());
        mail.setExternalTo(account.getEmailAddress());
        mail.setExternalMsgId(parsed.getMessageId());
        mail.setAccountId(account.getId());
        mail.setImapUid(uid);
        mailMapper.insert(mail);

        storeAttachments(parsed, mail.getId());

        MailStatus status = new MailStatus();
        status.setMailId(mail.getId());
        status.setUserId(account.getUserId());
        status.setIsRead(0);
        status.setIsDeleted(0);
        status.setSyncStatus(0);
        mailStatusMapper.insert(status);

        if (notificationService != null) {
            notificationService.notifyNewMail(account.getUserId(), mail.getId(),
                    mail.getSenderEmail(), mail.getSubject());
        }

        // 触发智能分析。收信流程刻意不带事务（一次同步含网络 IO 与附件落盘，
        // 包进事务会长时间占用连接），因此这里依赖监听方的
        // fallbackExecution = true 才能生效 —— 少了它，收进来的外部邮件
        // 永远不会被分析，而站内发信一切正常
        eventPublisher.publishEvent(new MailAnalysisEvent(
                mail.getId(), null, Collections.singletonList(account.getUserId())));

        return true;
    }

    /** 附件落库：单个附件失败不该让整封信收不到 */
    private void storeAttachments(ParsedMail parsed, Long mailId) {
        for (ParsedMail.ParsedAttachment incoming : parsed.getAttachments()) {
            try {
                attachmentService.storeIncoming(
                        incoming.getFileName(), incoming.getContentType(), incoming.getData(), mailId);
            } catch (Exception e) {
                System.err.println("[ImapReceive] 附件保存失败 " + incoming.getFileName()
                        + ": " + e.getMessage());
            }
        }
    }

    // ==================== 工具方法 ====================

    private long currentWatermark(MailAccount account) {
        return account.getImapLastUid() == null ? 0L : account.getImapLastUid();
    }

    private long uidOf(Folder folder, Message message) {
        try {
            return ((UIDFolder) folder).getUID(message);
        } catch (Exception e) {
            // 拿不到 UID 时返回 -1：Math.max 不会取它，水位线不受影响
            return -1L;
        }
    }

    private String headerOf(Message message, String name) {
        try {
            String[] values = message.getHeader(name);
            return (values == null || values.length == 0) ? null : values[0];
        } catch (Exception e) {
            return null;
        }
    }

    private void closeQuietly(Folder folder, Store store) {
        if (folder != null && folder.isOpen()) {
            try {
                folder.close(false);
            } catch (Exception ignored) {
                // 关闭失败无补救意义
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

}

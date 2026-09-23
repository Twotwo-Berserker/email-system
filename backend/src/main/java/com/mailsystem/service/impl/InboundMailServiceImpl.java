package com.mailsystem.service.impl;

import com.mailsystem.dto.InboundResultView;
import com.mailsystem.entity.Mail;
import com.mailsystem.entity.MailAccount;
import com.mailsystem.entity.MailStatus;
import com.mailsystem.event.MailAnalysisEvent;
import com.mailsystem.mapper.MailAccountMapper;
import com.mailsystem.mapper.MailMapper;
import com.mailsystem.mapper.MailStatusMapper;
import com.mailsystem.service.AttachmentService;
import com.mailsystem.service.InboundMailService;
import com.mailsystem.util.MailAddressUtil;
import com.mailsystem.util.MimeParser;
import com.mailsystem.util.ParsedMail;
import com.mailsystem.websocket.MailNotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;

/**
 * 入站收信实现 —— Cloudflare Email Routing 推送 → 解析 → 落库
 *
 * <h3>与 IMAP 链路的关系</h3>
 * <p>
 * 两条链路产出完全相同的库内形态（{@code direction=EXTERNAL}、
 * {@code sender_id=NULL}、{@code external_*} 字段齐全、一条 {@code mail_status}），
 * 因此收件箱查询、已读标记、智能分析、WebSocket 推送全部无需改动即可复用。
 * 唯一的差别是 {@code imap_uid} 为空 —— 那是 IMAP 专有的去重兜底键，
 * 推送链路靠 {@code Message-ID} 就足够了。
 * </p>
 *
 * <h3>为什么不用 {@code @Transactional}</h3>
 * <p>
 * 与 {@link ImapReceiveServiceImpl} 同一条理由：一次收信包含 MIME 解析与
 * 附件落盘（可能几十 MB），包进事务会让数据库连接被这些非数据库操作
 * 长时间占住。去重依赖唯一键冲突抛 {@link DuplicateKeyException}，
 * 而在事务里这会把自己所在的整个事务标记为 rollback-only，后续写入全部失败。
 * </p>
 *
 * <h3>为什么不用 {@code @Async}</h3>
 * <p>
 * 反直觉但重要：<b>Worker 在等这个响应</b>。异步返回"已接收"会让
 * "地址不存在"永远无法变成退信 —— 投递失败被吞掉，发件人毫不知情。
 * 单封邮件的处理是解析 + 几次插入，在百毫秒量级，同步完成是合适的。
 * </p>
 */
@Service
public class InboundMailServiceImpl implements InboundMailService {

    @Autowired
    private MailAccountMapper mailAccountMapper;

    @Autowired
    private MailMapper mailMapper;

    @Autowired
    private MailStatusMapper mailStatusMapper;

    @Autowired
    private AttachmentService attachmentService;

    @Autowired
    private MimeParser mimeParser;

    /** WebSocket 在无 Broker 的场景下可能不可用，缺失时降级为只落库 */
    @Autowired(required = false)
    private MailNotificationService notificationService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    public InboundResultView receive(String envelopeFrom, String envelopeTo, byte[] rawBytes) {
        String address = MailAddressUtil.normalize(envelopeTo);
        if (address == null) {
            return InboundResultView.unknownRecipient(String.valueOf(envelopeTo));
        }

        MailAccount account = mailAccountMapper.selectInboundAddress(address);
        if (account == null) {
            System.out.println("[Inbound] 无归属地址 " + address + "，已拒收（发件人 " + envelopeFrom + "）");
            return InboundResultView.unknownRecipient(address);
        }
        if (account.getEnabled() == null || account.getEnabled() != 1) {
            System.out.println("[Inbound] 地址 " + address + " 已停用，已拒收（发件人 " + envelopeFrom + "）");
            return InboundResultView.disabled(address);
        }

        ParsedMail parsed;
        try {
            parsed = mimeParser.parse(rawBytes);
        } catch (Exception e) {
            // 解析失败是这封信本身的问题，重试也不会变好 ——
            // 但把它当成"投递成功"会静默丢信，因此仍回 5xx，
            // 由 Worker 抛出、Cloudflare 退信给发件人
            throw new RuntimeException("MIME 解析失败: " + e.getMessage(), e);
        }

        // 前置去重：省掉一次附件落盘的开销（唯一键是最终保障）
        if (parsed.getMessageId() != null
                && mailMapper.countByExternalMsgId(parsed.getMessageId()) > 0) {
            System.out.println("[Inbound] 邮件已存在，跳过 msgId=" + parsed.getMessageId());
            return InboundResultView.duplicate();
        }

        try {
            Long mailId = store(account, address, parsed);
            System.out.println("[Inbound] " + address + " 收到来自 "
                    + parsed.getFromAddress() + " 的邮件，mailId=" + mailId);
            return InboundResultView.delivered(mailId);
        } catch (DuplicateKeyException e) {
            // 并发投递：两封信同时通过了上面的前置检查。
            // 唯一键挡下了第二封，正常返回而不是报错
            System.out.println("[Inbound] 并发重复投递，已忽略 msgId=" + parsed.getMessageId());
            return InboundResultView.duplicate();
        }
    }

    // ==================== 落库 ====================

    private Long store(MailAccount account, String address, ParsedMail parsed) {
        Mail mail = new Mail();
        // 外部来信没有本站发件人 —— 刻意不建"影子用户"，
        // 否则用户列表、登录、配额等以 user 为中心的逻辑都会被污染
        mail.setSenderId(null);
        mail.setSenderEmail(parsed.getFromAddress() == null
                ? "unknown@external" : parsed.getFromAddress());
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
        // 落信封收件人而非 To 头：本域地址常是别名，信封地址才是真实投递目标
        mail.setExternalTo(address);
        mail.setExternalMsgId(parsed.getMessageId());
        mail.setAccountId(account.getId());
        // imap_uid 留空：那是 IMAP 专有的兜底去重键，推送链路用不到。
        // uk_account_uid(account_id, imap_uid) 是唯一索引，而 MySQL
        // 不约束 NULL，因此多封推送来信不会互相冲突
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

        // 触发智能分析。收信流程刻意不带事务，因此这里依赖监听方的
        // fallbackExecution = true 才能生效 —— 少了它，推送进来的邮件
        // 永远不会被分析，而站内发信一切正常
        eventPublisher.publishEvent(new MailAnalysisEvent(
                mail.getId(), null, Collections.singletonList(account.getUserId())));

        return mail.getId();
    }

    /** 附件落库：单个附件失败不该让整封信收不到 */
    private void storeAttachments(ParsedMail parsed, Long mailId) {
        for (ParsedMail.ParsedAttachment incoming : parsed.getAttachments()) {
            try {
                attachmentService.storeIncoming(
                        incoming.getFileName(), incoming.getContentType(), incoming.getData(), mailId);
            } catch (Exception e) {
                System.err.println("[Inbound] 附件保存失败 " + incoming.getFileName()
                        + ": " + e.getMessage());
            }
        }
    }

}

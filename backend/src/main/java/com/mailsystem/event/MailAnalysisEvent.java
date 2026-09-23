package com.mailsystem.event;

import java.util.Collections;
import java.util.List;

/**
 * 请求对一封邮件做智能分析（事务提交后触发）
 *
 * <h3>为什么与 {@link MailSentEvent} 分开而不是复用它</h3>
 * <p>
 * 两者都由"邮件已入库"触发，但语义不同：
 * </p>
 * <ul>
 *   <li>{@code MailSentEvent} 表达的是"这封信要投递出去"，
 *       携带外部地址与发信账户，只有发信路径会产生它</li>
 *   <li>本事件表达的是"这封信要分析"，
 *       携带收件人列表，<b>收信路径（IMAP）同样会产生它</b></li>
 * </ul>
 * <p>
 * 若复用一个事件，{@code MailDeliveryListener} 就必须在收到 IMAP 来信时
 * 自己判断"这不是我该处理的"，而且那个判断依赖 {@code externalRecipients}
 * 恰好为空这一巧合 —— 一旦将来支持"收到后自动转发"，这个巧合就不成立了，
 * 表现是<b>把收到的邮件又原样发回给外部发件人</b>。
 * </p>
 *
 * <h3>为什么按收件人列表而不是单个用户</h3>
 * <p>
 * 结论是按收件人分别计算的（各自用自己的 API Key），但触发点只有一个。
 * 事件里带上完整列表，监听方一次拿到全部目标，避免为每个收件人各发一个事件 ——
 * 那样一封信发给 20 个人就会在事务提交后产生 20 次事件分发。
 * </p>
 * <p>
 * 本类<b>不可变</b>：异步消费者与发布方分处不同线程。
 * </p>
 */
public class MailAnalysisEvent {

    private final Long mailId;

    /** 发件人；IMAP 来信时为 null */
    private final Long senderId;

    /** 需要产出结论的站内收件人（已去重） */
    private final List<Long> recipientUserIds;

    public MailAnalysisEvent(Long mailId, Long senderId, List<Long> recipientUserIds) {
        this.mailId = mailId;
        this.senderId = senderId;
        this.recipientUserIds = recipientUserIds == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(recipientUserIds);
    }

    public Long getMailId() {
        return mailId;
    }

    public Long getSenderId() {
        return senderId;
    }

    public List<Long> getRecipientUserIds() {
        return recipientUserIds;
    }
}

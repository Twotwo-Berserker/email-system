package com.mailsystem.event;

import com.mailsystem.service.impl.AnalysisDispatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 监听"邮件待分析"事件，把任务交给分析线程池。
 *
 * <h3>为什么是 {@code AFTER_COMMIT} 而不是 {@code @Async}</h3>
 * <p>
 * 这是整条分析管线最容易做错的一处。<b>只加 {@code @Async} 是不够的</b>：
 * {@code @Async} 会立刻把任务丢给线程池，而发信方法上有 {@code @Transactional}，
 * 此刻事务<b>尚未提交</b>。分析线程随后按 {@code mailId} 查库，查不到那一行 ——
 * 分析静默失败，邮件永远没有分类，日志上什么也看不出来。
 * </p>
 * <p>
 * {@code AFTER_COMMIT} 把触发推迟到事务提交之后，从根上消除了这个竞态。
 * </p>
 *
 * <h3>为什么需要 {@code fallbackExecution = true}</h3>
 * <p>
 * {@code AFTER_COMMIT} 在没有事务上下文时<b>默认不触发</b>。IMAP 收信流程
 * 刻意不带事务（一次同步包含网络 IO 与附件落盘，包进事务会长时间占用连接），
 * 缺了这个参数，收进来的外部邮件就永远不会被分析 —— 而站内发信一切正常，
 * 属于最难排查的那种"只在一条路径上坏掉"。
 * </p>
 *
 * <h3>为什么监听方法本身不是 {@code @Async}</h3>
 * <p>
 * 见 {@link AnalysisDispatcher} 的说明：{@code @Async} 的拒绝会以异常形式
 * 从事件发布方抛出去，而这里需要的是"池满时降级"而不是"发信失败"。
 * </p>
 */
@Component
public class MailAnalysisListener {

    @Autowired
    private AnalysisDispatcher dispatcher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMailAnalysisRequested(MailAnalysisEvent event) {
        dispatcher.dispatch(event.getMailId(), event.getRecipientUserIds());
    }
}

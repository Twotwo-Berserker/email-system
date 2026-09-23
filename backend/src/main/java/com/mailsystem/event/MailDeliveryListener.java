package com.mailsystem.event;

import com.mailsystem.entity.Mail;
import com.mailsystem.service.MailSendService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 外部邮件投递监听器
 * <p>
 * 只处理"存在外部收件人"的情况；纯站内邮件在这里直接返回，
 * 不产生任何线程开销。
 * </p>
 *
 * <h3>三个注解缺一不可</h3>
 * <ul>
 *   <li>{@link TransactionalEventListener}({@code AFTER_COMMIT}) ——
 *       在发信事务<b>提交之后</b>才投递。若在事务内投递，SMTP 的网络往返
 *       会占着数据库连接；若事务随后回滚，外部收件人已经收到了这封信，
 *       出现"外部收到了、站内没有"的不一致</li>
 *   <li>{@code fallbackExecution = true} ——
 *       {@code AFTER_COMMIT} 在<b>没有事务上下文时默认不触发</b>。
 *       加上它，将来若有非事务路径发布同一事件，投递不会静默丢失</li>
 *   <li>{@link Async} —— 提交之后仍在请求线程上，不异步化会让
 *       {@code POST /mail/send} 的响应时间等于一次 SMTP 往返</li>
 * </ul>
 * <p>
 * 注意这三个注解的组合是安全的：{@code @Async} 与 {@code @TransactionalEventListener}
 * 可以共存（危险的是 {@code @Async} 与 {@code @Transactional} 标在同一方法上，
 * 那会让事务在另一个线程里开启）。
 * </p>
 */
@Component
public class MailDeliveryListener {

    @Autowired
    private MailSendService mailSendService;

    @Async("mailDeliveryExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMailSent(MailSentEvent event) {
        if (!event.hasExternalRecipients()) {
            return;
        }
        try {
            // 只取投递需要的字段，不让事件承担整个实体
            Mail mail = new Mail();
            mail.setId(event.getMailId());
            mail.setSenderId(event.getSenderId());
            mail.setSubject(event.getSubject());
            mail.setBody(event.getBody());
            mail.setAccountId(event.getAccountId());

            mailSendService.sendExternal(mail, event.getExternalRecipients());
        } catch (Exception e) {
            // 监听器里逃逸的异常在 @Async 下只会被记进日志，
            // 这里显式打印以便定位（sendExternal 自身已把失败写进 mail.external_error）
            System.err.println("[MailDelivery] 邮件#" + event.getMailId()
                    + " 投递过程异常: " + e.getMessage());
        }
    }
}

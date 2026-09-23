package com.mailsystem.service.impl;

import com.mailsystem.service.MailAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * 把分析任务提交到有界线程池，并在池满时就地降级。
 *
 * <h3>为什么不直接在监听方法上写 {@code @Async}</h3>
 * <p>
 * {@code @Async} 的拒绝发生在<b>代理调用处</b>，也就是事件发布方的线程上，
 * 异常会从 {@code publishEvent} 里抛出去 —— 那可能是正在处理发信请求的
 * HTTP 线程，一次"线程池满了"会变成一个发送失败。而且这个异常发生在
 * Spring 的事件多播器内部，业务代码没有合适的 catch 位置。
 * </p>
 * <p>
 * 因此改成显式提交：{@link #dispatch} 自己 try/catch，拒绝时同步跑一次
 * <b>纯 CPU、无网络</b>的规则兜底（{@code analyzeWithRulesOnly}）。
 * 这样"池满"的表现从"这封邮件没有分类"变成"这封邮件的分类来自规则"，
 * 两种降级里后者显然可以接受。
 * </p>
 *
 * <h3>与 {@code AsyncConfig} 的约定</h3>
 * <p>
 * 这个类是 {@code AsyncConfig} 里"用 AbortPolicy + 在分发处 catch"那段注释
 * 所指的分发处。两者要一起看：池改策略，这里就得跟着改。
 * </p>
 * <p>
 * 注意 {@code TaskRejectedException}（Spring 在池已关闭时抛的）继承自
 * {@code RejectedExecutionException}，因此一个 catch 覆盖两种情况。
 * </p>
 */
@Component
public class AnalysisDispatcher {

    @Autowired
    @Qualifier("analysisExecutor")
    private ThreadPoolTaskExecutor analysisExecutor;

    @Autowired
    private MailAnalysisService analysisService;

    /**
     * 提交一次分析。
     * <p>
     * 本方法<b>不阻塞</b>（正常路径下只是把任务放进队列），可以在事件发布线程上调用。
     * </p>
     */
    public void dispatch(Long mailId, List<Long> recipientUserIds) {
        if (mailId == null || recipientUserIds == null || recipientUserIds.isEmpty()) {
            return;
        }
        try {
            analysisExecutor.execute(
                    () -> analysisService.analyze(mailId, recipientUserIds));
        } catch (RejectedExecutionException e) {
            System.out.println("[AnalysisDispatcher] 分析线程池已满，邮件 " + mailId
                    + " 就地降级为规则结论（收件人 " + recipientUserIds.size() + " 个）");
            try {
                analysisService.analyzeWithRulesOnly(mailId, recipientUserIds);
            } catch (Exception fallbackError) {
                // 兜底也失败：这封邮件暂时没有分类，但写入端与发信端都不受影响。
                // 用户可以手动重跑（POST /mail/{id}/reanalyze）
                System.err.println("[AnalysisDispatcher] 规则兜底也失败，邮件 " + mailId
                        + ": " + fallbackError.getMessage());
            }
        }
    }
}

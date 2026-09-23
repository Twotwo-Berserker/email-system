package com.mailsystem.service;

import com.mailsystem.dto.MailAnalysisView;

import java.util.List;

/**
 * 邮件智能分析服务 —— 分类 / 垃圾识别 / 优先级 / 摘要的唯一入口。
 *
 * <h3>职责边界</h3>
 * <p>
 * 本服务负责"结论怎么来的"：选 Prompt、决定走 LLM 还是规则兜底、
 * 容错重试、落库、记账、通知缓存失效。
 * </p>
 * <p>
 * 列表页上的展示<b>不</b>经过本服务：那四项结论由 {@code MailMapper} 的查询
 * 用 {@code COALESCE} 覆盖到 {@code Mail} 的原有字段上（详见 {@code MailMapper}
 * 的类注释）。只有详情页的分析面板需要额外组装，走 {@link #viewFor}。
 * </p>
 *
 * <h3>为什么按收件人而不是按邮件</h3>
 * <p>
 * 每个用户用的是自己的 API Key（BYO-Key），因此同一封邮件对不同收件人
 * 可能得出不同结论，甚至是不同来源（有 Key 的走 LLM、没 Key 的走规则）。
 * 结论存在 {@code mail_intelligence_result(mail_id, user_id)} 上，
 * 而不是回写 {@code mail} 表的单值列。
 * </p>
 *
 * <h3>调用方式</h3>
 * <p>
 * 生产路径上由 {@code MailAnalysisListener} 在事务提交<b>之后</b>提交给线程池；
 * 手动重跑由接口触发、在请求线程上同步执行。
 * 本服务的写入方法都<b>不</b>带 {@code @Transactional}：见
 * {@code MailAnalysisServiceImpl} 的说明。
 * </p>
 */
public interface MailAnalysisService {

    /**
     * 为一批收件人分析同一封邮件。
     * <p>
     * 单个收件人失败不影响其余人：每个收件人独立 try/catch。
     * </p>
     */
    void analyze(Long mailId, List<Long> recipientUserIds);

    /**
     * 重新分析某收件人的某封邮件，跳过"内容未变则不重跑"的幂等检查。
     *
     * @return 是否真的完成了一次分析（邮件不存在或无权限时返回 false）
     */
    boolean reanalyze(Long mailId, Long userId);

    /**
     * 只用规则插件产出结论，<b>不做任何网络调用</b>。
     * <p>
     * 留给线程池已满时的就地兜底：分析池用的是 {@code AbortPolicy}
     * （见 {@code AsyncConfig}），被拒绝的任务不能就这么丢掉 ——
     * 丢掉的表现是收件人永远看不到分类，且没有任何报错。
     * 本方法因为不碰网络，可以安全地在请求线程上同步执行，
     * 几十毫秒即返回。
     * </p>
     * <p>
     * 产出的结论标注 {@code source='RULE'}、{@code error_code='OVERLOADED'}。
     * </p>
     */
    void analyzeWithRulesOnly(Long mailId, List<Long> recipientUserIds);

    /**
     * 组装详情页的分析面板。
     * <p>
     * 需要三份数据：分析结果行、当前用户的反馈、以及可供纠正的分类白名单。
     * 把它们拼起来是数据组装而不是业务规则，但仍然放进服务层 ——
     * 放在控制器里会让"授权校验"与"字段回退"这类容易出错的逻辑散在
     * 每个接口上。
     * </p>
     * <p>
     * <b>不做授权校验</b>：调用方必须先确认这封邮件与该用户有关
     * （通常是已经调过 {@code MailService.getMailDetail}）。这里只读
     * {@code (mailId, userId)} 这一对，读不到就是空视图，不会泄露别人的结论。
     * </p>
     *
     * @return 永不返回 null；没有分析结果时返回一个字段为空、{@code status=null} 的视图
     */
    MailAnalysisView viewFor(Long mailId, Long userId);
}

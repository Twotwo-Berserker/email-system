package com.mailsystem.plugin;

import com.mailsystem.entity.Mail;

/**
 * 智能分析插件接口 —— 所有规则插件必须实现此接口。
 *
 * <h3>从"改数据库"改为"返回计算结果"</h3>
 * <p>
 * 原签名是 {@code void process(Mail mail)}：插件自己写库、自己起异步线程。
 * 这在实际使用中暴露了三个问题，改造为 {@link #contribute(Mail)} 后一并解决：
 * </p>
 * <ol>
 *   <li><b>写竞态</b> —— 两个插件写同一列（{@code mail.summary}）且都是
 *       {@code @Async}，结果由线程调度决定，无人能复现</li>
 *   <li><b>结论丢失</b> —— 算出来却找不到地方放的中间结果只能被丢掉
 *       （见 {@link RuleContribution} 的说明）</li>
 *   <li><b>无法按收件人区分</b> —— 写 {@code mail} 表天然是"每封邮件一份结论"，
 *       而同一封邮件发给多个人时，每人的结论应当各自独立</li>
 * </ol>
 *
 * <h3>实现约定</h3>
 * <ul>
 *   <li><b>纯函数</b>：不得写数据库、不得发网络请求、不得起线程。
 *       {@code contribute} 会被同步调用在分析线程上</li>
 *   <li><b>不要在实现里判断开关</b>：{@link #isEnabled()} 由调用方
 *       （{@code RuleAnalyzer}）统一检查，实现只需如实回报状态</li>
 *   <li>对某项没有意见时返回 null 字段，不要填"看起来合理"的默认值 ——
 *       默认值由 {@code RuleAnalyzer} 统一决定，否则多个插件会互相覆盖</li>
 * </ul>
 */
public interface PluginInterface {

    /**
     * 插件名称（唯一标识，与 {@code plugin_config.plugin_name} 对应）
     */
    String getName();

    /**
     * 计算本插件对分析结论的贡献。
     * <p>
     * <b>必须是纯计算。</b>返回 null 与返回一个空贡献等价，
     * 调用方都按"无贡献"处理。
     * </p>
     *
     * @param mail 待分析的邮件（实现不得修改它的字段）
     */
    RuleContribution contribute(Mail mail);

    /**
     * 插件是否已启用（读 {@code plugin_config}）
     */
    boolean isEnabled();
}

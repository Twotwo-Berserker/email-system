package com.mailsystem.util;

import javax.mail.AuthenticationFailedException;

/**
 * 邮件协议异常的归类与描述
 *
 * <h3>为什么要单独抽出来</h3>
 * <p>
 * "这次失败到底是授权码错了，还是网络不通"是本项目里被问得最多的一个判断，
 * 它决定了给用户看什么提示：前者要引导他去重新生成授权码，后者只能建议他检查网络。
 * 这个判断原先在 {@code MailAccountServiceImpl}、{@code ImapReceiveServiceImpl}
 * 里各写了一份，加上自动探测就成了三份 —— 三份实现一旦漂移，
 * 同一次失败会在"测试连接"里显示为认证错误、在"自动收信"里显示为网络错误。
 * </p>
 *
 * <h3>为什么要遍历整个 cause 链</h3>
 * <p>
 * JavaMail 会把底层异常层层包装：{@code MessagingException} →
 * {@code AuthenticationFailedException} → 服务端的原始响应。
 * 只看最外层会漏判 —— 很多时候最外层只是一句
 * "Couldn't connect to host"，真正的原因在三层之下。
 * </p>
 */
public final class MailErrors {

    private MailErrors() {
    }

    /**
     * 是否为认证失败（授权码/密码错误，或服务商未开启对应协议）。
     * <p>
     * 除了异常类型，还要看消息文本：不同服务商的包装方式不一，
     * 有的直接抛 {@link AuthenticationFailedException}，
     * 有的只在 message 里带 "LOGIN failed" / "AUTHENTICATE" /
     * "Unsafe Login"（网易系）这类字样。
     * </p>
     */
    public static boolean isAuthFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof AuthenticationFailedException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("authentication failed") || lower.contains("login failed")
                        || lower.contains("authenticate") || lower.contains("invalid credentials")
                        // 网易系：未开启 IMAP 服务或未发 ID 命令时会回这句
                        || lower.contains("unsafe login")
                        // Gmail：未使用应用专用密码时回 "Invalid login: 535-5.7.8"
                        || lower.contains("535-5.7.8")
                        // 部分服务商：账号被禁用客户端登录
                        || lower.contains("client is not enabled")
                        || lower.contains("not enabled for imap")
                        || lower.contains("not enabled for smtp")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 取最内层异常的信息 —— JavaMail 会把根因（如 ConnectException）层层包装。
     * <p>
     * 不外抛、不截断：截断长度取决于调用方要写入哪一列（列宽不同），
     * 由调用方自己决定。
     * </p>
     */
    public static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return (msg == null || msg.isEmpty()) ? root.getClass().getSimpleName() : msg;
    }

    /** 按列宽截断 */
    public static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}

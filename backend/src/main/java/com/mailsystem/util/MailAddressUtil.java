package com.mailsystem.util;

import java.util.Locale;

/**
 * 邮箱地址的规范化 —— 收信与领址两条路径共用同一套规则
 *
 * <h3>为什么必须共用</h3>
 * <p>
 * "收信时拿到的地址"和"用户领取时输入的地址"最终要在
 * {@code mail_account.email_address} 上对上。两边只要有一处处理得不一样，
 * 结果就是<b>地址领了却收不到信</b> —— 而且从界面上完全看不出问题：
 * 账户列表里那个地址好好地待着，Cloudflare 那边的投递日志也显示成功。
 * </p>
 *
 * <h3>规则</h3>
 * <ul>
 *   <li>去掉 {@code <...>} 尖括号 —— Cloudflare 传过来的信封收件人可能带</li>
 *   <li>转小写 —— RFC5321 理论上 local-part 区分大小写，但没有哪家服务商真的这么做，
 *       而用户输入时大小写是随意的</li>
 *   <li>长度超 {@value #MAX_ADDRESS_LENGTH} 直接判为无效，而<b>不是截断后去查</b> ——
 *       截断会造出一个合法但错误的地址</li>
 * </ul>
 */
public final class MailAddressUtil {

    /** {@code mail_account.email_address} 的列宽 */
    public static final int MAX_ADDRESS_LENGTH = 128;

    private MailAddressUtil() {
    }

    /**
     * 规范化一个邮件地址。
     *
     * @return 规范化后的地址；无法构成有效地址时返回 {@code null}
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String address = raw.trim();

        int open = address.lastIndexOf('<');
        int close = address.lastIndexOf('>');
        if (open >= 0 && close > open) {
            address = address.substring(open + 1, close).trim();
        }
        if (address.isEmpty() || address.length() > MAX_ADDRESS_LENGTH) {
            return null;
        }
        return address.toLowerCase(Locale.ROOT);
    }

    /** 域名部分（{@code @} 之后），取不到时返回 {@code null} */
    public static String domainOf(String address) {
        if (address == null) {
            return null;
        }
        int at = address.lastIndexOf('@');
        if (at < 0 || at == address.length() - 1) {
            return null;
        }
        return address.substring(at + 1).toLowerCase(Locale.ROOT);
    }

    /** 用户名部分（{@code @} 之前），取不到时返回 {@code null} */
    public static String localPartOf(String address) {
        if (address == null) {
            return null;
        }
        int at = address.lastIndexOf('@');
        if (at <= 0) {
            return null;
        }
        return address.substring(0, at);
    }
}

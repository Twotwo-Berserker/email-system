package com.mailsystem.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 入站收信配置 —— {@code app.inbound.*}
 *
 * <h3>为什么收在一个类里</h3>
 * <p>
 * 这些值有三个使用方：接收接口（{@code InboundMailController}，校验签名与大小）、
 * 收信流程（{@code InboundMailServiceImpl}，判断地址是否有归属）、
 * 领取地址（{@code MailAccountServiceImpl}，校验域名是否属于本实例）。
 * 各写各的 {@code @Value} 会漂移成"接口认为开着、服务认为关着"这类
 * 只在特定配置组合下才暴露的问题。
 * </p>
 *
 * <h3>为什么域名是字符串而不是数组</h3>
 * <p>
 * 值的来源是 {@code .env} / 容器环境变量，那里只有"一个字符串"这一种形态
 * （{@code INBOUND_DOMAINS=a.com,b.com}）。用 {@code List<String>} 接会在
 * 空值时得到 {@code [""]} 这种既非空又无意义的结果，反倒要在每处再判一次。
 * </p>
 */
@Component
public class InboundProperties {

    /**
     * 是否接受入站投递。
     * <p>
     * 默认关。没做 Cloudflare 部署的实例不该对外暴露一个匿名写接口 ——
     * 端口一旦从内网开到公网，它就是最容易被盯上的那个入口。
     * </p>
     */
    @Value("${app.inbound.enabled:false}")
    private boolean enabled;

    /** 与 Cloudflare Worker 共享的密钥，用于校验 webhook 签名 */
    @Value("${app.inbound.shared-secret:}")
    private String sharedSecret;

    /** 允许接收来信的域名，逗号分隔。如 {@code mail.example.com,mail.example.org} */
    @Value("${app.inbound.domains:}")
    private String domains;

    /** 原始请求体上限（字节），防止一个超大 POST 把内存吃满 */
    @Value("${app.inbound.max-message-bytes:26214400}")
    private int maxMessageBytes;

    public boolean isEnabled() {
        return enabled;
    }

    public String getSharedSecret() {
        return sharedSecret;
    }

    public int getMaxMessageBytes() {
        return maxMessageBytes;
    }

    /** 共享密钥是否已配置。缺了它签名校验无从谈起，接收接口会直接拒绝服务 */
    public boolean hasSharedSecret() {
        return sharedSecret != null && !sharedSecret.trim().isEmpty();
    }

    /** 配好的入站域名，已转小写并去除空白项 */
    public List<String> getDomains() {
        if (domains == null || domains.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String part : Arrays.asList(domains.split(","))) {
            String domain = part.trim().toLowerCase(Locale.ROOT);
            if (!domain.isEmpty()) {
                result.add(domain);
            }
        }
        return result;
    }

    /**
     * 默认域名 —— 用户只填用户名时拼的那个。
     * <p>
     * 取列表第一个。多域名部署下这只是一个"打字省事"的默认值，
     * 用户仍可在界面上选别的域名，因此不需要额外的配置项去指定它。
     * </p>
     */
    public String getDefaultDomain() {
        List<String> all = getDomains();
        return all.isEmpty() ? null : all.get(0);
    }

    /** 该域名是否属于本实例配置的入站域名 */
    public boolean isLocalDomain(String domain) {
        return domain != null && getDomains().contains(domain.toLowerCase(Locale.ROOT));
    }

    /**
     * 入站链路是否可用（开关打开且两个必填项都在）。
     * <p>
     * 与 {@link #isEnabled()} 的区别：后者只是"部署方说要开"，
     * 本方法才是"确实开得起来"。判断"要不要给用户显示领址入口"必须用这个。
     * </p>
     */
    public boolean isReady() {
        return enabled && hasSharedSecret() && getDefaultDomain() != null;
    }
}

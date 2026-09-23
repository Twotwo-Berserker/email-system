package com.mailsystem.service;

import com.mailsystem.dto.PageResult;
import com.mailsystem.entity.Mail;

import java.time.Duration;

/**
 * 邮件缓存服务 —— 列表分页缓存与未读数的统一入口。
 *
 * <h3>为什么要把这几个方法从 MailServiceImpl 里抽出来</h3>
 * <p>
 * 原先 {@code evictUserCache}/{@code evictUnreadCache} 是
 * {@code MailServiceImpl} 的私有方法，只有它自己能调用。而 P3 之后
 * <b>分析完成</b>也必须驱逐缓存（否则用户最多 5 分钟看不到新生成的摘要），
 * 分析却在另一个类里。只有三个选择：把 MailServiceImpl 注入分析服务（会形成
 * 循环依赖：邮件服务 → 事件 → 分析服务 → 邮件服务），把驱逐逻辑复制一份
 * （两份实现必然发散），或者抽成这个小服务。选第三个。
 * </p>
 *
 * <h3>缓存键的命名规则集中在这里</h3>
 * <p>
 * 驱逐用的是 {@code KEYS mail:list:{userId}:*} 这样的模式匹配，
 * 它必须与写入时构造的键<b>逐字一致</b>。写入和驱逐分散在两个类里时，
 * 任何一方改了键格式都会导致"驱逐静默失效"—— 界面上表现为
 * "改了邮件但列表不变"，且没有任何报错。因此两组方法放在同一个类里。
 * </p>
 */
public interface MailCacheService {

    /** 列表分页缓存的存活时间 */
    Duration LIST_TTL = Duration.ofMinutes(5);

    /** 未读数的存活时间（比列表短：未读数是角标，越旧越刺眼） */
    Duration UNREAD_TTL = Duration.ofMinutes(2);

    /**
     * 读列表分页缓存；未命中或缓存损坏时返回 null（调用方应降级查库）
     */
    PageResult<Mail> getPage(Long userId, int type, int page, int pageSize);

    /**
     * 写列表分页缓存
     */
    void putPage(Long userId, int type, int page, int pageSize, PageResult<Mail> result);

    /**
     * 读未读数缓存；未命中返回 null
     */
    Integer getUnread(Long userId);

    /**
     * 写未读数缓存
     */
    void putUnread(Long userId, int count);

    /**
     * 驱逐某用户的<b>全部</b>列表缓存与未读数缓存。
     * <p>
     * 任何改变"某用户能看到哪些邮件、看到什么内容"的操作都必须调用它。
     * </p>
     */
    void evictAll(Long userId);

    /**
     * 只驱逐未读数（已读/未读状态变化时用，不必让整个列表缓存失效）
     */
    void evictUnread(Long userId);
}

package com.mailsystem.service.impl;

import com.mailsystem.dto.PageResult;
import com.mailsystem.entity.Mail;
import com.mailsystem.service.MailCacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 邮件缓存服务实现
 */
@Service
public class MailCacheServiceImpl implements MailCacheService {

    /** 列表缓存键前缀。驱逐时的模式匹配依赖它，因此抽成常量 */
    private static final String LIST_PREFIX = "mail:list:";

    /** 未读数缓存键前缀 */
    private static final String UNREAD_PREFIX = "mail:unread:";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public PageResult<Mail> getPage(Long userId, int type, int page, int pageSize) {
        String key = listKey(userId, type, page, pageSize);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached == null) {
                return null;
            }
            if (cached instanceof PageResult) {
                @SuppressWarnings("unchecked")
                PageResult<Mail> result = (PageResult<Mail>) cached;
                return result;
            }
            // 反序列化成了 LinkedHashMap 之类的类型 —— 说明缓存里的结构
            // 与当前代码不兼容（通常是 PageResult 的字段改过）。
            // 删掉它并降级查库，否则每次读都会走到这里
            redisTemplate.delete(key);
            return null;
        } catch (Exception e) {
            // Redis 不可用或反序列化失败都不该让请求失败：缓存是加速手段，
            // 不是数据来源。删掉可能损坏的 key 后降级
            try {
                redisTemplate.delete(key);
            } catch (Exception ignored) {
                // 连删都失败说明 Redis 整体不可用，忽略即可
            }
            return null;
        }
    }

    @Override
    public void putPage(Long userId, int type, int page, int pageSize, PageResult<Mail> result) {
        if (result == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    listKey(userId, type, page, pageSize), result, LIST_TTL);
        } catch (Exception e) {
            // 写缓存失败不影响本次请求已经查到的数据
            System.err.println("[MailCache] 写入列表缓存失败: " + e.getMessage());
        }
    }

    @Override
    public Integer getUnread(Long userId) {
        try {
            String cached = stringRedisTemplate.opsForValue().get(UNREAD_PREFIX + userId);
            if (cached == null) {
                return null;
            }
            return Integer.parseInt(cached);
        } catch (NumberFormatException e) {
            // 值被人为改坏或格式变更，删掉后重新算
            evictUnread(userId);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void putUnread(Long userId, int count) {
        try {
            stringRedisTemplate.opsForValue().set(
                    UNREAD_PREFIX + userId, String.valueOf(count),
                    UNREAD_TTL.getSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("[MailCache] 写入未读数缓存失败: " + e.getMessage());
        }
    }

    @Override
    public void evictAll(Long userId) {
        if (userId == null) {
            return;
        }
        evictList(userId);
        evictUnread(userId);
    }

    @Override
    public void evictUnread(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(UNREAD_PREFIX + userId);
        } catch (Exception e) {
            System.err.println("[MailCache] 驱逐未读数缓存失败 userId=" + userId + ": " + e.getMessage());
        }
    }

    /**
     * 按模式删除某用户的全部列表缓存。
     * <p>
     * ⚠️ {@code KEYS} 是 O(N) 的阻塞命令，在键很多时会拖慢整个 Redis。
     * 这里的规模是"单用户的几个分页键"，且调用点都在邮件状态变更之后
     * （用户可感知的操作，频率有限），因此可以接受。
     * </p>
     * <p>
     * 若将来键量增长到需要 {@code SCAN}，改这里一处即可 —— 这也是把缓存
     * 收进单个类的原因之一。
     * </p>
     */
    private void evictList(Long userId) {
        try {
            Set<String> keys = redisTemplate.keys(LIST_PREFIX + userId + ":*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            System.err.println("[MailCache] 驱逐列表缓存失败 userId=" + userId + ": " + e.getMessage());
        }
    }

    private static String listKey(Long userId, int type, int page, int pageSize) {
        return LIST_PREFIX + userId + ":" + type + ":" + page + ":" + pageSize;
    }
}

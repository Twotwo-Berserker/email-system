package com.mailsystem.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关
 * <p>
 * 全仓库原先没有任何 {@code @EnableScheduling}，因此 {@code @Scheduled} 注解
 * 一律不生效。本类为以下新增的周期任务提供开关：
 * </p>
 * <ul>
 *   <li>IMAP 收信轮询（ImapReceiveService）</li>
 *   <li>分析缓存驱逐合并器（AnalysisCacheSweeper）—— 把多个用户的分析完成事件
 *       合并成每个用户一次缓存驱逐，避免每个收件人触发一次 Redis 全库扫描</li>
 *   <li>分析结果孤儿行回收（JVM 重启导致残留的 RUNNING 行）</li>
 * </ul>
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}

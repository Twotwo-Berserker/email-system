package com.mailsystem.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 异步任务配置 — 开启 @Async 支持
 * 用于摘要生成、智能分类等异步插件
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}

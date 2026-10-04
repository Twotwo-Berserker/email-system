package com.mailsystem;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 智邮 CloudMail 后端服务入口
 *
 * 基于 Spring Boot 的全栈邮件系统：站内收发、外部邮箱接入、
 * SMTP/IMAP 协议代理与 LLM 智能分析。
 */
@SpringBootApplication
@MapperScan("com.mailsystem.mapper")
public class MailSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(MailSystemApplication.class, args);
        System.out.println("============================================");
        System.out.println("  邮件系统后端服务启动成功!");
        System.out.println("  API 地址: http://localhost:8080");
        System.out.println("============================================");
    }
}

package com.mailsystem;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 邮件系统 SpringBoot 启动类
 *
 * 2026 软件开发综合实训 - 邮件系统
 * 后端服务入口
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

package com.mailsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mailsystem.dto.LoginResponse;
import com.mailsystem.entity.User;

/**
 * 用户服务接口
 */
public interface UserService extends IService<User> {

    /**
     * 用户注册
     * @return 注册成功的用户
     */
    User register(String email, String password, String nickname);

    /**
     * 用户登录
     * <p>
     * 校验密码、账号状态，记录登录时间与IP，并返回含角色信息的响应。
     * 登录成功时若发现密码仍是历史无盐 SHA-256 哈希，会透明重写为 BCrypt。
     * </p>
     *
     * @param clientIp 客户端IP，用于登录审计
     */
    LoginResponse login(String email, String password, String clientIp);

    /**
     * 用户自助修改密码（校验原密码）
     */
    void changePassword(Long userId, String oldPassword, String newPassword);

    /**
     * 退出登录 —— 把当前 Token 的 jti 写入 Redis 吊销名单
     */
    void logout(String token);

    /**
     * 密码加密（BCrypt）
     */
    String encodePassword(String password);

    /**
     * 根据ID获取用户
     */
    User getById(Long id);

    /**
     * 根据邮箱获取用户
     */
    User getByEmail(String email);
}

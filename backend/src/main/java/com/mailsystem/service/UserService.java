package com.mailsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
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
     * @return JWT token 字符串
     */
    String login(String email, String password);

    /**
     * 密码加密
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

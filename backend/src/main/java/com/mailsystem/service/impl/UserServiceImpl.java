package com.mailsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mailsystem.entity.User;
import com.mailsystem.mapper.UserMapper;
import com.mailsystem.service.UserService;
import com.mailsystem.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 用户服务实现
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    public User register(String email, String password, String nickname) {
        // 检查邮箱是否已注册
        User exist = userMapper.selectByEmail(email);
        if (exist != null) {
            throw new RuntimeException("该邮箱已注册");
        }

        User user = new User();
        user.setEmail(email);
        user.setPassword(encodePassword(password));
        user.setNickname(nickname != null && !nickname.isEmpty() ? nickname : email.split("@")[0]);

        userMapper.insert(user);
        // 隐藏密码
        user.setPassword(null);
        return user;
    }

    @Override
    public String login(String email, String password) {
        User user = userMapper.selectByEmail(email);
        if (user == null) {
            throw new RuntimeException("邮箱或密码错误");
        }
        if (!user.getPassword().equals(encodePassword(password))) {
            throw new RuntimeException("邮箱或密码错误");
        }
        return jwtUtil.generateToken(user.getId(), user.getEmail());
    }

    @Override
    public String encodePassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("加密算法不可用", e);
        }
    }

    @Override
    public User getById(Long id) {
        User user = userMapper.selectById(id);
        if (user != null) {
            user.setPassword(null);
        }
        return user;
    }

    @Override
    public User getByEmail(String email) {
        User user = userMapper.selectByEmail(email);
        if (user != null) {
            user.setPassword(null);
        }
        return user;
    }
}

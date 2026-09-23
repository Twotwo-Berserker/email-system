package com.mailsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mailsystem.dto.LoginResponse;
import com.mailsystem.entity.User;
import com.mailsystem.mapper.UserMapper;
import com.mailsystem.service.TokenBlacklistService;
import com.mailsystem.service.UserService;
import com.mailsystem.util.JwtUtil;
import com.mailsystem.util.PasswordUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 用户服务实现
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private PasswordUtil passwordUtil;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

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
        // 新注册用户一律是普通用户。提权只能由管理员在管理端操作，
        // 不接受任何来自客户端的角色字段（注册请求体里根本没有这个字段）。
        user.setRole(User.ROLE_USER);
        user.setStatus(User.STATUS_ENABLED);
        user.setMustChangePassword(0);

        userMapper.insert(user);
        // 隐藏密码
        user.setPassword(null);
        return user;
    }

    @Override
    @Transactional
    public LoginResponse login(String email, String password, String clientIp) {
        User user = userMapper.selectByEmail(email);
        if (user == null) {
            throw new RuntimeException("邮箱或密码错误");
        }

        // 先校验密码，再检查账号状态。
        // 顺序很重要：若先查状态，未持有正确密码的人也能通过"账号已被禁用"
        // 这一提示枚举出哪些账号被禁用。
        if (!passwordUtil.matches(password, user.getPassword())) {
            throw new RuntimeException("邮箱或密码错误");
        }

        if (user.getStatus() != null && user.getStatus() == User.STATUS_DISABLED) {
            throw new RuntimeException("账号已被禁用，请联系管理员");
        }

        // 透明重哈希：历史数据是无盐单次 SHA-256，密码校验通过后
        // 立刻用同一明文重写为 BCrypt。用户无感，活跃账号自动完成升级。
        if (passwordUtil.needsRehash(user.getPassword())) {
            userMapper.updatePassword(user.getId(), passwordUtil.encode(password),
                    user.getMustChangePassword() == null ? 0 : user.getMustChangePassword());
            System.out.println("[UserService] 用户#" + user.getId() + " 密码哈希已升级为 BCrypt");
        }

        userMapper.recordLogin(user.getId(), LocalDateTime.now(), clientIp);

        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        return new LoginResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                token,
                user.getRole() == null ? User.ROLE_USER : user.getRole(),
                user.getMustChangePassword() != null && user.getMustChangePassword() == 1);
    }

    @Override
    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        if (!passwordUtil.matches(oldPassword, user.getPassword())) {
            throw new RuntimeException("原密码错误");
        }
        if (passwordUtil.matches(newPassword, user.getPassword())) {
            throw new RuntimeException("新密码不能与原密码相同");
        }
        // 自己改密后清除强制改密标记
        userMapper.updatePassword(userId, passwordUtil.encode(newPassword), 0);

        // 已知局限：这里<b>不</b>作废该用户的其他会话。
        // 按用户吊销会让当前这次会话也一起失效（无法区分"本机"与"其他设备"，
        // 除非引入会话表），而"改完密码立刻被登出"是更糟的体验。
        // 管理员重置密码走的是 AdminServiceImpl.resetPassword，那里会全量作废。
    }

    @Override
    public void logout(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        String jti = jwtUtil.getJtiFromToken(token);
        long remaining = jwtUtil.getRemainingValidityMs(token);
        tokenBlacklistService.blacklist(jti, remaining);
    }

    @Override
    public String encodePassword(String password) {
        return passwordUtil.encode(password);
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

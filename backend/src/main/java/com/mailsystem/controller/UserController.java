package com.mailsystem.controller;

import com.mailsystem.dto.ApiResponse;
import com.mailsystem.dto.ChangePasswordRequest;
import com.mailsystem.dto.LoginRequest;
import com.mailsystem.dto.LoginResponse;
import com.mailsystem.dto.RegisterRequest;
import com.mailsystem.entity.User;
import com.mailsystem.service.UserService;
import com.mailsystem.util.RequestUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

/**
 * 用户控制器 — /user/*
 */
@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private UserService userService;

    /**
     * 用户注册
     * POST /user/register
     */
    @PostMapping("/register")
    public ApiResponse<User> register(@Valid @RequestBody RegisterRequest req) {
        try {
            User user = userService.register(req.getEmail(), req.getPassword(), req.getNickname());
            return ApiResponse.ok("注册成功", user);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 用户登录
     * POST /user/login
     * <p>
     * 响应中携带 role 与 mustChangePassword：前者供前端路由守卫放行管理端入口，
     * 后者用于在首次登录（或管理员重置密码后）强制跳转改密页。
     * </p>
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req,
                                            HttpServletRequest request) {
        try {
            LoginResponse resp = userService.login(req.getEmail(), req.getPassword(),
                    RequestUtil.clientIp(request));
            return ApiResponse.ok("登录成功", resp);
        } catch (RuntimeException e) {
            return ApiResponse.error(401, e.getMessage());
        }
    }

    /**
     * 退出登录
     * POST /user/logout
     * <p>
     * 把当前 Token 的 jti 写入 Redis 吊销名单，使其在剩余有效期内立即失效。
     * 原先前端只清 localStorage，Token 仍可被继续使用直到自然过期。
     * </p>
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        userService.logout(extractToken(request));
        return ApiResponse.ok("已退出登录", null);
    }

    /**
     * 修改自己的密码
     * PUT /user/change-password
     */
    @PutMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req,
                                            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        try {
            userService.changePassword(userId, req.getOldPassword(), req.getNewPassword());
            return ApiResponse.ok("密码修改成功", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    private String extractToken(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            return token.substring(7);
        }
        return token;
    }
}

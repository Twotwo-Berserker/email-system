package com.mailsystem.controller;

import com.mailsystem.dto.ApiResponse;
import com.mailsystem.dto.LoginRequest;
import com.mailsystem.dto.LoginResponse;
import com.mailsystem.dto.RegisterRequest;
import com.mailsystem.entity.User;
import com.mailsystem.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

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
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        try {
            String token = userService.login(req.getEmail(), req.getPassword());
            User user = userService.getByEmail(req.getEmail());
            LoginResponse resp = new LoginResponse(user.getId(), user.getEmail(), user.getNickname(), token);
            return ApiResponse.ok("登录成功", resp);
        } catch (RuntimeException e) {
            return ApiResponse.error(401, e.getMessage());
        }
    }
}

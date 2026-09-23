package com.mailsystem.dto;

import com.mailsystem.entity.User;
import lombok.Data;

/**
 * 登录响应DTO（含JWT token）
 * <p>
 * 携带 {@code role} 供前端做路由守卫；携带 {@code mustChangePassword}
 * 供前端在首次登录（或管理员重置密码后）强制跳转改密页。
 * </p>
 */
@Data
public class LoginResponse {

    private Long userId;
    private String email;
    private String nickname;
    private String token;

    /** 角色：USER / ADMIN —— 前端侧栏与路由守卫据此放行管理端入口 */
    private String role;

    /** 是否必须修改密码：true 时前端应强制跳转改密页 */
    private Boolean mustChangePassword;

    public LoginResponse(Long userId, String email, String nickname, String token) {
        this(userId, email, nickname, token, User.ROLE_USER, false);
    }

    public LoginResponse(Long userId, String email, String nickname, String token,
                         String role, Boolean mustChangePassword) {
        this.userId = userId;
        this.email = email;
        this.nickname = nickname;
        this.token = token;
        this.role = role;
        this.mustChangePassword = mustChangePassword;
    }
}

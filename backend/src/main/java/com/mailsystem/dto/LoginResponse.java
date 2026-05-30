package com.mailsystem.dto;

import lombok.Data;

/**
 * 登录响应DTO（含JWT token）
 */
@Data
public class LoginResponse {

    private Long userId;
    private String email;
    private String nickname;
    private String token;

    public LoginResponse(Long userId, String email, String nickname, String token) {
        this.userId = userId;
        this.email = email;
        this.nickname = nickname;
        this.token = token;
    }
}

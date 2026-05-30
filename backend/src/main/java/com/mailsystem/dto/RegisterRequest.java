package com.mailsystem.dto;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 注册请求DTO
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "邮箱不能为空")
    private String email;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在6-32位之间")
    private String password;

    private String nickname;
}

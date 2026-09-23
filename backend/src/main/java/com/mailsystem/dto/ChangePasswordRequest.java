package com.mailsystem.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 修改密码请求
 * <p>
 * 同时服务于两个场景：
 * </p>
 * <ul>
 *   <li>用户自助改密（{@code PUT /user/change-password}）—— 必须提供 {@code oldPassword}</li>
 *   <li>管理员重置他人密码（{@code PUT /admin/users/{id}/password}）—— 不需要旧密码</li>
 * </ul>
 * <p>
 * 因此 {@code oldPassword} 不做 {@code @NotBlank}，由服务层按场景分别校验。
 * </p>
 */
@Data
public class ChangePasswordRequest {

    /** 旧密码。用户自助改密时必填；管理员重置他人密码时忽略。 */
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 32, message = "新密码长度需为 6-32 位")
    private String newPassword;
}

package com.mailsystem.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * 修改用户角色请求
 */
@Data
public class UpdateRoleRequest {

    /** 目标角色，仅允许 USER / ADMIN */
    @NotBlank(message = "角色不能为空")
    @Pattern(regexp = "USER|ADMIN", message = "角色只能是 USER 或 ADMIN")
    private String role;
}

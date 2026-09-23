package com.mailsystem.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

/**
 * 启用/禁用用户请求
 */
@Data
public class UpdateStatusRequest {

    /** 1=启用, 0=禁用 */
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能是 0 或 1")
    @Max(value = 1, message = "状态只能是 0 或 1")
    private Integer status;
}

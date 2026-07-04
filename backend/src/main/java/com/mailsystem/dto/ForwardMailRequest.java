package com.mailsystem.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 转发邮件请求DTO
 */
@Data
public class ForwardMailRequest {

    /** 收件人邮箱，逗号分隔 */
    @NotBlank(message = "收件人不能为空")
    private String receiverEmails;

    /** 抄送人邮箱，逗号分隔（可选） */
    private String ccEmails;

    /** 附加说明（可选，将添加到引用内容之前） */
    private String additionalBody;
}

package com.mailsystem.dto;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import java.util.List;

/**
 * 发送邮件请求DTO
 */
@Data
public class SendMailRequest {

    /** 收件人邮箱，逗号分隔 */
    @NotBlank(message = "收件人不能为空")
    private String receiverEmails;

    /** 抄送人邮箱，逗号分隔（可选） */
    private String ccEmails;

    @NotBlank(message = "主题不能为空")
    private String subject;

    /** 邮件正文（HTML） */
    @NotBlank(message = "正文不能为空")
    private String body;

    /** 附件ID列表（先上传附件再发送） */
    private List<Long> attachmentIds;
}

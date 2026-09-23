package com.mailsystem.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 待投递到外部的一封信（与具体发送通道无关）
 * <p>
 * 存在的意义是把"投递内容"与"投递方式"分开：本系统有两条外发通道 ——
 * 用户自己邮箱的 SMTP，以及项目级中继（Resend HTTP API / 自建 SMTP 中继）。
 * 两条通道要组装的东西完全一样（发件人、收件人、主题、正文、附件），
 * 差别只在最后一步怎么把它送出去。
 * </p>
 * <p>
 * 附件在这里已经是<b>字节</b>而不是附件 ID：读写附件要访问 MinIO 或本地磁盘，
 * 让每个通道各查一次会把同一个附件读两遍。
 * </p>
 */
@Data
public class OutboundMessage {

    /** 发件人地址。QQ/163/Gmail 与 Resend 都会校验它，必须是已验证的身份 */
    private String fromAddress;

    /** 发件人显示名，可空 */
    private String fromName;

    private List<String> to = new ArrayList<>();

    private String subject;

    private String body;

    private List<OutboundAttachment> attachments = new ArrayList<>();

    /** 组装好的附件内容 */
    @Data
    public static class OutboundAttachment {
        private final String fileName;
        private final byte[] data;

        public OutboundAttachment(String fileName, byte[] data) {
            this.fileName = fileName;
            this.data = data;
        }
    }

    /**
     * RFC5322 的 {@code From} 头值：{@code 张三 <a@b.com>} 或退化为纯地址。
     * <p>
     * 两条通道都要这个格式，因此在这里拼一次 —— 两处各拼一遍迟早会在
     * "显示名里恰好有引号/逗号"这类边界上分叉。
     * </p>
     */
    public String fromHeader() {
        if (fromName == null || fromName.trim().isEmpty()) {
            return fromAddress;
        }
        String name = fromName.trim();
        // 显示名含特殊字符时必须加引号，否则整个 From 头会被解析歪
        if (name.matches(".*[()<>@,;:\\\\\"\\[\\]].*")) {
            name = "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return name + " <" + fromAddress + ">";
    }
}

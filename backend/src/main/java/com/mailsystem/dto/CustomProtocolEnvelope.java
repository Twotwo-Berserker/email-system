package com.mailsystem.dto;

import lombok.Data;

/**
 * 自定义通信协议信封
 * 用于在 HTTP 上层封装统一的报文格式
 */
@Data
public class CustomProtocolEnvelope {

    /** 功能码，如 MAIL_SEND, MAIL_RECEIVE, MAIL_SYNC */
    private String functionCode;

    /** 发送方地址 */
    private String senderAddress;

    /** 接收者地址 */
    private String receiverAddress;

    /** 时间戳 */
    private String timestamp;

    /** 报文体长度 */
    private Integer bodyLength;

    /** 加密类型: NONE, AES256 */
    private String encryptionType;

    /** 实际载荷（请求/响应体） */
    private Object payload;

    /** HMAC签名 */
    private String signature;
}

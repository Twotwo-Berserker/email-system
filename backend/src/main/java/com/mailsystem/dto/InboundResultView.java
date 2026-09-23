package com.mailsystem.dto;

import com.mailsystem.service.InboundMailService;
import lombok.Data;

/**
 * 入站投递结果
 * <p>
 * Worker 依据 {@link #status} 决定后续动作，两者的契约是：
 * </p>
 * <ul>
 *   <li>{@code DELIVERED} / {@code DUPLICATE} —— 正常返回，Cloudflare 视为投递成功</li>
 *   <li>{@code UNKNOWN_RECIPIENT} —— Worker 调用 {@code message.setReject()}，
 *       让发件人收到退信。地址写错时这是唯一能让发件人知道的方式 ——
 *       静默丢弃会让两边都以为信寄到了</li>
 *   <li>{@code DISABLED} —— 同样是拒收，但退信理由不同（"该地址已停用"）</li>
 * </ul>
 * <p>
 * HTTP 层面的 5xx（签名错、报文无法解析、数据库不可用）由 Worker 直接抛出异常，
 * 交给 Cloudflare 重试 —— 那些是暂时性故障，不是"这封信不该收"。
 * </p>
 */
@Data
public class InboundResultView {

    private String status;

    /** 给 Worker 日志用的一句话说明 */
    private String message;

    /** 投递成功时的入库邮件 ID（便于把 Cloudflare 侧日志与站内邮件对上） */
    private Long mailId;

    public static InboundResultView of(String status, String message, Long mailId) {
        InboundResultView view = new InboundResultView();
        view.setStatus(status);
        view.setMessage(message);
        view.setMailId(mailId);
        return view;
    }

    public static InboundResultView delivered(Long mailId) {
        return of(InboundMailService.STATUS_DELIVERED, "已投递", mailId);
    }

    public static InboundResultView duplicate() {
        return of(InboundMailService.STATUS_DUPLICATE, "该邮件已存在，未重复入库", null);
    }

    public static InboundResultView unknownRecipient(String address) {
        return of(InboundMailService.STATUS_UNKNOWN_RECIPIENT,
                "本系统没有 " + address + " 这个收件地址", null);
    }

    public static InboundResultView disabled(String address) {
        return of(InboundMailService.STATUS_DISABLED,
                "收件地址 " + address + " 已被停用", null);
    }
}

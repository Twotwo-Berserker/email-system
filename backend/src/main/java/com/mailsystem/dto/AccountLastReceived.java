package com.mailsystem.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 某个邮箱账户最近一次收到来信的时间 —— 聚合查询的行类型。
 * <p>
 * 只服务于一种账户：不参与 IMAP 同步的那些（本域地址、只绑了发信的账户）。
 * 它们的 {@code mail_account.last_sync_time} 永远是空的，因为没有任何一轮
 * 同步会去写它，卡片上那行"最近收信"于是永远显示"尚未收到邮件"。
 * 这个值改为从 {@code mail} 表上现算：见
 * {@code MailMapper#selectLastReceivedByAccountIds}。
 * </p>
 * <p>
 * 独立成类型而不是返回 {@code Map<String, Object>}：字段名一旦拼错，
 * 前者是编译错误，后者是运行时的空指针。
 * </p>
 */
@Data
public class AccountLastReceived {

    /** {@code mail.account_id} */
    private Long accountId;

    /** 该账户收到的最后一封来信的 send_time */
    private LocalDateTime lastReceived;
}

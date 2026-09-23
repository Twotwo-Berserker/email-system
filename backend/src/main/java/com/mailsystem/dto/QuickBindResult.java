package com.mailsystem.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一键绑定的结果 —— 把"哪一侧通了、哪一侧没通"如实告诉用户
 * <p>
 * 之所以不是简单的成功/失败：SMTP 与 IMAP 是两条独立的链路，用户可能只关心其一。
 * 企业邮箱禁用 IMAP 是常见情况（能发不能收），此时应当<b>绑定成功</b>并说明
 * 收信不可用，而不是整体失败让用户以为自己填错了。
 * </p>
 */
@Data
public class QuickBindResult {

    /** 落库后的账户（授权码字段已脱敏） */
    private MailAccountView account;

    private boolean smtpOk;
    private String smtpMessage;

    private boolean imapOk;
    private String imapMessage;

    /**
     * 值得提醒用户的事项，如"收信未开通，仍可正常发信"。
     * <p>
     * 与错误不同 —— 这些不影响绑定成立，但用户需要知道。
     * </p>
     */
    private List<String> notices = new ArrayList<>();

    public void addNotice(String notice) {
        this.notices.add(notice);
    }
}

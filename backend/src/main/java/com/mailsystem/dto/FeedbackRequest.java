package com.mailsystem.dto;

import com.mailsystem.entity.UserFeedback;
import lombok.Data;

import javax.validation.constraints.Size;

/**
 * 用户对某封邮件的分析结论提交反馈。
 *
 * <h3>三个字段的语义是"可选覆盖"，不是"必填"</h3>
 * <ul>
 *   <li>只点 👍 → 只传 {@code feedbackType=AGREE}</li>
 *   <li>点 👎 但不想说是哪里错 → 只传 {@code feedbackType=DISAGREE}</li>
 *   <li>点 👎 并顺手把分类改对 → 再带上 {@code correctedCategory}</li>
 * </ul>
 * <p>
 * 因此除 {@code feedbackType} 外的字段都可为空。<b>为空表示"用户没提这一项"，
 * 而不是"用户认为这一项应该是空的"</b> —— 后者在界面上无法表达，
 * 也不该被表达（分类为空没有意义）。
 * </p>
 *
 * <h3>为什么没有 mailId / userId</h3>
 * <p>
 * 两者都从 URL 路径与 Token 取。放进 body 就意味着"用户可以声明这条反馈
 * 是关于哪封邮件、属于谁的"，而服务层必须再校验一遍 —— 少校验一处就是
 * 一条越权写入。
 * </p>
 */
@Data
public class FeedbackRequest {

    /** AGREE / DISAGREE，见 {@link UserFeedback#TYPE_AGREE} */
    private String feedbackType;

    /** 用户认为正确的分类；为空表示未纠正 */
    @Size(max = UserFeedback.MAX_CATEGORY_CHARS, message = "分类名过长")
    private String correctedCategory;

    /** 用户认为正确的垃圾判定（1=是垃圾, 0=否）；为空表示未纠正 */
    private Integer correctedSpam;

    /** 备注。界面上限 512 字，与列宽一致 */
    @Size(max = UserFeedback.MAX_COMMENT_CHARS, message = "备注过长（最多 512 字）")
    private String comment;
}

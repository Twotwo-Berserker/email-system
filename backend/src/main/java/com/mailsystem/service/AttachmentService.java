package com.mailsystem.service;

import com.mailsystem.entity.Attachment;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 附件服务接口
 */
public interface AttachmentService {

    /**
     * 上传附件
     */
    Attachment uploadAttachment(MultipartFile file);

    /**
     * 某封邮件的附件列表（外发时逐个读取内容作为 MIME 附件）
     */
    List<Attachment> attachmentList(Long mailId);

    /**
     * 存储一封外部来信的附件（IMAP 收信路径）。
     * <p>
     * 与 {@link #uploadAttachment} 的区别有两点：入口是字节数组而非
     * {@code MultipartFile}（MIME 解析出来的附件本来就在内存里），以及
     * 落库时直接绑定 {@code mailId}（收信流程先插 {@code mail} 再存附件，
     * 不存在"上传时还不知道属于哪封信"的问题）。存储后端的选择逻辑
     * （MinIO 优先、本地回退）与上传路径一致。
     * </p>
     *
     * @param mailId 所属邮件 ID，必须是已落库的 mail.id
     * @return 已落库且已绑定 mailId 的附件记录
     */
    Attachment storeIncoming(String fileName, String contentType, byte[] data, Long mailId);

    /**
     * 读取附件的归属校验：只有该附件所属邮件的收件人或发件人能读。
     * <p>
     * <b>这是对外暴露附件内容的唯一入口。</b>任何把附件返回给客户端的代码路径
     * 都必须先调用本方法取得附件实体，再调用 {@link #getAttachmentData} ——
     * 后者不做任何归属校验（它还要服务 SMTP 外发那条内部路径）。
     * </p>
     *
     * @param userId 当前登录用户；为 null 一律拒绝
     * @throws RuntimeException 附件不存在、或当前用户不是该附件所属邮件的相关人。
     *                          <b>两种情况报同一句话</b>：区分开会让本接口变成
     *                          "探测某个附件 ID 是否存在的工具"
     */
    Attachment requireReadable(Long attachmentId, Long userId);

    /**
     * 获取附件存储的字节数据。
     * <p>
     * <b>不做归属校验。</b>它服务两条路径：居中的 {@code requireReadable} 校验通过后
     * 的实际读取，以及 SMTP 外发时按 {@code mailId} 取已授权邮件的附件内容。
     * 新增调用点前请确认你手上已经有一封"确认过权限"的邮件。
     * </p>
     */
    byte[] getAttachmentData(Long attachmentId);
}

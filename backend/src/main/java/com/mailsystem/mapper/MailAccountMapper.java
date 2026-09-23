package com.mailsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mailsystem.entity.MailAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 邮箱账户 Mapper（注解式，与本项目其余 Mapper 一致）
 */
@Mapper
public interface MailAccountMapper extends BaseMapper<MailAccount> {

    /**
     * 某用户的全部账户
     */
    @Select("SELECT * FROM mail_account WHERE user_id = #{userId} ORDER BY id ASC")
    List<MailAccount> selectByUserId(@Param("userId") Long userId);

    /**
     * 某用户指定邮箱地址的账户（唯一键 uk_user_email 保证最多一条）
     */
    @Select("SELECT * FROM mail_account WHERE user_id = #{userId} AND email_address = #{emailAddress} LIMIT 1")
    MailAccount selectByUserIdAndEmail(@Param("userId") Long userId,
                                       @Param("emailAddress") String emailAddress);

    /**
     * 按收件地址查找入站归属账户（Cloudflare 推送链路）。
     * <p>
     * 只在本域邮箱（{@code provider_type='CLOUDFLARE'}）里找，且只取启用的：
     * 这个查询的返回值直接决定"这封信投给谁"，多匹配一行就意味着投递有歧义。
     * 唯一性由 {@code uk_cloudflare_address} 在库层面保证，这里的 {@code LIMIT 1}
     * 只是兜底，不是"任选一个"。
     * </p>
     * <p>
     * 返回实体（含全部字段）而不是布尔量：调用方需要 {@code user_id}（投给谁）
     * 与 {@code enabled}（区分"地址不存在"和"地址已停用"两种拒收理由）。
     * </p>
     */
    @Select("SELECT * FROM mail_account WHERE email_address = #{emailAddress} "
            + "AND provider_type = 'CLOUDFLARE' LIMIT 1")
    MailAccount selectInboundAddress(@Param("emailAddress") String emailAddress);

    /**
     * 某用户的发信账户：优先取指定地址，未指定则取第一个启用的。
     * <p>
     * 两种账户都能发信，但走的路不同，因此"能发信"的判据也不同：
     * </p>
     * <ul>
     *   <li>{@code IMAP_SMTP} —— 用它自己的服务器，要求 {@code smtp_host} 非空</li>
     *   <li>{@code CLOUDFLARE} —— 走项目级中继，本就没有 {@code smtp_host}，
     *       只要有这个地址就能发（中继是否配置由收信/发信环节另行判断）</li>
     * </ul>
     * <p>
     * 排序把 {@code IMAP_SMTP} 放在前面：用户自己绑定的邮箱是更明确的选择，
     * 本域地址是后加的备选。两者的默认发信地址不能因为"谁先绑定"而随机颠倒。
     * </p>
     */
    @Select("SELECT * FROM mail_account WHERE user_id = #{userId} AND enabled = 1 "
            + "AND ((smtp_host IS NOT NULL AND smtp_host != '') "
            + "     OR provider_type = 'CLOUDFLARE') "
            + "ORDER BY (provider_type = 'CLOUDFLARE') ASC, id ASC LIMIT 1")
    MailAccount selectPrimaryForSend(@Param("userId") Long userId);

    /**
     * 所有启用的账户（IMAP 轮询用）。
     * <p>
     * 要求 imap_host 非空 —— 只绑定了发信没配收信的账户不该进轮询队列。
     * 本域邮箱（CLOUDFLARE）的 imap_host 恒为空，靠这个条件天然被排除：
     * 它们的来信由 Cloudflare 推送，没有可轮询的服务器，
     * 放进队列只会每 3 分钟失败一次。
     * </p>
     */
    @Select("SELECT * FROM mail_account WHERE enabled = 1 "
            + "AND imap_host IS NOT NULL AND imap_host != '' ORDER BY id ASC")
    List<MailAccount> selectAllEnabledForSync();

    /**
     * 账户列表（管理端，带归属用户邮箱，一次查询避免 N+1）
     */
    @Select("SELECT a.*, u.email AS owner_email FROM mail_account a "
            + "LEFT JOIN user u ON u.id = a.user_id "
            + "ORDER BY a.id DESC")
    List<MailAccount> selectAllWithOwner();

    /**
     * 推进 IMAP 水位线并记录同步结果。
     * <p>
     * {@code imapLastUid} 传 null 时不修改水位线（同步失败时用，避免把水位线
     * 推到没真正入库的位置而永久丢信）。
     * </p>
     */
    @Update("<script>"
            + "UPDATE mail_account SET last_sync_time = #{syncTime}, "
            + "last_sync_status = #{status}, last_sync_error = #{error}"
            + "<if test='lastUid != null'>, imap_last_uid = #{lastUid}</if>"
            + " WHERE id = #{id}"
            + "</script>")
    int updateSyncState(@Param("id") Long id,
                        @Param("syncTime") LocalDateTime syncTime,
                        @Param("status") String status,
                        @Param("error") String error,
                        @Param("lastUid") Long lastUid);

    /**
     * 换了信箱后清空同步状态（水位线归零、旧报错清掉）。
     * <p>
     * 不能用 {@code updateById} 做这件事：MyBatis-Plus 会跳过 null 字段，
     * 把实体字段置 null 再提交等于什么都没做，旧信箱的报错会一直挂在界面上
     * 直到下一轮同步覆盖它。这里显式写 SQL。
     * </p>
     */
    @Update("UPDATE mail_account SET imap_last_uid = 0, "
            + "last_sync_time = NULL, last_sync_status = NULL, last_sync_error = NULL "
            + "WHERE id = #{id}")
    int resetSyncState(@Param("id") Long id);
}

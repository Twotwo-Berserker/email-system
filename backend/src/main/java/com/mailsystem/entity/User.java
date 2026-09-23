package com.mailsystem.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户实体
 * <p>
 * 注意：本实体<b>没有</b>名为 deleted 的字段，这是刻意的。
 * mybatis-plus 全局配置了 {@code logic-delete-field: deleted}，
 * 一旦出现同名字段，框架会给所有生成语句静默追加 {@code AND deleted = 0}，
 * 包括本表的新增/更新，导致匹配 0 行却不报错。
 * 用户删除语义由 {@link #status}（禁用）承担。
 * </p>
 */
@Data
@TableName("user")
public class User {

    /** 角色：普通用户 */
    public static final String ROLE_USER = "USER";
    /** 角色：管理员 */
    public static final String ROLE_ADMIN = "ADMIN";

    /** 账号状态：启用 */
    public static final int STATUS_ENABLED = 1;
    /** 账号状态：禁用 */
    public static final int STATUS_DISABLED = 0;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 邮箱地址（登录账号） */
    private String email;

    /** 密码（BCrypt 哈希。历史数据可能为无盐 SHA-256，登录时透明重哈希升级） */
    private String password;

    /** 用户昵称 */
    private String nickname;

    /** 角色：USER / ADMIN */
    private String role;

    /** 账号状态：1=启用, 0=禁用（禁用后无法登录） */
    private Integer status;

    /** 是否强制修改密码：1=是（首次登录、或管理员重置密码后） */
    private Integer mustChangePassword;

    /** 最后登录时间 */
    private LocalDateTime lastLoginTime;

    /** 最后登录IP */
    private String lastLoginIp;

    /** 注册时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 是否为管理员（null 视为非管理员，避免历史行为空时误判为管理员） */
    public boolean isAdmin() {
        return ROLE_ADMIN.equals(this.role);
    }
}

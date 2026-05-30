package com.mailsystem.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户实体
 */
@Data
@TableName("user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 邮箱地址（登录账号） */
    private String email;

    /** 密码（MD5+盐加密） */
    private String password;

    /** 用户昵称 */
    private String nickname;

    /** 注册时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

package com.mailsystem.dto;

import com.mailsystem.entity.User;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端用户视图（脱敏）
 * <p>
 * 刻意不含 password 字段 —— 即便存的是哈希，也没有任何理由把它交给前端。
 * 用独立 DTO 而不是给 {@link User} 加 {@code @JsonIgnore}，
 * 是为了让"管理端返回什么"这件事在类型层面一目了然。
 * </p>
 */
@Data
public class UserAdminView {

    private Long id;
    private String email;
    private String nickname;
    private String role;
    private Integer status;
    private Boolean mustChangePassword;
    private LocalDateTime lastLoginTime;
    private String lastLoginIp;
    private LocalDateTime createTime;

    /** 该账号是否仍在用弱哈希（无盐 SHA-256）。管理端可据此提示需强制重置。 */
    private Boolean weakPasswordHash;

    public static UserAdminView from(User user, boolean weakHash) {
        UserAdminView v = new UserAdminView();
        v.setId(user.getId());
        v.setEmail(user.getEmail());
        v.setNickname(user.getNickname());
        v.setRole(user.getRole());
        v.setStatus(user.getStatus());
        v.setMustChangePassword(user.getMustChangePassword() != null && user.getMustChangePassword() == 1);
        v.setLastLoginTime(user.getLastLoginTime());
        v.setLastLoginIp(user.getLastLoginIp());
        v.setCreateTime(user.getCreateTime());
        v.setWeakPasswordHash(weakHash);
        return v;
    }
}

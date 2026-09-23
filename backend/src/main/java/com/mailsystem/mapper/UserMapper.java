package com.mailsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mailsystem.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 用户 Mapper
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 根据邮箱查询用户（登录、注册查重、收件人地址解析）
     */
    @Select("SELECT * FROM user WHERE email = #{email}")
    User selectByEmail(@Param("email") String email);

    // ==================== 管理端查询 ====================

    /**
     * 用户列表（分页 + 关键字/角色/状态筛选）
     * <p>
     * 三个筛选条件都按"为空则不过滤"处理，避免为每种组合写一个方法。
     * 刻意不返回 password —— 管理端也不需要拿到密码哈希。
     * </p>
     */
    @Select("<script>"
            + "SELECT id, email, nickname, role, status, must_change_password, "
            + "       last_login_time, last_login_ip, create_time "
            + "FROM user "
            + "<where>"
            + "  <if test='keyword != null and keyword != \"\"'>"
            + "    AND (email LIKE CONCAT('%', #{keyword}, '%') "
            + "         OR nickname LIKE CONCAT('%', #{keyword}, '%'))"
            + "  </if>"
            + "  <if test='role != null and role != \"\"'> AND role = #{role} </if>"
            + "  <if test='status != null'> AND status = #{status} </if>"
            + "</where>"
            + "ORDER BY id ASC"
            + "</script>")
    IPage<User> selectPageFiltered(Page<User> page,
                                   @Param("keyword") String keyword,
                                   @Param("role") String role,
                                   @Param("status") Integer status);

    /**
     * 统计指定角色的用户数 —— 用于"不能降级最后一个管理员"的校验
     */
    @Select("SELECT COUNT(*) FROM user WHERE role = #{role}")
    int countByRole(@Param("role") String role);

    /**
     * 统计指定状态下的指定角色用户数
     */
    @Select("SELECT COUNT(*) FROM user WHERE role = #{role} AND status = 1")
    int countEnabledByRole(@Param("role") String role);

    // ==================== 管理端写入 ====================

    @Update("UPDATE user SET role = #{role} WHERE id = #{id}")
    int updateRole(@Param("id") Long id, @Param("role") String role);

    @Update("UPDATE user SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /**
     * 更新密码。
     * <p>
     * 同时重置 must_change_password —— 管理员重置密码后强制用户首次登录改密；
     * 用户自己改密时传 0。
     * </p>
     */
    @Update("UPDATE user SET password = #{password}, must_change_password = #{mustChangePassword} WHERE id = #{id}")
    int updatePassword(@Param("id") Long id,
                       @Param("password") String password,
                       @Param("mustChangePassword") Integer mustChangePassword);

    /**
     * 记录登录信息（时间 + IP）
     */
    @Update("UPDATE user SET last_login_time = #{loginTime}, last_login_ip = #{loginIp} WHERE id = #{id}")
    int recordLogin(@Param("id") Long id,
                    @Param("loginTime") LocalDateTime loginTime,
                    @Param("loginIp") String loginIp);
}

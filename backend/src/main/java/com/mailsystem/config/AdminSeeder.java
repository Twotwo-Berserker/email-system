package com.mailsystem.config;

import com.mailsystem.entity.User;
import com.mailsystem.mapper.UserMapper;
import com.mailsystem.util.PasswordUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 管理员种子账号创建
 * <p>
 * 为什么不放在 SQL 里：BCrypt 哈希必须在运行期计算，SQL 脚本无法生成。
 * 若把某个固定哈希写死进 init.sql，就等于把管理员密码公开写在仓库里。
 * 因此这里在应用启动时按环境变量创建，密码由 {@code ADMIN_INIT_PASSWORD} 提供，
 * 明文不落任何文件。
 * </p>
 *
 * <h3>幂等性与"不覆盖"原则</h3>
 * <p>
 * 只在邮箱不存在时创建。已存在则完全不动 —— 包括不重置密码、不改角色。
 * 否则每次重启都会把管理员密码打回环境变量里的初始值，
 * 用户在界面上改的密码会被静默回滚。
 * </p>
 *
 * <h3>刻意不做"已存在则提权"</h3>
 * <p>
 * 如果 {@code ADMIN_INIT_EMAIL} 对应的账号已存在但不是 ADMIN，
 * 这里<b>不会</b>把它提升为管理员，只打印告警。
 * 原因是存在这样的攻击路径：清库后应用重启前，有人先注册了该邮箱，
 * 启动时的"自动提权"就会把一个普通注册账号变成管理员。
 * 宁可让运维手工处理，也不留这条路径。
 * </p>
 */
@Component
public class AdminSeeder implements ApplicationRunner {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordUtil passwordUtil;

    @Value("${app.admin.email:}")
    private String adminEmail;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (adminEmail == null || adminEmail.trim().isEmpty()) {
            System.out.println("[AdminSeeder] 未配置 app.admin.email，跳过管理员种子创建");
            return;
        }
        if (adminPassword == null || adminPassword.trim().isEmpty()) {
            System.out.println("[AdminSeeder] 未配置 app.admin.password（环境变量 ADMIN_INIT_PASSWORD），"
                    + "跳过管理员种子创建。管理端将无法登录，除非库中已存在管理员账号。");
            return;
        }

        String email = adminEmail.trim();
        User existing = userMapper.selectByEmail(email);

        if (existing == null) {
            User admin = new User();
            admin.setEmail(email);
            admin.setPassword(passwordUtil.encode(adminPassword));
            admin.setNickname("系统管理员");
            admin.setRole(User.ROLE_ADMIN);
            admin.setStatus(User.STATUS_ENABLED);
            // 强制首次登录改密：环境变量里的初始密码是"引导凭据"，
            // 不应长期有效
            admin.setMustChangePassword(1);
            userMapper.insert(admin);
            System.out.println("[AdminSeeder] 已创建管理员账号 " + email + "（首次登录需修改密码）");
            return;
        }

        if (existing.isAdmin()) {
            System.out.println("[AdminSeeder] 管理员账号 " + email + " 已存在，保持不动");
        } else {
            System.err.println("[AdminSeeder] 严重：账号 " + email + " 已存在但不是管理员，"
                    + "出于安全考虑不会自动提权。"
                    + "请手工执行：UPDATE user SET role='ADMIN' WHERE email='" + email + "';"
                    + "或改用另一个 ADMIN_INIT_EMAIL。");
        }
    }
}

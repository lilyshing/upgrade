package com.aipro.upgrade.server.service;

import com.aipro.upgrade.server.auth.PasswordHasher;
import com.aipro.upgrade.server.auth.SessionManager;
import com.aipro.upgrade.server.db.DatabaseManager;
import com.aipro.upgrade.server.model.Models.AdminUser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户服务：登录、改密、用户 CRUD（管理员）
 * <p>
 * 权限隔离（PRD F-S-10、F-S-18、F-S-19）：
 * - ADMIN 管所有工具 + 用户管理
 * - USER 仅自己创建的工具
 * - 不开放自助注册（PRD 假设 13）
 * - 最后一个 ADMIN 不可降级或禁用（PRD 假设 16）
 */
public final class UserService {

    private static final UserService INSTANCE = new UserService();

    private UserService() {
    }

    public static UserService getInstance() {
        return INSTANCE;
    }

    // ============== 登录与登出 ==============

    /**
     * 登录：返回 token 与是否需改密；失败抛异常。
     * 成功后更新 last_login_at。
     */
    public LoginResult login(String username, String plain) {
        AdminUser u = findByUsername(username);
        if (u == null) {
            throw new AuthException("用户名或密码错误");
        }
        if (!"ACTIVE".equals(u.status)) {
            throw new AuthException("账号已被禁用");
        }
        if (!PasswordHasher.check(plain, u.passwordHash)) {
            throw new AuthException("用户名或密码错误");
        }
        // 更新最近登录时间
        updateLastLogin(u.id);
        String token = SessionManager.getInstance().create(
                u.id, u.username, u.role, u.displayName, u.mustChangePwd != null && u.mustChangePwd == 1);
        LoginResult r = new LoginResult();
        r.token = token;
        r.userId = u.id;
        r.username = u.username;
        r.role = u.role;
        r.displayName = u.displayName;
        r.mustChangePwd = u.mustChangePwd != null && u.mustChangePwd == 1;
        return r;
    }

    public void logout(String token) {
        SessionManager.getInstance().invalidate(token);
    }

    /** 修改密码：校验旧密码、更新、清除 must_change_pwd 标记。 */
    public void changePassword(int userId, String oldPlain, String newPlain) {
        AdminUser u = findById(userId);
        if (u == null) {
            throw new AuthException("用户不存在");
        }
        if (!PasswordHasher.check(oldPlain, u.passwordHash)) {
            throw new AuthException("原密码错误");
        }
        if (newPlain == null || newPlain.length() < 6) {
            throw new AuthException("新密码长度至少 6 位");
        }
        String hash = PasswordHasher.hash(newPlain);
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE admin_user SET password_hash=?, must_change_pwd=0 WHERE id=?")) {
            ps.setString(1, hash);
            ps.setInt(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("修改密码失败：" + e.getMessage(), e);
        }
    }

    // ============== 用户 CRUD（仅 ADMIN 用） ==============

    public List<AdminUser> listAll() {
        List<AdminUser> list = new ArrayList<>();
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, username, password_hash, role, status, display_name, must_change_pwd, created_at, last_login_at "
                             + "FROM admin_user ORDER BY id")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询用户列表失败：" + e.getMessage(), e);
        }
        return list;
    }

    /** 新增用户：返回新 id。 */
    public int create(String username, String plain, String role, String displayName) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (plain == null || plain.length() < 6) {
            throw new IllegalArgumentException("密码长度至少 6 位");
        }
        if (!"ADMIN".equals(role) && !"USER".equals(role)) {
            throw new IllegalArgumentException("角色必须是 ADMIN 或 USER");
        }
        if (findByUsername(username) != null) {
            throw new IllegalStateException("用户名已存在");
        }
        String hash = PasswordHasher.hash(plain);
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO admin_user(username, password_hash, role, status, display_name, must_change_pwd, created_at) "
                             + "VALUES(?, ?, ?, 'ACTIVE', ?, 1, ?)")) {
            ps.setString(1, username.trim());
            ps.setString(2, hash);
            ps.setString(3, role);
            ps.setString(4, displayName);
            ps.setString(5, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("新增用户失败：" + e.getMessage(), e);
        }
        throw new RuntimeException("新增用户未返回 id");
    }

    /** 改角色：USER↔ADMIN；最后一个 ADMIN 不可降级。 */
    public void changeRole(int targetUserId, String newRole) {
        if (!"ADMIN".equals(newRole) && !"USER".equals(newRole)) {
            throw new IllegalArgumentException("角色必须是 ADMIN 或 USER");
        }
        AdminUser target = findById(targetUserId);
        if (target == null) {
            throw new IllegalArgumentException("目标用户不存在");
        }
        if (target.role.equals(newRole)) {
            return;
        }
        // 从 ADMIN 降为 USER 时，必须保证至少还剩一个 ADMIN
        if ("ADMIN".equals(target.role) && "USER".equals(newRole)) {
            int adminCount = countActiveAdmins();
            if (adminCount <= 1) {
                throw new IllegalStateException("不可降级最后一个 ADMIN（PRD 假设 16）");
            }
        }
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE admin_user SET role=? WHERE id=?")) {
            ps.setString(1, newRole);
            ps.setInt(2, targetUserId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("改角色失败：" + e.getMessage(), e);
        }
    }

    /** 重置密码：管理员操作，重置后强制改密。 */
    public void resetPassword(int targetUserId, String newPlain) {
        if (newPlain == null || newPlain.length() < 6) {
            throw new IllegalArgumentException("密码长度至少 6 位");
        }
        String hash = PasswordHasher.hash(newPlain);
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE admin_user SET password_hash=?, must_change_pwd=1 WHERE id=?")) {
            ps.setString(1, hash);
            ps.setInt(2, targetUserId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("重置密码失败：" + e.getMessage(), e);
        }
    }

    /** 启用/禁用账号：禁用最后一个 ADMIN 被拒绝。 */
    public void setStatus(int targetUserId, String status) {
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw new IllegalArgumentException("状态必须是 ACTIVE 或 DISABLED");
        }
        AdminUser target = findById(targetUserId);
        if (target == null) {
            throw new IllegalArgumentException("目标用户不存在");
        }
        if (target.status.equals(status)) {
            return;
        }
        if ("ADMIN".equals(target.role) && "ACTIVE".equals(target.status) && "DISABLED".equals(status)) {
            int adminCount = countActiveAdmins();
            if (adminCount <= 1) {
                throw new IllegalStateException("不可禁用最后一个 ACTIVE 状态的 ADMIN");
            }
        }
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE admin_user SET status=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setInt(2, targetUserId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("修改状态失败：" + e.getMessage(), e);
        }
    }

    /** 删除用户：先转移其工具归属（调用方负责），再删除账号。 */
    public void delete(int targetUserId) {
        AdminUser target = findById(targetUserId);
        if (target == null) {
            return;
        }
        if ("ADMIN".equals(target.role) && countActiveAdmins() <= 1) {
            throw new IllegalStateException("不可删除最后一个 ACTIVE 状态的 ADMIN");
        }
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM admin_user WHERE id=?")) {
            ps.setInt(1, targetUserId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("删除用户失败：" + e.getMessage(), e);
        }
    }

    /** 把某用户的所有工具归属转移给目标用户（删除用户前调用）。 */
    public void transferOwnership(int fromUserId, int toUserId) {
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE tool SET owner_user_id=? WHERE owner_user_id=?")) {
            ps.setInt(1, toUserId);
            ps.setInt(2, fromUserId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("转移工具归属失败：" + e.getMessage(), e);
        }
    }

    // ============== 内部辅助 ==============

    public AdminUser findById(int id) {
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, username, password_hash, role, status, display_name, must_change_pwd, created_at, last_login_at "
                             + "FROM admin_user WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return map(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询用户失败：" + e.getMessage(), e);
        }
        return null;
    }

    private AdminUser findByUsername(String username) {
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, username, password_hash, role, status, display_name, must_change_pwd, created_at, last_login_at "
                             + "FROM admin_user WHERE username=?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return map(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询用户失败：" + e.getMessage(), e);
        }
        return null;
    }

    private void updateLastLogin(int userId) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE admin_user SET last_login_at=? WHERE id=?")) {
            ps.setString(1, now);
            ps.setInt(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            // 仅日志，不阻断登录
            System.err.println("[WARN] 更新登录时间失败：" + e.getMessage());
        }
    }

    private int countActiveAdmins() {
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM admin_user WHERE role='ADMIN' AND status='ACTIVE'")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("统计 ADMIN 数失败：" + e.getMessage(), e);
        }
    }

    private AdminUser map(ResultSet rs) throws SQLException {
        AdminUser u = new AdminUser();
        u.id = rs.getInt("id");
        u.username = rs.getString("username");
        u.passwordHash = rs.getString("password_hash");
        u.role = rs.getString("role");
        u.status = rs.getString("status");
        u.displayName = rs.getString("display_name");
        int mc = rs.getInt("must_change_pwd");
        u.mustChangePwd = rs.wasNull() ? null : mc;
        u.createdAt = rs.getString("created_at");
        u.lastLoginAt = rs.getString("last_login_at");
        return u;
    }

    // ============== 结果对象 ==============

    public static class LoginResult {
        public String token;
        public int userId;
        public String username;
        public String role;
        public String displayName;
        public boolean mustChangePwd;
    }

    /** 鉴权异常。 */
    public static class AuthException extends RuntimeException {
        public AuthException(String msg) {
            super(msg);
        }
    }
}

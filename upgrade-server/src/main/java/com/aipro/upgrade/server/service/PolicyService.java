package com.aipro.upgrade.server.service;

import com.aipro.upgrade.server.db.DatabaseManager;
import com.aipro.upgrade.server.model.Models.PushPolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 灰度策略服务（PRD F-S-06、F-S-07、§4.2.4 push_policy 表）
 * <p>
 * 按工具独立配置（PRD 假设 14）；普通用户仅管理自己工具的灰度，由 Handler 校验归属。
 */
public final class PolicyService {

    private static final PolicyService INSTANCE = new PolicyService();

    private PolicyService() {
    }

    public static PolicyService getInstance() {
        return INSTANCE;
    }

    /** 确保某工具已存在灰度配置行；不存在则用默认值创建。 */
    public void ensurePolicy(String toolId) {
        if (findPolicy(toolId) != null) {
            return;
        }
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO push_policy(tool_id, enabled, threshold, current_count, whitelist, updated_at) "
                             + "VALUES(?, 1, 10, 0, '', ?)")) {
            ps.setString(1, toolId);
            ps.setString(2, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            // 并发可能重复，忽略唯一约束错误
            if (!e.getMessage().contains("UNIQUE")) {
                throw new RuntimeException("初始化灰度策略失败：" + e.getMessage(), e);
            }
        }
    }

    public PushPolicy findPolicy(String toolId) {
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, tool_id, enabled, threshold, current_count, whitelist, updated_at FROM push_policy WHERE tool_id=?")) {
            ps.setString(1, toolId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return map(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询灰度策略失败：" + e.getMessage(), e);
        }
        return null;
    }

    /** 保存灰度配置（开关、阈值、白名单）。 */
    public PushPolicy savePolicy(String toolId, boolean enabled, int threshold, String whitelistCsv) {
        ensurePolicy(toolId);
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE push_policy SET enabled=?, threshold=?, whitelist=?, updated_at=? WHERE tool_id=?")) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setInt(2, Math.max(0, threshold));
            ps.setString(3, whitelistCsv == null ? "" : whitelistCsv.trim());
            ps.setString(4, now);
            ps.setString(5, toolId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("保存灰度策略失败：" + e.getMessage(), e);
        }
        return findPolicy(toolId);
    }

    /** 增量调整阈值或开关（一键放开/暂停用）。 */
    public PushPolicy updateEnabled(String toolId, boolean enabled) {
        ensurePolicy(toolId);
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE push_policy SET enabled=?, updated_at=? WHERE tool_id=?")) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setString(2, now);
            ps.setString(3, toolId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新灰度开关失败：" + e.getMessage(), e);
        }
        return findPolicy(toolId);
    }

    /** 一键放开：清除计数、关闭灰度限制（threshold 设为 -1 表示不限制）。 */
    public PushPolicy release(String toolId) {
        ensurePolicy(toolId);
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE push_policy SET enabled=0, current_count=0, threshold=-1, updated_at=? WHERE tool_id=?")) {
            ps.setString(1, now);
            ps.setString(2, toolId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("一键放开失败：" + e.getMessage(), e);
        }
        return findPolicy(toolId);
    }

    /** 一键暂停：关闭开关，保持现有阈值与计数。 */
    public PushPolicy pause(String toolId) {
        return updateEnabled(toolId, false);
    }

    /** 重置计数：current_count = 0。 */
    public PushPolicy resetCount(String toolId) {
        ensurePolicy(toolId);
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE push_policy SET current_count=0, updated_at=? WHERE tool_id=?")) {
            ps.setString(1, now);
            ps.setString(2, toolId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("重置计数失败：" + e.getMessage(), e);
        }
        return findPolicy(toolId);
    }

    /**
     * 判断某 client 是否被允许接收新版本推送（灰度判定）。
     * <p>
     * 规则：
     * - 灰度未开启 → 允许
     * - 当前已更新数 < 阈值 → 允许
     * - 客户端 ip/id 在白名单 → 允许
     * - 其他 → 不允许
     * <p>
     * 阈值为 -1 表示已一键放开（无限制）。
     */
    public boolean allowPush(String toolId, String clientIp, String clientId) {
        PushPolicy p = findPolicy(toolId);
        if (p == null) {
            // 工具未配置灰度，默认允许（兼容）
            return true;
        }
        if (p.enabled == null || p.enabled == 0) {
            return true;
        }
        if (p.threshold != null && p.threshold == -1) {
            return true;
        }
        // 白名单优先
        if (isWhitelisted(p, clientIp, clientId)) {
            return true;
        }
        int current = p.currentCount == null ? 0 : p.currentCount;
        int threshold = p.threshold == null ? 10 : p.threshold;
        return current < threshold;
    }

    /** 一次成功升级后自增 current_count。 */
    public void incrementCount(String toolId) {
        ensurePolicy(toolId);
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE push_policy SET current_count=current_count+1 WHERE tool_id=? AND enabled=1")) {
            ps.setString(1, toolId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[WARN] 自增灰度计数失败：" + e.getMessage());
        }
    }

    /** 判断 ip/id 是否在白名单中。 */
    private boolean isWhitelisted(PushPolicy p, String clientIp, String clientId) {
        if (p.whitelist == null || p.whitelist.isEmpty()) {
            return false;
        }
        Set<String> set = new HashSet<>(Arrays.asList(p.whitelist.split("\\s*,\\s*")));
        if (clientIp != null && set.contains(clientIp)) {
            return true;
        }
        if (clientId != null && set.contains(clientId)) {
            return true;
        }
        return false;
    }

    /** 计算剩余配额：threshold=-1 或 enabled=0 返回 -1（不限制）。 */
    public int remaining(PushPolicy p) {
        if (p == null) {
            return -1;
        }
        if (p.enabled == null || p.enabled == 0) {
            return -1;
        }
        if (p.threshold != null && p.threshold == -1) {
            return -1;
        }
        int current = p.currentCount == null ? 0 : p.currentCount;
        int threshold = p.threshold == null ? 10 : p.threshold;
        return Math.max(0, threshold - current);
    }

    private PushPolicy map(ResultSet rs) throws SQLException {
        PushPolicy p = new PushPolicy();
        p.id = rs.getInt("id");
        p.toolId = rs.getString("tool_id");
        p.enabled = rs.getInt("enabled");
        p.threshold = rs.getInt("threshold");
        p.currentCount = rs.getInt("current_count");
        p.whitelist = rs.getString("whitelist");
        p.updatedAt = rs.getString("updated_at");
        return p;
    }
}

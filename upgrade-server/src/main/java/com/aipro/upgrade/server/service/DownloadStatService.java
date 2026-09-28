package com.aipro.upgrade.server.service;

import com.aipro.upgrade.server.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 下载统计服务（PRD F-S-17）
 * <p>
 * 记录下载器访问（BOOTSTRAP）与本体文件下载（FILE）次数，按平台、按天聚合。
 */
public final class DownloadStatService {

    private static final DownloadStatService INSTANCE = new DownloadStatService();

    private DownloadStatService() {
    }

    public static DownloadStatService getInstance() {
        return INSTANCE;
    }

    /** 记录一次下载事件。 */
    public void record(String toolId, String stage, String platform, String clientIp) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO download_stat(tool_id, stage, platform, client_ip, downloaded_at) VALUES(?, ?, ?, ?, ?)")) {
            ps.setString(1, toolId);
            ps.setString(2, stage);
            ps.setString(3, platform);
            ps.setString(4, clientIp);
            ps.setString(5, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            // 不阻断业务
            System.err.println("[WARN] 写下载统计失败：" + e.getMessage());
        }
    }

    /** 统计某工具下载器访问数（BOOTSTRAP 阶段）。 */
    public int bootstrapCount(String toolId) {
        return countBy(toolId, "BOOTSTRAP", null);
    }

    /** 统计某工具本体文件下载数（FILE 阶段）。 */
    public int fileCount(String toolId) {
        return countBy(toolId, "FILE", null);
    }

    /** 统计某工具+平台下载器访问数。 */
    public int bootstrapCountByPlatform(String toolId, String platform) {
        return countBy(toolId, "BOOTSTRAP", platform);
    }

    /** 按平台汇总下载器访问。 */
    public Map<String, Integer> bootstrapCountByPlatformBreakdown(String toolId) {
        Map<String, Integer> m = new LinkedHashMap<>();
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT platform, COUNT(*) FROM download_stat WHERE tool_id=? AND stage='BOOTSTRAP' GROUP BY platform")) {
            ps.setString(1, toolId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    m.put(rs.getString(1), rs.getInt(2));
                }
            }
        } catch (SQLException e) {
            System.err.println("[WARN] 查询下载统计失败：" + e.getMessage());
        }
        return m;
    }

    /** 按天汇总下载器访问。 */
    public Map<String, Integer> bootstrapCountByDay(String toolId, int recentDays) {
        Map<String, Integer> m = new LinkedHashMap<>();
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT substr(downloaded_at, 1, 10) AS d, COUNT(*) FROM download_stat "
                             + "WHERE tool_id=? AND stage='BOOTSTRAP' GROUP BY d ORDER BY d DESC LIMIT ?")) {
            ps.setString(1, toolId);
            ps.setInt(2, recentDays);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    m.put(rs.getString(1), rs.getInt(2));
                }
            }
        } catch (SQLException e) {
            System.err.println("[WARN] 查询按天下载统计失败：" + e.getMessage());
        }
        return m;
    }

    private int countBy(String toolId, String stage, String platform) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM download_stat WHERE tool_id=? AND stage=?");
        if (platform != null && !platform.isEmpty()) {
            sql.append(" AND platform=?");
        }
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, toolId);
            ps.setString(2, stage);
            if (platform != null && !platform.isEmpty()) {
                ps.setString(3, platform);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }
}

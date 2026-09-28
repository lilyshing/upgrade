package com.aipro.upgrade.server.service;

import com.aipro.upgrade.server.db.DatabaseManager;
import com.aipro.upgrade.server.model.Models.ClientUpdateRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端更新记录服务（PRD F-S-05）
 * <p>
 * 接收客户端上报并写入 SQLite3 client_update_record 表，可在后台查询与导出。
 */
public final class RecordService {

    private static final RecordService INSTANCE = new RecordService();

    private RecordService() {
    }

    public static RecordService getInstance() {
        return INSTANCE;
    }

    /**
     * 写入一条客户端更新记录。
     *
     * @param clientId   客户端唯一标识
     * @param clientIp   客户端 IP（服务器从请求提取）
     * @param oldVersion 之前版本
     * @param newVersion 现在版本
     * @param result     结果：SUCCESS / FAIL
     * @param failReason 失败原因（失败时填）
     * @param durationMs 更新耗时（毫秒）
     */
    public void record(String clientId, String clientIp, String oldVersion, String newVersion,
                       String result, String failReason, long durationMs) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO client_update_record(client_id, client_ip, old_version, new_version, update_time, result, fail_reason, duration_ms) "
                             + "VALUES(?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, clientId);
            ps.setString(2, clientIp);
            ps.setString(3, oldVersion);
            ps.setString(4, newVersion);
            ps.setString(5, now);
            ps.setString(6, result);
            ps.setString(7, failReason);
            ps.setLong(8, durationMs);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[WARN] 写入更新记录失败：" + e.getMessage());
        }
    }

    /** 查询记录：按 new_version（可选）、client_ip（可选）、按时间区间（可选）过滤；返回全部匹配。 */
    public List<ClientUpdateRecord> query(String newVersion, String clientIp, String fromDate, String toDate) {
        List<ClientUpdateRecord> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT id, client_id, client_ip, old_version, new_version, update_time, result, fail_reason, duration_ms "
                        + "FROM client_update_record WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (newVersion != null && !newVersion.isEmpty()) {
            sql.append(" AND new_version=?");
            params.add(newVersion);
        }
        if (clientIp != null && !clientIp.isEmpty()) {
            sql.append(" AND client_ip=?");
            params.add(clientIp);
        }
        if (fromDate != null && !fromDate.isEmpty()) {
            sql.append(" AND update_time >= ?");
            params.add(fromDate);
        }
        if (toDate != null && !toDate.isEmpty()) {
            sql.append(" AND update_time <= ?");
            params.add(toDate);
        }
        sql.append(" ORDER BY id DESC LIMIT 1000");
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询更新记录失败：" + e.getMessage(), e);
        }
        return list;
    }

    /** 查询记录：按工具归属过滤——通过 new_version 与 toolVersion 的关联（简化版）。 */
    public List<ClientUpdateRecord> queryByTools(List<String> versionFilter, String clientIp, String fromDate, String toDate) {
        List<ClientUpdateRecord> list = new ArrayList<>();
        if (versionFilter == null || versionFilter.isEmpty()) {
            return list;
        }
        StringBuilder sql = new StringBuilder(
                "SELECT id, client_id, client_ip, old_version, new_version, update_time, result, fail_reason, duration_ms "
                        + "FROM client_update_record WHERE new_version IN (");
        for (int i = 0; i < versionFilter.size(); i++) {
            if (i > 0) {
                sql.append(",");
            }
            sql.append("?");
        }
        sql.append(")");
        List<Object> params = new ArrayList<>(versionFilter);
        if (clientIp != null && !clientIp.isEmpty()) {
            sql.append(" AND client_ip=?");
            params.add(clientIp);
        }
        if (fromDate != null && !fromDate.isEmpty()) {
            sql.append(" AND update_time >= ?");
            params.add(fromDate);
        }
        if (toDate != null && !toDate.isEmpty()) {
            sql.append(" AND update_time <= ?");
            params.add(toDate);
        }
        sql.append(" ORDER BY id DESC LIMIT 1000");
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询更新记录失败：" + e.getMessage(), e);
        }
        return list;
    }

    /** 累计更新客户端数（去重 client_id）。 */
    public int totalClients() {
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(DISTINCT client_id) FROM client_update_record")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    /** 今日更新数（按 update_time ISO 日期匹配，简化）。 */
    public int todayCount() {
        String todayPrefix = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).substring(0, 10);
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM client_update_record WHERE update_time LIKE ?")) {
            ps.setString(1, todayPrefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    private ClientUpdateRecord map(ResultSet rs) throws SQLException {
        ClientUpdateRecord r = new ClientUpdateRecord();
        r.id = rs.getInt("id");
        r.clientId = rs.getString("client_id");
        r.clientIp = rs.getString("client_ip");
        r.oldVersion = rs.getString("old_version");
        r.newVersion = rs.getString("new_version");
        r.updateTime = rs.getString("update_time");
        r.result = rs.getString("result");
        r.failReason = rs.getString("fail_reason");
        r.durationMs = rs.getLong("duration_ms");
        return r;
    }
}

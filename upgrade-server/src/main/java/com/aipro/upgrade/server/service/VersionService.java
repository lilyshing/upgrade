package com.aipro.upgrade.server.service;

import com.aipro.upgrade.server.db.DatabaseManager;
import com.aipro.upgrade.server.model.Models.Version;
import com.aipro.upgrade.server.model.Models.VersionFile;
import com.aipro.upgrade.server.util.ZipExtractor;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 版本服务：SDK 升级用的版本管理（PRD F-S-01、F-S-02、F-S-03、F-S-04、F-S-09）
 * <p>
 * 与 ToolService（工具本体分发）相对：
 * - VersionService 面向 SDK 自身的升级流程（/api/version/latest、/api/file/{version}/{filename}）
 * - ToolService 面向首次分发的工具本体（/api/bootstrap/{toolId}、/api/file/tool/...）
 * <p>
 * 这套版本表与 PRD §4.2.1/§4.2.2 一致，向后兼容历史设计（PRD §1 提到为 Java 应用群统一升级能力，
 * SDK 本身也是一个被升级对象；当前 PRD 主要落地工具本体分发与下载器，但仍保留 version 表与 API
 * 供 SDK 自检自升级，二者独立可演进）。
 */
public final class VersionService {

    private static final VersionService INSTANCE = new VersionService();

    /** SDK 升级文件根目录（与 upgrade.db 同级）。 */
    private String versionFileRoot = "versionfiles";

    private VersionService() {
    }

    public static VersionService getInstance() {
        return INSTANCE;
    }

    public void setVersionFileRoot(String root) {
        this.versionFileRoot = root;
    }

    public String getVersionFileRoot() {
        return versionFileRoot;
    }

    // ============== 上传与发布 ==============

    /**
     * 上传 zip 包，自动解包、计算 SHA-256、生成 version 与 version_file 记录。
     * 同 version_no 已存在则抛异常。
     */
    public Version upload(String versionNo, String releaseNote, byte[] zipBytes) {
        if (versionNo == null || versionNo.trim().isEmpty()) {
            throw new IllegalArgumentException("version 不能为空");
        }
        if (existsVersion(versionNo)) {
            throw new IllegalStateException("版本号已存在：" + versionNo);
        }
        Path baseDir = Paths.get(versionFileRoot, versionNo);
        List<ZipExtractor.ExtractedFile> files = ZipExtractor.extract(zipBytes, baseDir);
        long totalSize = 0;
        for (ZipExtractor.ExtractedFile f : files) {
            totalSize += f.size;
        }
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        int newId;
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO version(version_no, status, release_note, file_count, total_size, created_at) "
                             + "VALUES(?, 'DRAFT', ?, ?, ?, ?)")) {
            ps.setString(1, versionNo);
            ps.setString(2, releaseNote);
            ps.setInt(3, files.size());
            ps.setLong(4, totalSize);
            ps.setString(5, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) {
                    throw new RuntimeException("未返回 version id");
                }
                newId = rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("写入 version 失败：" + e.getMessage(), e);
        }
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO version_file(version_id, file_path, sha256, size, created_at) VALUES(?, ?, ?, ?, ?)")) {
            for (ZipExtractor.ExtractedFile f : files) {
                ps.setInt(1, newId);
                ps.setString(2, f.relativePath);
                ps.setString(3, f.sha256);
                ps.setLong(4, f.size);
                ps.setString(5, now);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("写入 version_file 失败：" + e.getMessage(), e);
        }
        return findVersion(newId);
    }

    /** 发布：DRAFT → PUBLISHED；旧 PUBLISHED 自动置 OFFLINE。 */
    public Version publish(int versionId) {
        Version v = findVersion(versionId);
        if (v == null) {
            throw new IllegalArgumentException("版本不存在");
        }
        if (!"DRAFT".equals(v.status) && !"OFFLINE".equals(v.status)) {
            throw new IllegalStateException("仅 DRAFT/OFFLINE 可发布");
        }
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        try (Connection conn = DatabaseManager.getInstance().getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE version SET status='OFFLINE' WHERE status='PUBLISHED'")) {
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE version SET status='PUBLISHED', published_at=? WHERE id=?")) {
                    ps.setString(1, now);
                    ps.setInt(2, versionId);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("发布失败：" + e.getMessage(), e);
        }
        return findVersion(versionId);
    }

    /** 下线：PUBLISHED → OFFLINE。 */
    public void offline(int versionId) {
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE version SET status='OFFLINE' WHERE id=? AND status='PUBLISHED'")) {
            ps.setInt(1, versionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("下线失败：" + e.getMessage(), e);
        }
    }

    // ============== 查询 ==============

    /** 取最新 PUBLISHED 版本；不存在返回 null。 */
    public Version findLatestPublished() {
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, version_no, status, release_note, file_count, total_size, created_at, published_at "
                             + "FROM version WHERE status='PUBLISHED' ORDER BY id DESC LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapVersion(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询最新版本失败：" + e.getMessage(), e);
        }
        return null;
    }

    public Version findVersion(int id) {
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, version_no, status, release_note, file_count, total_size, created_at, published_at FROM version WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapVersion(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询版本失败：" + e.getMessage(), e);
        }
        return null;
    }

    public List<Version> listVersions() {
        List<Version> list = new ArrayList<>();
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, version_no, status, release_note, file_count, total_size, created_at, published_at FROM version ORDER BY id DESC")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapVersion(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询版本列表失败：" + e.getMessage(), e);
        }
        return list;
    }

    public List<VersionFile> listVersionFiles(int versionId) {
        List<VersionFile> list = new ArrayList<>();
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, version_id, file_path, sha256, size, created_at FROM version_file WHERE version_id=?")) {
            ps.setInt(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    VersionFile f = new VersionFile();
                    f.id = rs.getInt("id");
                    f.versionId = rs.getInt("version_id");
                    f.filePath = rs.getString("file_path");
                    f.sha256 = rs.getString("sha256");
                    f.size = rs.getLong("size");
                    f.createdAt = rs.getString("created_at");
                    list.add(f);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询文件清单失败：" + e.getMessage(), e);
        }
        return list;
    }

    /** 取版本文件物理路径。 */
    public Path resolveVersionFile(String versionNo, String relativePath) {
        return Paths.get(versionFileRoot, versionNo, relativePath);
    }

    // ============== 内部辅助 ==============

    private boolean existsVersion(String versionNo) {
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM version WHERE version_no=?")) {
            ps.setString(1, versionNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询版本存在性失败：" + e.getMessage(), e);
        }
    }

    private Version mapVersion(ResultSet rs) throws SQLException {
        Version v = new Version();
        v.id = rs.getInt("id");
        v.versionNo = rs.getString("version_no");
        v.status = rs.getString("status");
        v.releaseNote = rs.getString("release_note");
        v.fileCount = rs.getInt("file_count");
        v.totalSize = rs.getLong("total_size");
        v.createdAt = rs.getString("created_at");
        v.publishedAt = rs.getString("published_at");
        return v;
    }
}

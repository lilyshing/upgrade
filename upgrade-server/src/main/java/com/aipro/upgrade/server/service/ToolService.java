package com.aipro.upgrade.server.service;

import com.aipro.upgrade.server.db.DatabaseManager;
import com.aipro.upgrade.server.model.Models.Tool;
import com.aipro.upgrade.server.model.Models.ToolVersion;
import com.aipro.upgrade.server.model.Models.ToolVersionFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 工具服务：对外分发工具的注册、本体上传、版本管理、归属控制（PRD F-S-11~17、F-S-19）
 * <p>
 * 权限隔离：
 * - 注册工具：自动归属当前用户
 * - 普通 USER 仅可见/操作自己的工具；ADMIN 不受限
 * - 工具归属过滤由调用方 Handler 传入 currentUserId 与 isAdmin 决定
 * <p>
 * 本体文件存储：
 * - 物理文件存于 {dataDir}/tools/{toolId}/{version}/{platform}/{相对路径}
 * - 数据库只存 sha256/size/path 元信息
 */
public final class ToolService {

    private static final ToolService INSTANCE = new ToolService();

    /** 本体文件根目录（与 upgrade.db 同级）。 */
    private String toolFileRoot = "toolfiles";

    private ToolService() {
    }

    public static ToolService getInstance() {
        return INSTANCE;
    }

    public void setToolFileRoot(String root) {
        this.toolFileRoot = root;
    }

    public String getToolFileRoot() {
        return toolFileRoot;
    }

    // ============== 工具注册 ==============

    /** 注册新工具；toolId 必须唯一；自动归属 ownerUserId。 */
    public Tool register(String toolId, String name, String description, int ownerUserId, String defaultStartCmd) {
        if (toolId == null || toolId.trim().isEmpty()) {
            throw new IllegalArgumentException("toolId 不能为空");
        }
        if (!toolId.matches("^[a-z0-9][a-z0-9-]{1,63}$")) {
            throw new IllegalArgumentException("toolId 必须为小写字母数字短串，2-64 位");
        }
        if (findTool(toolId) != null) {
            throw new IllegalStateException("toolId 已存在：" + toolId);
        }
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tool(tool_id, name, description, owner_user_id, default_start_cmd, created_at) "
                             + "VALUES(?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, toolId);
            ps.setString(2, name);
            ps.setString(3, description);
            ps.setInt(4, ownerUserId);
            ps.setString(5, defaultStartCmd);
            ps.setString(6, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("注册工具失败：" + e.getMessage(), e);
        }
        // 自动初始化灰度策略行（默认开启，阈值 10）
        PolicyService.getInstance().ensurePolicy(toolId);
        return findTool(toolId);
    }

    public Tool findTool(String toolId) {
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT t.id, t.tool_id, t.name, t.description, t.owner_user_id, t.default_start_cmd, t.created_at, "
                             + "u.username AS owner_username FROM tool t "
                             + "LEFT JOIN admin_user u ON t.owner_user_id = u.id "
                             + "WHERE t.tool_id=?")) {
            ps.setString(1, toolId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapTool(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询工具失败：" + e.getMessage(), e);
        }
        return null;
    }

    /** 列工具：管理员返回全部，普通用户返回自己的。 */
    public List<Tool> listTools(int currentUserId, boolean isAdmin) {
        List<Tool> list = new ArrayList<>();
        String sql = "SELECT t.id, t.tool_id, t.name, t.description, t.owner_user_id, t.default_start_cmd, t.created_at, "
                + "u.username AS owner_username FROM tool t "
                + "LEFT JOIN admin_user u ON t.owner_user_id = u.id ";
        if (!isAdmin) {
            sql += "WHERE t.owner_user_id = ? ";
        }
        sql += "ORDER BY t.id";
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (!isAdmin) {
                ps.setInt(1, currentUserId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapTool(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询工具列表失败：" + e.getMessage(), e);
        }
        return list;
    }

    /** 校验当前用户对该工具的访问权；不可访问抛 SecurityException。 */
    public Tool checkOwnership(String toolId, int currentUserId, boolean isAdmin) {
        Tool t = findTool(toolId);
        if (t == null) {
            throw new IllegalArgumentException("工具不存在：" + toolId);
        }
        if (!isAdmin && t.ownerUserId != currentUserId) {
            throw new SecurityException("无权访问该工具：" + toolId);
        }
        return t;
    }

    // ============== 工具本体版本 ==============

    /**
     * 上传本体 zip 包并自动解包、计算 SHA-256、生成 tool_version 与 tool_version_file 记录。
     * 同 toolId+version+platform 已存在则抛异常（不允许覆盖）。
     *
     * @param toolId      工具特征值
     * @param version     本体版本号（语义化）
     * @param platform    平台：win / linux
     * @param zipBytes    zip 字节内容
     * @param startCommand 启动命令；为空则用工具默认
     * @param releaseNote 更新说明
     * @return 新建的 ToolVersion 记录
     */
    public ToolVersion uploadVersion(String toolId, String version, String platform,
                                     byte[] zipBytes, String startCommand, String releaseNote) {
        Tool tool = findTool(toolId);
        if (tool == null) {
            throw new IllegalArgumentException("工具不存在：" + toolId);
        }
        if (!"win".equals(platform) && !"linux".equals(platform)) {
            throw new IllegalArgumentException("platform 必须是 win 或 linux");
        }
        if (version == null || version.trim().isEmpty()) {
            throw new IllegalArgumentException("version 不能为空");
        }
        if (existsToolVersion(toolId, version, platform)) {
            throw new IllegalStateException("同 toolId+version+platform 已存在，不允许覆盖");
        }
        // 解压并落地
        List<UnpackedFile> files = unzipAndStore(zipBytes, toolId, version, platform);
        long totalSize = 0;
        for (UnpackedFile f : files) {
            totalSize += f.size;
        }
        // 写库
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        int newId;
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tool_version(tool_id, version, platform, start_command, status, file_count, total_size, release_note, created_at) "
                             + "VALUES(?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?)")) {
            ps.setString(1, toolId);
            ps.setString(2, version);
            ps.setString(3, platform);
            ps.setString(4, startCommand != null && !startCommand.isEmpty() ? startCommand : tool.defaultStartCmd);
            ps.setInt(5, files.size());
            ps.setLong(6, totalSize);
            ps.setString(7, releaseNote);
            ps.setString(8, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) {
                    throw new RuntimeException("未返回 tool_version id");
                }
                newId = rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("写入 tool_version 失败：" + e.getMessage(), e);
        }
        // 写文件清单
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tool_version_file(tool_version_id, file_path, sha256, size, created_at) VALUES(?, ?, ?, ?, ?)")) {
            for (UnpackedFile f : files) {
                ps.setInt(1, newId);
                ps.setString(2, f.relativePath);
                ps.setString(3, f.sha256);
                ps.setLong(4, f.size);
                ps.setString(5, now);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("写入 tool_version_file 失败：" + e.getMessage(), e);
        }
        return findToolVersion(newId);
    }

    /** 发布工具版本：DRAFT → PUBLISHED；同平台最多一个 PUBLISHED，发布前会自动将旧版本置 OFFLINE。 */
    public ToolVersion publishToolVersion(int toolVersionId) {
        ToolVersion v = findToolVersion(toolVersionId);
        if (v == null) {
            throw new IllegalArgumentException("版本不存在");
        }
        if (!"DRAFT".equals(v.status) && !"OFFLINE".equals(v.status)) {
            throw new IllegalStateException("仅 DRAFT/OFFLINE 状态可发布");
        }
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        try (Connection conn = DatabaseManager.getInstance().getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 将同 toolId+platform 的 PUBLISHED 置为 OFFLINE
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE tool_version SET status='OFFLINE' WHERE tool_id=? AND platform=? AND status='PUBLISHED'")) {
                    ps.setString(1, v.toolId);
                    ps.setString(2, v.platform);
                    ps.executeUpdate();
                }
                // 发布目标版本
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE tool_version SET status='PUBLISHED', published_at=? WHERE id=?")) {
                    ps.setString(1, now);
                    ps.setInt(2, toolVersionId);
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
        return findToolVersion(toolVersionId);
    }

    /** 下线工具版本：PUBLISHED → OFFLINE。 */
    public void offlineToolVersion(int toolVersionId) {
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE tool_version SET status='OFFLINE' WHERE id=? AND status='PUBLISHED'")) {
            ps.setInt(1, toolVersionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("下线失败：" + e.getMessage(), e);
        }
    }

    /** 列工具版本（按平台过滤）。 */
    public List<ToolVersion> listToolVersions(String toolId, String platform) {
        List<ToolVersion> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT id, tool_id, version, platform, start_command, status, file_count, total_size, release_note, created_at, published_at FROM tool_version WHERE tool_id=?");
        List<Object> params = new ArrayList<>();
        params.add(toolId);
        if (platform != null && !platform.isEmpty()) {
            sql.append(" AND platform=?");
            params.add(platform);
        }
        sql.append(" ORDER BY id DESC");
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapToolVersion(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询工具版本失败：" + e.getMessage(), e);
        }
        return list;
    }

    public ToolVersion findToolVersion(int id) {
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, tool_id, version, platform, start_command, status, file_count, total_size, release_note, created_at, published_at FROM tool_version WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapToolVersion(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询版本失败：" + e.getMessage(), e);
        }
        return null;
    }

    /** 取某工具某平台最新 PUBLISHED 版本；不存在返回 null。 */
    public ToolVersion findLatestPublished(String toolId, String platform) {
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, tool_id, version, platform, start_command, status, file_count, total_size, release_note, created_at, published_at "
                             + "FROM tool_version WHERE tool_id=? AND platform=? AND status='PUBLISHED' "
                             + "ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, toolId);
            ps.setString(2, platform);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapToolVersion(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询最新版本失败：" + e.getMessage(), e);
        }
        return null;
    }

    /** 取工具版本文件清单。 */
    public List<ToolVersionFile> listToolVersionFiles(int toolVersionId) {
        List<ToolVersionFile> list = new ArrayList<>();
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, tool_version_id, file_path, sha256, size, created_at FROM tool_version_file WHERE tool_version_id=?")) {
            ps.setInt(1, toolVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ToolVersionFile f = new ToolVersionFile();
                    f.id = rs.getInt("id");
                    f.toolVersionId = rs.getInt("tool_version_id");
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

    /** 取工具版本文件物理路径。 */
    public Path resolveToolVersionFile(String toolId, String version, String platform, String relativePath) {
        return Paths.get(toolFileRoot, toolId, version, platform, relativePath);
    }

    // ============== 内部辅助 ==============

    private boolean existsToolVersion(String toolId, String version, String platform) {
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM tool_version WHERE tool_id=? AND version=? AND platform=?")) {
            ps.setString(1, toolId);
            ps.setString(2, version);
            ps.setString(3, platform);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询版本存在性失败：" + e.getMessage(), e);
        }
    }

    /** 解压 zip 并落地到 toolfiles 目录，同时计算每个文件的 SHA-256。 */
    private List<UnpackedFile> unzipAndStore(byte[] zipBytes, String toolId, String version, String platform) {
        List<UnpackedFile> result = new ArrayList<>();
        Path baseDir = Paths.get(toolFileRoot, toolId, version, platform);
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new RuntimeException("创建目录失败：" + baseDir, e);
        }
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                // 安全：禁止 .. 路径穿越
                if (name.contains("..") || name.startsWith("/")) {
                    throw new IllegalArgumentException("非法文件路径：" + name);
                }
                Path target = baseDir.resolve(name).normalize();
                if (!target.startsWith(baseDir)) {
                    throw new IllegalArgumentException("非法文件路径：" + name);
                }
                Files.createDirectories(target.getParent());
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                long size = 0;
                try (java.io.OutputStream os = Files.newOutputStream(target)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        md.update(buf, 0, n);
                        size += n;
                    }
                }
                String sha = bytesToHex(md.digest());
                UnpackedFile f = new UnpackedFile();
                f.relativePath = name.replace('\\', '/');
                f.sha256 = sha;
                f.size = size;
                result.add(f);
                zis.closeEntry();
            }
        } catch (Exception e) {
            throw new RuntimeException("解压失败：" + e.getMessage(), e);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("zip 包为空");
        }
        return result;
    }

    private Tool mapTool(ResultSet rs) throws SQLException {
        Tool t = new Tool();
        t.id = rs.getInt("id");
        t.toolId = rs.getString("tool_id");
        t.name = rs.getString("name");
        t.description = rs.getString("description");
        t.ownerUserId = rs.getInt("owner_user_id");
        t.defaultStartCmd = rs.getString("default_start_cmd");
        t.createdAt = rs.getString("created_at");
        t.ownerUsername = rs.getString("owner_username");
        return t;
    }

    private ToolVersion mapToolVersion(ResultSet rs) throws SQLException {
        ToolVersion v = new ToolVersion();
        v.id = rs.getInt("id");
        v.toolId = rs.getString("tool_id");
        v.version = rs.getString("version");
        v.platform = rs.getString("platform");
        v.startCommand = rs.getString("start_command");
        v.status = rs.getString("status");
        v.fileCount = rs.getInt("file_count");
        v.totalSize = rs.getLong("total_size");
        v.releaseNote = rs.getString("release_note");
        v.createdAt = rs.getString("created_at");
        v.publishedAt = rs.getString("published_at");
        return v;
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    /** 解包后单个文件信息。 */
    private static class UnpackedFile {
        String relativePath;
        String sha256;
        long size;
    }
}

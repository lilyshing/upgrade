package com.aipro.upgrade.server.db;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 数据库管理器
 * <p>
 * 职责：
 * - 维护单个 SQLite 数据库文件（默认 ./upgrade.db，随服务器 jar 同目录）
 * - 启动时初始化全部 9 张表（DDL 见 {@link SchemaInitializer}）
 * - 提供获取 Connection 的入口（SQLite 内嵌，单连接足够；并发由 SQLite 自身 WAL 模式承载）
 * <p>
 * Win7 离线场景：sqlite-jdbc 自带各平台 native 库，运行时从 jar 解压到 java.io.tmpdir；
 * 如该目录不可写，可通过 -Dorg.sqlite.tmpdir=... 指定。
 */
public final class DatabaseManager {

    private static volatile DatabaseManager instance;

    private final String dbUrl;
    private volatile boolean initialized = false;

    private DatabaseManager(String dbPath) {
        File f = new File(dbPath);
        // 父目录确保存在
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        // 启用外键 + WAL 模式，提升并发与一致性
        this.dbUrl = "jdbc:sqlite:" + dbPath;
    }

    /** 获取单例（懒加载，启动时由 main 调用 init 初始化）。 */
    public static DatabaseManager getInstance() {
        if (instance == null) {
            synchronized (DatabaseManager.class) {
                if (instance == null) {
                    // 默认数据库文件路径：当前工作目录下 upgrade.db
                    instance = new DatabaseManager("upgrade.db");
                }
            }
        }
        return instance;
    }

    /** 用指定 db 路径初始化单例（仅服务器启动时调用一次）。 */
    public static void init(String dbPath) {
        synchronized (DatabaseManager.class) {
            if (instance != null) {
                throw new IllegalStateException("DatabaseManager 已初始化，不可重复");
            }
            instance = new DatabaseManager(dbPath);
        }
    }

    /** 取一个新连接（调用方负责 close）。SQLite 内嵌模式：每次获取代价很低。 */
    public Connection getConnection() throws SQLException {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    initialize();
                    initialized = true;
                }
            }
        }
        Connection conn = DriverManager.getConnection(dbUrl);
        // 开启外键约束
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
        }
        return conn;
    }

    /** 初始化数据库：建表 + 默认数据。 */
    private void initialize() {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA journal_mode = WAL");
            // 建 9 张表
            for (String ddl : SchemaInitializer.DDL_LIST) {
                try {
                    st.execute(ddl);
                } catch (SQLException e) {
                    System.err.println("[ERROR] 执行 DDL 失败：" + ddl);
                    throw e;
                }
            }
            // 写入默认 admin 账号（密码 admin，BCrypt 哈希，首次登录强制改密）
            // 注意：BCrypt 哈希以 $2a$ 开头，SQLite JDBC 会把 $xxx 当成命名参数占位符，
            //       导致 SQL 不完整（incomplete input）；必须用 PreparedStatement 参数化。
            //       哈希值每次启动可能不同（BCrypt 自带随机 salt），但只要库内一致即可正常登录。
            String defaultHash = com.aipro.upgrade.server.auth.PasswordHasher.hash("admin");
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO admin_user(username, password_hash, role, status, display_name, must_change_pwd, created_at) "
                            + "VALUES(?, ?, 'ADMIN', 'ACTIVE', 'Administrator', 1, datetime('now'))")) {
                ps.setString(1, "admin");
                ps.setString(2, defaultHash);
                ps.executeUpdate();
            } catch (SQLException ignored) {
                // admin 已存在则跳过
            }
        } catch (SQLException e) {
            throw new RuntimeException("初始化 SQLite 数据库失败：" + e.getMessage(), e);
        }
    }
}

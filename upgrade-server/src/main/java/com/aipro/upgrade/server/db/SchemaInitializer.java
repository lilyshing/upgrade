package com.aipro.upgrade.server.db;

/**
 * SQLite 数据表 DDL 定义（对应 PRD §4.2 共 9 张表）
 * <p>
 * 字符集 UTF-8（SQLite 默认）；外键约束启用（{@link DatabaseManager} 已开启 PRAGMA foreign_keys = ON）
 * <p>
 * 字段含义见 PRD §4.2 各表定义。
 * <p>
 * 注意：DDL 字符串拼接不能使用 `-- ` 行内注释（Java 字符串无换行会把后续全部当注释），
 *      字段含义说明放本类的 Javadoc 或字段命名上。
 */
public final class SchemaInitializer {

    private SchemaInitializer() {
    }

    /** 全部表 DDL 列表（按依赖顺序排列，便于初始化时顺序执行）。 */
    public static final String[] DDL_LIST = {

            // 1. 后台用户表（admin_user）：role=ADMIN/USER，status=ACTIVE/DISABLED，must_change_pwd=0/1
            "CREATE TABLE IF NOT EXISTS admin_user ("
                    + "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "  username TEXT NOT NULL UNIQUE,"
                    + "  password_hash TEXT NOT NULL,"
                    + "  role TEXT NOT NULL,"
                    + "  status TEXT NOT NULL,"
                    + "  display_name TEXT,"
                    + "  must_change_pwd INTEGER NOT NULL DEFAULT 1,"
                    + "  created_at TEXT NOT NULL,"
                    + "  last_login_at TEXT"
                    + ")",

            // 2. 版本表（version）：SDK 升级用版本，status=DRAFT/PUBLISHED/OFFLINE
            "CREATE TABLE IF NOT EXISTS version ("
                    + "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "  version_no TEXT NOT NULL UNIQUE,"
                    + "  status TEXT NOT NULL,"
                    + "  release_note TEXT,"
                    + "  file_count INTEGER,"
                    + "  total_size INTEGER,"
                    + "  created_at TEXT,"
                    + "  published_at TEXT"
                    + ")",

            // 3. 版本文件清单（version_file）
            "CREATE TABLE IF NOT EXISTS version_file ("
                    + "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "  version_id INTEGER,"
                    + "  file_path TEXT,"
                    + "  sha256 TEXT,"
                    + "  size INTEGER,"
                    + "  created_at TEXT,"
                    + "  FOREIGN KEY(version_id) REFERENCES version(id)"
                    + ")",

            // 4. 客户端更新记录（client_update_record）：result=SUCCESS/FAIL
            "CREATE TABLE IF NOT EXISTS client_update_record ("
                    + "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "  client_id TEXT,"
                    + "  client_ip TEXT,"
                    + "  old_version TEXT,"
                    + "  new_version TEXT,"
                    + "  update_time TEXT,"
                    + "  result TEXT,"
                    + "  fail_reason TEXT,"
                    + "  duration_ms INTEGER"
                    + ")",

            // 5. 工具表（tool）：对外分发的工具，绑定 owner_user_id
            "CREATE TABLE IF NOT EXISTS tool ("
                    + "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "  tool_id TEXT NOT NULL UNIQUE,"
                    + "  name TEXT,"
                    + "  description TEXT,"
                    + "  owner_user_id INTEGER,"
                    + "  default_start_cmd TEXT,"
                    + "  created_at TEXT,"
                    + "  FOREIGN KEY(owner_user_id) REFERENCES admin_user(id)"
                    + ")",

            // 6. 工具本体版本（tool_version）：按平台，UNIQUE(tool_id, version, platform)
            "CREATE TABLE IF NOT EXISTS tool_version ("
                    + "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "  tool_id TEXT NOT NULL,"
                    + "  version TEXT NOT NULL,"
                    + "  platform TEXT NOT NULL,"
                    + "  start_command TEXT,"
                    + "  status TEXT NOT NULL,"
                    + "  file_count INTEGER,"
                    + "  total_size INTEGER,"
                    + "  release_note TEXT,"
                    + "  created_at TEXT,"
                    + "  published_at TEXT,"
                    + "  UNIQUE(tool_id, version, platform),"
                    + "  FOREIGN KEY(tool_id) REFERENCES tool(tool_id)"
                    + ")",

            // 7. 工具本体文件清单（tool_version_file）
            "CREATE TABLE IF NOT EXISTS tool_version_file ("
                    + "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "  tool_version_id INTEGER,"
                    + "  file_path TEXT,"
                    + "  sha256 TEXT,"
                    + "  size INTEGER,"
                    + "  created_at TEXT,"
                    + "  FOREIGN KEY(tool_version_id) REFERENCES tool_version(id)"
                    + ")",

            // 8. 灰度策略表（push_policy）：按工具独立配置
            "CREATE TABLE IF NOT EXISTS push_policy ("
                    + "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "  tool_id TEXT NOT NULL UNIQUE,"
                    + "  enabled INTEGER NOT NULL DEFAULT 1,"
                    + "  threshold INTEGER NOT NULL DEFAULT 10,"
                    + "  current_count INTEGER NOT NULL DEFAULT 0,"
                    + "  whitelist TEXT,"
                    + "  updated_at TEXT,"
                    + "  FOREIGN KEY(tool_id) REFERENCES tool(tool_id)"
                    + ")",

            // 9. 下载统计表（download_stat）：stage=BOOTSTRAP/FILE
            "CREATE TABLE IF NOT EXISTS download_stat ("
                    + "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "  tool_id TEXT,"
                    + "  stage TEXT,"
                    + "  platform TEXT,"
                    + "  client_ip TEXT,"
                    + "  downloaded_at TEXT"
                    + ")",

            // 索引：客户端更新记录按版本与时间筛选常用
            "CREATE INDEX IF NOT EXISTS idx_client_update_record_version ON client_update_record(new_version, update_time)",
            "CREATE INDEX IF NOT EXISTS idx_client_update_record_ip ON client_update_record(client_ip)",
            "CREATE INDEX IF NOT EXISTS idx_download_stat_tool ON download_stat(tool_id, stage, downloaded_at)",
            "CREATE INDEX IF NOT EXISTS idx_version_status ON version(status)",
            "CREATE INDEX IF NOT EXISTS idx_tool_version_tool ON tool_version(tool_id, platform, status)",
    };
}

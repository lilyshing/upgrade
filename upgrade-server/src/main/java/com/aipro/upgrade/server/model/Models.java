package com.aipro.upgrade.server.model;

/**
 * 服务端数据模型 DTO 集合（对应 PRD §4.2 各表）
 * <p>
 * 全部为简单 POJO，仅含字段与 getter/setter，便于在 Service 与 Handler 间传递。
 */
public final class Models {

    private Models() {
    }

    /** 后台用户（admin_user 表对应） */
    public static class AdminUser {
        public Integer id;
        public String username;
        public String passwordHash;
        public String role;        // ADMIN / USER
        public String status;      // ACTIVE / DISABLED
        public String displayName;
        public Integer mustChangePwd; // 0/1
        public String createdAt;
        public String lastLoginAt;
    }

    /** 版本（version 表对应，SDK 升级用） */
    public static class Version {
        public Integer id;
        public String versionNo;
        public String status;       // DRAFT / PUBLISHED / OFFLINE
        public String releaseNote;
        public Integer fileCount;
        public Long totalSize;
        public String createdAt;
        public String publishedAt;
    }

    /** 版本文件清单条目（version_file 表对应） */
    public static class VersionFile {
        public Integer id;
        public Integer versionId;
        public String filePath;
        public String sha256;
        public Long size;
        public String createdAt;
    }

    /** 客户端更新记录（client_update_record 表对应） */
    public static class ClientUpdateRecord {
        public Integer id;
        public String clientId;
        public String clientIp;
        public String oldVersion;
        public String newVersion;
        public String updateTime;
        public String result;     // SUCCESS / FAIL
        public String failReason;
        public Long durationMs;
    }

    /** 对外分发工具（tool 表对应） */
    public static class Tool {
        public Integer id;
        public String toolId;
        public String name;
        public String description;
        public Integer ownerUserId;
        public String defaultStartCmd;
        public String createdAt;
        public String ownerUsername; // 连表查询时填充
    }

    /** 工具本体版本（tool_version 表对应） */
    public static class ToolVersion {
        public Integer id;
        public String toolId;
        public String version;
        public String platform;    // win / linux
        public String startCommand;
        public String status;      // DRAFT / PUBLISHED / OFFLINE
        public Integer fileCount;
        public Long totalSize;
        public String releaseNote;
        public String createdAt;
        public String publishedAt;
    }

    /** 工具本体文件清单条目（tool_version_file 表对应） */
    public static class ToolVersionFile {
        public Integer id;
        public Integer toolVersionId;
        public String filePath;
        public String sha256;
        public Long size;
        public String createdAt;
    }

    /** 灰度策略（push_policy 表对应，按 tool_id 独立） */
    public static class PushPolicy {
        public Integer id;
        public String toolId;
        public Integer enabled;     // 0/1
        public Integer threshold;
        public Integer currentCount;
        public String whitelist;   // CSV
        public String updatedAt;
    }
}

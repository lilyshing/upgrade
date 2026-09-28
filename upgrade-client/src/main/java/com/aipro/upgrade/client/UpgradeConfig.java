package com.aipro.upgrade.client;

import com.aipro.upgrade.client.core.JsonParser;
import com.aipro.upgrade.client.util.IoUtil;
import com.aipro.upgrade.client.util.UuidUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;

/**
 * 升级配置加载器（F-C-01）
 * <p>
 * 加载顺序（优先级从高到低）：
 * <ol>
 *   <li>工作目录 ./upgrade.properties</li>
 *   <li>classpath 下 upgrade.properties（随宿主打包）</li>
 *   <li>内置默认值</li>
 * </ol>
 * <p>
 * 配置项与默认值见 PRD §4.1。
 * <p>
 * 当前版本号读取：优先 version.json 的 version 字段，缺失时用 upgrade.current.version。
 */
public final class UpgradeConfig {

    // ===== 配置键 =====
    public static final String KEY_SERVER_URL = "upgrade.server.url";
    public static final String KEY_STRATEGY = "upgrade.strategy";
    public static final String KEY_CLIENT_ID = "upgrade.client.id";
    public static final String KEY_RETRY_MAX = "upgrade.retry.max";
    public static final String KEY_RETRY_BACKOFF_MS = "upgrade.retry.backoff.ms";
    public static final String KEY_DOWNLOAD_DIR = "upgrade.download.dir";
    public static final String KEY_BACKUP_DIR = "upgrade.backup.dir";
    public static final String KEY_CURRENT_VERSION = "upgrade.current.version";
    public static final String KEY_SKIP_VERSION = "upgrade.skip.version";
    public static final String KEY_PROMPT_IMPL = "upgrade.prompt.impl";

    // ===== 默认值（与 PRD §4.1 一致） =====
    public static final String DEFAULT_SERVER_URL = "http://127.0.0.1:8090";
    public static final String DEFAULT_STRATEGY = "ASK";
    public static final int DEFAULT_RETRY_MAX = 3;
    public static final long DEFAULT_RETRY_BACKOFF_MS = 2000L;
    public static final String DEFAULT_DOWNLOAD_DIR = "./upgrade/tmp";
    public static final String DEFAULT_BACKUP_DIR = "./upgrade/backup";

    // ===== 关键文件名 =====
    public static final String CONFIG_FILE = "upgrade.properties";
    public static final String VERSION_FILE = "version.json";
    public static final String UPDATE_LOG_FILE = "update_log.txt";
    public static final String MANIFEST_FILE = "file_manifest.json";

    private final Properties props;
    private final Path workDir;
    private final Path downloadDir;
    private final Path backupDir;
    private final Path configFile;
    private final Path versionFile;
    private final Path manifestFile;
    private final Path updateLogFile;
    private String clientIdCache;

    private UpgradeConfig(Properties props, Path workDir) {
        this.props = props;
        this.workDir = workDir;
        this.downloadDir = workDir.resolve(stripDotPrefix(getProperty(KEY_DOWNLOAD_DIR, DEFAULT_DOWNLOAD_DIR)));
        this.backupDir = workDir.resolve(stripDotPrefix(getProperty(KEY_BACKUP_DIR, DEFAULT_BACKUP_DIR)));
        this.configFile = workDir.resolve(CONFIG_FILE);
        this.versionFile = workDir.resolve(VERSION_FILE);
        this.manifestFile = workDir.resolve(MANIFEST_FILE);
        this.updateLogFile = workDir.resolve(UPDATE_LOG_FILE);
    }

    /**
     * 从工作目录加载配置；工作目录默认取 System.getProperty("user.dir")。
     */
    public static UpgradeConfig load() {
        return load(Paths.get(System.getProperty("user.dir")));
    }

    /**
     * 从指定工作目录加载配置。
     * 加载顺序：工作目录 upgrade.properties → classpath 同名文件 → 默认值。
     */
    public static UpgradeConfig load(Path workDir) {
        Properties props = new Properties();
        // 1. classpath
        try (InputStream in = UpgradeConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            // 忽略，使用默认值
        }
        // 2. 工作目录覆盖
        Path userFile = workDir.resolve(CONFIG_FILE);
        if (Files.exists(userFile)) {
            try (InputStream in = Files.newInputStream(userFile)) {
                props.load(in);
            } catch (IOException e) {
                // 忽略，沿用 classpath 配置
            }
        }
        return new UpgradeConfig(props, workDir);
    }

    /** 取属性值；不存在返回默认值。 */
    public String getProperty(String key, String def) {
        String v = props.getProperty(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    /** 取属性值（int）；不存在或解析失败返回默认值。 */
    public int getIntProperty(String key, int def) {
        String v = props.getProperty(key);
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 取属性值（long）。 */
    public long getLongProperty(String key, long def) {
        String v = props.getProperty(key);
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 服务器 URL。 */
    public String getServerUrl() {
        return getProperty(KEY_SERVER_URL, DEFAULT_SERVER_URL);
    }

    /** 升级策略。 */
    public UpgradeStrategy getStrategy() {
        return UpgradeStrategy.fromString(getProperty(KEY_STRATEGY, DEFAULT_STRATEGY));
    }

    /** 客户端 ID；缺失自动生成 UUID 并写入配置文件。 */
    public String getClientId() {
        if (clientIdCache != null) {
            return clientIdCache;
        }
        String id = props.getProperty(KEY_CLIENT_ID);
        if (id == null || id.isEmpty()) {
            // 优先随机 UUID，避免暴露硬件信息
            id = UuidUtil.random();
            props.setProperty(KEY_CLIENT_ID, id);
            // 持久化到工作目录 upgrade.properties（不存在则不写）
            persistClientId(id);
        }
        clientIdCache = id;
        return id;
    }

    /** 把自动生成的 clientId 写回 upgrade.properties，避免下次重新生成。 */
    private void persistClientId(String id) {
        Path file = configFile;
        Properties out = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                out.load(in);
            } catch (IOException e) {
                // 忽略
            }
        }
        out.setProperty(KEY_CLIENT_ID, id);
        try (java.io.OutputStream os = Files.newOutputStream(file,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            out.store(os, "auto-upgrade client config");
        } catch (IOException e) {
            // 持久化失败不影响运行时使用
        }
    }

    /** 最大重试次数。 */
    public int getRetryMax() {
        return getIntProperty(KEY_RETRY_MAX, DEFAULT_RETRY_MAX);
    }

    /** 重试退避基数（毫秒）。 */
    public long getRetryBackoffMs() {
        return getLongProperty(KEY_RETRY_BACKOFF_MS, DEFAULT_RETRY_BACKOFF_MS);
    }

    /** 下载临时目录。 */
    public Path getDownloadDir() {
        return downloadDir;
    }

    /** 备份目录。 */
    public Path getBackupDir() {
        return backupDir;
    }

    /** 工作目录。 */
    public Path getWorkDir() {
        return workDir;
    }

    /** version.json 路径。 */
    public Path getVersionFile() {
        return versionFile;
    }

    /** file_manifest.json 路径。 */
    public Path getManifestFile() {
        return manifestFile;
    }

    /** update_log.txt 路径。 */
    public Path getUpdateLogFile() {
        return updateLogFile;
    }

    /** 配置项中显式声明的当前版本号（fallback）。 */
    public String getConfiguredCurrentVersion() {
        return getProperty(KEY_CURRENT_VERSION, "0.0.0");
    }

    /** 已跳过的版本号。 */
    public String getSkipVersion() {
        return getProperty(KEY_SKIP_VERSION, "");
    }

    /** 自定义交互实现类全名（空表示用默认 Swing）。 */
    public String getPromptImpl() {
        return getProperty(KEY_PROMPT_IMPL, "");
    }

    /**
     * 取当前版本号：优先读 version.json 的 version 字段，缺失回退到 upgrade.current.version。
     */
    public String getCurrentVersion() {
        try {
            String content = IoUtil.readText(versionFile);
            if (content != null && !content.isEmpty()) {
                Object parsed = JsonParser.parse(content);
                if (parsed instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) parsed;
                    String v = JsonParser.getString(m, "version");
                    if (v != null && !v.isEmpty()) {
                        return v;
                    }
                }
            }
        } catch (RuntimeException | IOException e) {
            // 解析失败回退到配置项
        }
        return getConfiguredCurrentVersion();
    }

    /** 去掉路径开头的 ./ 前缀，避免与 workDir.resolve 冲突。 */
    private static String stripDotPrefix(String path) {
        if (path == null) {
            return "";
        }
        if (path.startsWith("./")) {
            return path.substring(2);
        }
        return path;
    }
}

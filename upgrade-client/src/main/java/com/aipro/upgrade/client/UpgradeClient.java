package com.aipro.upgrade.client;

import com.aipro.upgrade.client.core.DownloadManager;
import com.aipro.upgrade.client.core.FileEntry;
import com.aipro.upgrade.client.core.FileReplacer;
import com.aipro.upgrade.client.core.HttpClient;
import com.aipro.upgrade.client.core.JsonParser;
import com.aipro.upgrade.client.core.PolicyChecker;
import com.aipro.upgrade.client.core.ReportClient;
import com.aipro.upgrade.client.core.RestartLauncher;
import com.aipro.upgrade.client.core.UpdateLogger;
import com.aipro.upgrade.client.core.VersionComparator;
import com.aipro.upgrade.client.core.VersionInfo;
import com.aipro.upgrade.client.impl.DefaultSwingPrompt;
import com.aipro.upgrade.client.impl.NoOpPrompt;
import com.aipro.upgrade.client.util.IoUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 升级客户端门面（F-C-08）
 * <p>
 * 单例模式，宿主启动时一行调用：
 * <pre>
 *   UpgradeClient.getInstance().checkAndUpgrade();
 * </pre>
 * <p>
 * 升级流程（PRD §2.1）：
 * <ol>
 *   <li>加载 UpgradeConfig（工作目录优先，缺失回退 classpath / 默认值）</li>
 *   <li>策略为 NONE → 直接返回</li>
 *   <li>PolicyChecker 查询 /api/policy/status，若 enabled && remaining<=0 && !whitelistAllowed → 返回</li>
 *   <li>HttpClient GET /api/version/latest 拉取 VersionInfo</li>
 *   <li>VersionComparator 比对：服务器版本 <= 本地 → 返回</li>
 *   <li>ASK 策略 → DefaultSwingPrompt.onAsk 返回 false → 返回</li>
 *   <li>DownloadManager 下载每个文件（断点续传 + 重试）</li>
 *   <li>逐文件 SHA-256 校验（已并入下载流程）</li>
 *   <li>FileReplacer：backup 旧文件 → 写新文件 → 写 file_manifest.json → 写 version.json</li>
 *   <li>ReportClient POST /api/record/update 上报结果</li>
 *   <li>UpdateLogger 写 update_log.txt</li>
 *   <li>生成 restart.bat 触发，宿主应主动退出</li>
 * </ol>
 * <p>
 * 所有异常均被捕获并上报 / 记日志；不会向宿主抛出。
 */
public final class UpgradeClient {

    /** 单例。 */
    private static volatile UpgradeClient instance;

    private volatile UpgradeConfig config;
    private volatile UpgradePrompt prompt;
    /** 宿主可注册的启动命令（用于 restart.bat / restart.sh）。 */
    private volatile String startCommand;
    /** 宿主可注册的工作目录覆盖；null 表示使用默认 user.dir。 */
    private volatile Path workDirOverride;

    private UpgradeClient() {
    }

    /** 取单例。 */
    public static UpgradeClient getInstance() {
        if (instance == null) {
            synchronized (UpgradeClient.class) {
                if (instance == null) {
                    instance = new UpgradeClient();
                }
            }
        }
        return instance;
    }

    /**
     * 注册自定义交互实现；null 表示用默认 Swing。
     * 必须在 {@link #checkAndUpgrade()} 之前调用。
     */
    public void setUpgradePrompt(UpgradePrompt p) {
        this.prompt = p;
    }

    /**
     * 注册宿主启动命令（restart 脚本拉起新版宿主用）。
     * 如未注册，SDK 自动用 System.getProperty("sun.java.command") 兜底。
     */
    public void setStartCommand(String cmd) {
        this.startCommand = cmd;
    }

    /**
     * 覆盖工作目录（默认 System.getProperty("user.dir")）。
     * 必须在 {@link #checkAndUpgrade()} 之前调用。
     */
    public void setWorkDir(Path workDir) {
        this.workDirOverride = workDir;
    }

    /**
     * 热切换升级策略（F-C-01）。
     * 在 checkAndUpgrade 之前调用生效。
     */
    public void setStrategy(UpgradeStrategy strategy) {
        // 通过覆盖 config 实现热切换
        UpgradeConfig current = currentConfig();
        // 直接修改 properties 不可行（不可变视图），此处通过 workDir 与重载实现
        // 简化处理：写入临时 override 字段，后续重新加载时读取
        // 这里直接把 prompt 与 strategy 缓存到客户端
        this.strategyOverride = strategy;
    }

    private volatile UpgradeStrategy strategyOverride;

    /** 主入口：检查并执行升级（一行调用）。 */
    public void checkAndUpgrade() {
        long start = System.currentTimeMillis();
        UpgradeConfig cfg = currentConfig();
        UpgradeStrategy strategy = strategyOverride != null ? strategyOverride : cfg.getStrategy();
        UpdateLogger logger = new UpdateLogger(cfg.getUpdateLogFile());
        ReportClient reporter = new ReportClient(cfg.getServerUrl());
        String oldVersion = cfg.getCurrentVersion();
        String clientId = cfg.getClientId();

        // NONE 策略：直接返回
        if (strategy == UpgradeStrategy.NONE) {
            return;
        }

        UpgradePrompt p = resolvePrompt(strategy);
        VersionInfo info = null;
        try {
            // 1. 灰度策略检查
            PolicyChecker checker = new PolicyChecker(cfg.getServerUrl());
            PolicyChecker.PolicyStatus status = checker.query();
            if (PolicyChecker.shouldSkip(status)) {
                logger.append(oldVersion, oldVersion, "SKIP",
                        System.currentTimeMillis() - start, "灰度阈值已满，本次跳过");
                p.onResult(false, "灰度阈值已满，本次跳过升级");
                return;
            }

            // 2. 拉取最新版本元信息
            info = fetchLatest(cfg);
            if (info == null || info.getVersion() == null) {
                logger.append(oldVersion, oldVersion, "SKIP",
                        System.currentTimeMillis() - start, "未获取到版本信息");
                return;
            }

            // 3. 版本比对
            if (!VersionComparator.greaterThan(info.getVersion(), oldVersion)) {
                // 服务器版本不高于本地：无需升级
                return;
            }

            // 4. ASK 策略询问用户
            if (strategy == UpgradeStrategy.ASK) {
                boolean ok = p.onAsk(info, oldVersion, cfg.getSkipVersion());
                if (!ok) {
                    logger.append(oldVersion, info.getVersion(), "SKIP",
                            System.currentTimeMillis() - start, "用户拒绝");
                    // 上报 SKIP（视为失败但非异常）
                    reporter.report(clientId, oldVersion, info.getVersion(), "SKIP", "用户拒绝",
                            System.currentTimeMillis() - start);
                    return;
                }
            }

            // 5. 下载与校验
            List<Path> downloaded = downloadAll(cfg, info, p);

            // 6. 替换文件 + 写 manifest + version.json
            FileReplacer replacer = new FileReplacer(cfg.getWorkDir(), cfg.getBackupDir(),
                    cfg.getManifestFile(), cfg.getVersionFile());
            replacer.replace(oldVersion, info.getVersion(), info.getFiles(), downloaded);

            long duration = System.currentTimeMillis() - start;
            // 7. 上报成功
            reporter.report(clientId, oldVersion, info.getVersion(), "SUCCESS", "", duration);
            // 8. 写日志
            logger.append(oldVersion, info.getVersion(), "SUCCESS", duration, "");
            // 9. 触发重启
            try {
                triggerRestart(cfg);
                p.onResult(true, "升级成功，宿主即将退出重启");
            } catch (IOException e) {
                p.onResult(true, "升级成功，但生成重启脚本失败：" + e.getMessage()
                        + "；请手动重启宿主");
            }
        } catch (Throwable t) {
            long duration = System.currentTimeMillis() - start;
            String reason = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            logger.append(oldVersion, info == null ? "?" : info.getVersion(), "FAIL", duration, reason);
            reporter.report(clientId, oldVersion,
                    info == null ? "?" : info.getVersion(), "FAIL", reason, duration);
            p.onResult(false, "升级失败：" + reason);
        }
    }

    /** 加载 / 复用配置。 */
    private UpgradeConfig currentConfig() {
        if (config == null) {
            synchronized (this) {
                if (config == null) {
                    Path workDir = workDirOverride != null ? workDirOverride
                            : java.nio.file.Paths.get(System.getProperty("user.dir"));
                    config = UpgradeConfig.load(workDir);
                }
            }
        }
        return config;
    }

    /** 解析交互实现：NONE 用 NoOpPrompt；否则优先用户自定义，缺失用 DefaultSwingPrompt。 */
    private UpgradePrompt resolvePrompt(UpgradeStrategy strategy) {
        if (strategy == UpgradeStrategy.NONE) {
            return new NoOpPrompt();
        }
        if (prompt != null) {
            return prompt;
        }
        String impl = currentConfig().getPromptImpl();
        if (impl == null || impl.isEmpty()) {
            return new DefaultSwingPrompt();
        }
        // 反射加载用户自定义类
        try {
            Class<?> clazz = Class.forName(impl, true, Thread.currentThread().getContextClassLoader());
            return (UpgradePrompt) clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            System.err.println("[UpgradeClient] 加载自定义 UpgradePrompt 失败: " + impl
                    + " 原因=" + e.getMessage() + "，回退到默认 Swing");
            return new DefaultSwingPrompt();
        }
    }

    /** 拉取最新版本元信息。 */
    private VersionInfo fetchLatest(UpgradeConfig cfg) {
        try {
            String body = HttpClient.getJson(cfg.getServerUrl() + "/api/version/latest");
            Object parsed = JsonParser.parse(body);
            if (parsed instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) parsed;
                return VersionInfo.fromMap(m);
            }
        } catch (IOException e) {
            System.err.println("[UpgradeClient] 拉取最新版本失败: " + e.getMessage());
        } catch (RuntimeException e) {
            System.err.println("[UpgradeClient] 解析最新版本响应失败: " + e.getMessage());
        }
        return null;
    }

    /** 下载所有文件并返回本地路径列表。 */
    private List<Path> downloadAll(UpgradeConfig cfg, final VersionInfo info, final UpgradePrompt p) throws IOException {
        IoUtil.ensureDirectory(cfg.getDownloadDir());
        // 清空旧 download 目录，避免遗留旧版本文件污染
        IoUtil.deleteDirectory(cfg.getDownloadDir());
        IoUtil.ensureDirectory(cfg.getDownloadDir());

        DownloadManager dm = new DownloadManager(cfg.getServerUrl(), cfg.getDownloadDir(),
                cfg.getRetryMax(), cfg.getRetryBackoffMs(),
                new DownloadManager.ProgressCallback() {
                    @Override
                    public void onProgress(String fileName, int fileIndex, int fileTotal,
                                            long bytesWritten, long fileTotalSize, int attempt) {
                        p.onProgress(bytesWritten, fileTotalSize, fileName, fileIndex, fileTotal, attempt);
                    }
                });
        List<Path> result = new ArrayList<>();
        List<FileEntry> files = info.getFiles();
        int total = files.size();
        for (int i = 0; i < total; i++) {
            FileEntry e = files.get(i);
            Path p2 = dm.downloadAndVerify(info.getVersion(), e, i + 1, total);
            result.add(p2);
        }
        return result;
    }

    /** 触发 restart 脚本生成与启动。 */
    private void triggerRestart(UpgradeConfig cfg) throws IOException {
        String cmd = startCommand;
        if (cmd == null || cmd.isEmpty()) {
            // 兜底：尝试从 sun.java.command 解析
            String jcmd = System.getProperty("sun.java.command");
            if (jcmd != null && !jcmd.isEmpty()) {
                cmd = jcmd;
            } else {
                cmd = "";
            }
        }
        RestartLauncher launcher = new RestartLauncher(cfg.getDownloadDir());
        launcher.launch(cfg.getWorkDir(), cmd);
    }

    /** 仅用于测试：替换 config 实例。 */
    void setConfigForTest(UpgradeConfig cfg) {
        this.config = cfg;
    }
}

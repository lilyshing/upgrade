package com.aipro.upgrade.server;

import com.aipro.upgrade.server.db.DatabaseManager;
import com.aipro.upgrade.server.http.Router;
import com.aipro.upgrade.server.service.BootstrapService;
import com.aipro.upgrade.server.service.ToolService;
import com.aipro.upgrade.server.service.VersionService;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * 服务器端启动入口
 * <p>
 * 启动流程：
 * 1. 初始化 SQLite 数据库（建表 + 默认 admin 账号）
 * 2. 设置各 Service 的运行时参数（文件存储根、服务器 base URL）
 * 3. 启动 HttpServer（默认端口 8090）
 * 4. 注册 Router 到根路径，统一分发 API 与页面
 * <p>
 * 命令行参数：
 *   java -jar upgrade-server.jar [port] [host]
 *   默认 port=8090, host=0.0.0.0
 * <p>
 * 也可通过环境变量：
 *   UPGRADE_SERVER_PORT、UPGRADE_SERVER_HOST
 * <p>
 * 服务器 base URL（用于下载器分发）：默认 http://<host>:<port>，可通过 UPGRADE_SERVER_BASE_URL 覆盖。
 */
public final class UpgradeServerMain {

    public static void main(String[] args) throws Exception {
        int port = parseInt(System.getenv("UPGRADE_SERVER_PORT"), 8090);
        String host = System.getenv("UPGRADE_SERVER_HOST");
        if (host == null || host.isEmpty()) {
            host = "0.0.0.0";
        }
        if (args.length >= 1) {
            port = parseInt(args[0], port);
        }
        if (args.length >= 2) {
            host = args[1];
        }

        // 1. 初始化数据库
        File dbFile = new File("upgrade.db");
        DatabaseManager.init(dbFile.getAbsolutePath());
        DatabaseManager.getInstance().getConnection().close(); // 触发建表

        // 2. 设置 Service 运行时参数
        File toolFilesDir = new File("toolfiles");
        File versionFilesDir = new File("versionfiles");
        if (!toolFilesDir.exists()) {
            toolFilesDir.mkdirs();
        }
        if (!versionFilesDir.exists()) {
            versionFilesDir.mkdirs();
        }
        ToolService.getInstance().setToolFileRoot(toolFilesDir.getAbsolutePath());
        VersionService.getInstance().setVersionFileRoot(versionFilesDir.getAbsolutePath());

        String baseUrl = System.getenv("UPGRADE_SERVER_BASE_URL");
        if (baseUrl == null || baseUrl.isEmpty()) {
            String displayHost = "0.0.0.0".equals(host) ? "127.0.0.1" : host;
            baseUrl = "http://" + displayHost + ":" + port;
        }
        BootstrapService.getInstance().setServerBaseUrl(baseUrl);

        // 3. 启动 HTTP 服务
        InetSocketAddress addr = new InetSocketAddress(host, port);
        HttpServer server = HttpServer.create(addr, 0);
        Router router = new Router();
        router.register(server);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();

        System.out.println("============================================");
        System.out.println("  自动升级管理后台已启动");
        System.out.println("  监听: " + host + ":" + port);
        System.out.println("  Web 控制台: " + baseUrl + "/");
        System.out.println("  默认账号: admin / admin (首次登录强制改密)");
        System.out.println("  数据库: " + dbFile.getAbsolutePath());
        System.out.println("  工具文件根: " + toolFilesDir.getAbsolutePath());
        System.out.println("  SDK 版本文件根: " + versionFilesDir.getAbsolutePath());
        System.out.println("============================================");
        System.out.println("提示: Ctrl+C 可停止服务");

        // 注入关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[INFO] 正在停止服务...");
            server.stop(2);
        }));
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}

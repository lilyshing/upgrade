package com.aipro.upgrade.client.core;

import com.aipro.upgrade.client.util.IoUtil;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

/**
 * 重启器（F-C-06，PRD §6 假设 2）
 * <p>
 * 由于 Windows 上 jar 文件被 JVM 锁定，无法在 JVM 内部替换自身。
 * 通过生成独立的 restart.bat 脚本：
 * <ol>
 *   <li>等待宿主进程退出（tasklist 轮询 PID）</li>
 *   <li>用 xcopy 把 downloadDir 下所有文件覆盖到 workDir</li>
 *   <li>启动宿主命令（start cmd）</li>
 * </ol>
 * <p>
 * 仅 Windows 平台支持 bat；其他平台生成 restart.sh。
 * <p>
 * 触发后由调用方主动 System.exit 退出宿主。
 */
public final class RestartLauncher {

    /** restart 脚本文件名（生成在 downloadDir 下）。 */
    public static final String RESTART_BAT = "restart.bat";
    public static final String RESTART_SH = "restart.sh";

    private final Path downloadDir;

    public RestartLauncher(Path downloadDir) {
        this.downloadDir = downloadDir;
    }

    /**
     * 生成并触发 restart 脚本。
     *
     * @param workDir    工作目录（替换文件的目标目录）
     * @param startCommand 启动宿主的命令（如 java -jar recorder.jar）
     * @return 生成的脚本路径
     */
    public Path launch(Path workDir, String startCommand) throws IOException {
        boolean isWindows = isWindows();
        Path script = downloadDir.resolve(isWindows ? RESTART_BAT : RESTART_SH);
        String pid = currentPid();
        String content = isWindows
                ? buildBat(pid, downloadDir, workDir, startCommand)
                : buildSh(pid, downloadDir, workDir, startCommand);
        IoUtil.writeText(script, content);
        // 触发脚本（detached）
        if (isWindows) {
            // 用 cmd /c start 启动独立窗口，避免继承 IO
            new ProcessBuilder("cmd.exe", "/c", "start", "\"upgrade-restart\"",
                    "/b", script.toString()).redirectErrorStream(true).start();
        } else {
            // POSIX：加可执行权限后 nohup 启动
            try {
                Set<PosixFilePermission> perms = new HashSet<>();
                perms.add(PosixFilePermission.OWNER_READ);
                perms.add(PosixFilePermission.OWNER_WRITE);
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                Files.setPosixFilePermissions(script, perms);
            } catch (UnsupportedOperationException ignored) {
                // 非 POSIX 文件系统忽略
            }
            java.io.File devNull = new java.io.File("/dev/null");
            new ProcessBuilder("sh", script.toString())
                    .redirectOutput(ProcessBuilder.Redirect.to(devNull))
                    .redirectError(ProcessBuilder.Redirect.to(devNull)).start();
        }
        return script;
    }

    /** 获取当前 JVM 进程 PID（JDK 1.8 兼容方式）。 */
    public static String currentPid() {
        try {
            // ManagementFactory.getRuntimeMXBean().getName() 形如 "12345@host"
            String name = ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            if (at > 0) {
                return name.substring(0, at);
            }
            return name;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 判断是否 Windows 平台。 */
    public static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os != null && os.toLowerCase().contains("win");
    }

    /** 路径转 Windows 双反斜杠字符串（用于 bat 变量）。 */
    private static String winPath(Path p) {
        return p.toAbsolutePath().toString().replace("/", "\\");
    }

    /** 构造 restart.bat 内容（PRD §6 模板）。 */
    static String buildBat(String pid, Path srcDir, Path destDir, String startCmd) {
        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\r\n");
        sb.append("chcp 65001 >nul\r\n");
        sb.append("setlocal enabledelayedexpansion\r\n");
        sb.append("set \"PID=").append(pid).append("\"\r\n");
        sb.append("set \"SRC_DIR=").append(winPath(srcDir)).append("\"\r\n");
        sb.append("set \"DEST_DIR=").append(winPath(destDir)).append("\"\r\n");
        sb.append("set \"START_CMD=").append(escapeBat(startCmd == null ? "" : startCmd)).append("\"\r\n");
        sb.append("echo [INFO] 升级脚本启动，等待宿主进程 PID=!PID! 退出...\r\n");
        sb.append("if \"!PID!\"==\"\" goto replace\r\n");
        sb.append(":wait\r\n");
        sb.append("tasklist /FI \"PID eq !PID!\" 2>nul | find \"!PID!\" >nul\r\n");
        sb.append("if errorlevel 1 goto replace\r\n");
        sb.append("timeout /t 1 >nul\r\n");
        sb.append("goto wait\r\n");
        sb.append(":replace\r\n");
        sb.append("echo [INFO] 替换文件... 源=!SRC_DIR! 目标=!DEST_DIR!\r\n");
        sb.append("xcopy /E /Y /I \"!SRC_DIR!\\*\" \"!DEST_DIR!\\\" >nul\r\n");
        sb.append("if errorlevel 1 (echo [ERR] 替换失败 & exit /b 1)\r\n");
        sb.append("if \"!START_CMD!\"==\"\" (echo [INFO] 未配置启动命令，跳过启动 & exit /b 0)\r\n");
        sb.append("echo [INFO] 启动宿主: !START_CMD!\r\n");
        sb.append("cd /d \"!DEST_DIR!\"\r\n");
        sb.append("start \"\" cmd /c \"!START_CMD!\"\r\n");
        sb.append("exit /b 0\r\n");
        return sb.toString();
    }

    /** 构造 restart.sh 内容。 */
    static String buildSh(String pid, Path srcDir, Path destDir, String startCmd) {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/usr/bin/env bash\r\n");
        sb.append("set -e\r\n");
        sb.append("PID=\"").append(pid).append("\"\r\n");
        sb.append("SRC_DIR=\"").append(srcDir.toAbsolutePath()).append("\"\r\n");
        sb.append("DEST_DIR=\"").append(destDir.toAbsolutePath()).append("\"\r\n");
        sb.append("START_CMD=\"").append(startCmd == null ? "" : startCmd).append("\"\r\n");
        sb.append("echo \"[INFO] 升级脚本启动，等待进程 PID=$PID 退出...\"\r\n");
        sb.append("if [ -n \"$PID\" ]; then\r\n");
        sb.append("  while kill -0 \"$PID\" 2>/dev/null; do sleep 1; done\r\n");
        sb.append("fi\r\n");
        sb.append("echo \"[INFO] 替换文件...\"\r\n");
        sb.append("cp -rf \"$SRC_DIR\"/. \"$DEST_DIR\"/\r\n");
        sb.append("if [ -z \"$START_CMD\" ]; then echo \"[INFO] 未配置启动命令\"; exit 0; fi\r\n");
        sb.append("echo \"[INFO] 启动宿主: $START_CMD\"\r\n");
        sb.append("cd \"$DEST_DIR\"\r\n");
        sb.append("nohup $START_CMD >/dev/null 2>&1 &\r\n");
        sb.append("exit 0\r\n");
        return sb.toString();
    }

    /** bat 中需要把双引号转义为两个双引号。 */
    private static String escapeBat(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\"", "\"\"");
    }
}

package com.aipro.upgrade.client.core;

import com.aipro.upgrade.client.util.IoUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 升级历史日志记录器（F-C-07）
 * <p>
 * 本地保存 update_log.txt，记录每次升级尝试的时间、旧版本→新版本、结果、耗时。
 * <p>
 * 日志采用追加模式（UTF-8），按行写入，便于排查问题。
 */
public final class UpdateLogger {

    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final String ISO_PATTERN = "yyyy-MM-dd'T'HH:mm:ssXXX";

    private final Path logFile;

    public UpdateLogger(Path logFile) {
        this.logFile = logFile;
    }

    /**
     * 追加一条升级记录。
     *
     * @param oldVersion 旧版本
     * @param newVersion 新版本
     * @param result     结果：SUCCESS / FAIL / SKIP
     * @param durationMs 总耗时（毫秒）
     * @param message    附加消息（失败原因等，可空）
     */
    public void append(String oldVersion, String newVersion, String result,
                        long durationMs, String message) {
        try {
            if (logFile.getParent() != null) {
                java.nio.file.Files.createDirectories(logFile.getParent());
            }
            SimpleDateFormat ts = new SimpleDateFormat(DATE_PATTERN);
            SimpleDateFormat iso = new SimpleDateFormat(ISO_PATTERN);
            ts.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            iso.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(ts.format(new Date())).append("] ")
                    .append("old=").append(safe(oldVersion)).append(" -> ")
                    .append("new=").append(safe(newVersion)).append(" ")
                    .append("result=").append(safe(result)).append(" ")
                    .append("duration=").append(durationMs).append("ms ");
            if (message != null && !message.isEmpty()) {
                sb.append("msg=").append(message.replace("\n", " ").replace("\r", " "));
            }
            sb.append(System.lineSeparator());
            // 用追加模式写文件
            String line = sb.toString();
            java.nio.file.Files.write(logFile, line.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 日志失败不应影响主流程
            System.err.println("[UpdateLogger] 写日志失败: " + e.getMessage());
        }
    }

    /** null 安全。 */
    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** 取当前 ISO 8601 时间（含时区，如 2026-09-28T10:15:30+08:00）。 */
    public static String currentIso() {
        SimpleDateFormat iso = new SimpleDateFormat(ISO_PATTERN);
        iso.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return iso.format(new Date());
    }

    /** 取日志文件路径。 */
    public Path getLogFile() {
        return logFile;
    }
}

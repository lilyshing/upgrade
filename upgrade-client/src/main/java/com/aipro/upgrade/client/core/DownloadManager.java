package com.aipro.upgrade.client.core;

import com.aipro.upgrade.client.util.IoUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 下载管理器：负责把服务器版本包中的每个文件下载到本地临时目录
 * <p>
 * 能力（F-C-04 / F-C-05 / F-C-11）：
 * <ul>
 *   <li>断点续传（Range: bytes=N-）</li>
 *   <li>失败重试 max 次，指数退避（base × 2^n，n 从 0 开始）</li>
 *   <li>中文文件名 URL 百分号编码</li>
 *   <li>下载完成后逐文件 SHA-256 校验，不一致删除重试</li>
 * </ul>
 */
public final class DownloadManager {

    private final String serverUrl;
    private final Path downloadDir;
    private final int retryMax;
    private final long retryBackoffMs;
    private final ProgressCallback progressCallback;

    /** 进度回调接口（由调用方注入 UI 或日志）。 */
    public interface ProgressCallback {
        /**
         * 文件级进度回调。
         *
         * @param fileName      当前下载文件名
         * @param fileIndex     当前文件序号（从 1 起）
         * @param fileTotal     文件总数
         * @param bytesWritten  本次已写入字节（含续传前的已有字节）
         * @param fileTotalSize 当前文件预期大小（0 表示未知）
         * @param attempt       第几次重试（1 表示首次）
         */
        void onProgress(String fileName, int fileIndex, int fileTotal,
                         long bytesWritten, long fileTotalSize, int attempt);
    }

    /**
     * 构造下载管理器。
     *
     * @param serverUrl        服务器基础 URL（如 http://127.0.0.1:8090）
     * @param downloadDir      下载临时目录
     * @param retryMax         最大重试次数
     * @param retryBackoffMs   重试退避基数（毫秒）
     * @param progressCallback 进度回调；可传 null
     */
    public DownloadManager(String serverUrl, Path downloadDir, int retryMax,
                           long retryBackoffMs, ProgressCallback progressCallback) {
        this.serverUrl = stripTrailingSlash(serverUrl);
        this.downloadDir = downloadDir;
        this.retryMax = Math.max(1, retryMax);
        this.retryBackoffMs = Math.max(100, retryBackoffMs);
        this.progressCallback = progressCallback;
    }

    /** 去掉 URL 末尾的斜杠，避免拼接时双斜杠。 */
    private static String stripTrailingSlash(String s) {
        if (s == null) {
            return "";
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * 下载并校验一个文件到 downloadDir 下，文件相对路径保留。
     * 全部重试耗尽仍失败抛 IOException。
     *
     * @param version   目标版本号（拼入 /api/file/{version}/{filename}）
     * @param entry     文件清单条目
     * @param fileIndex 当前文件序号（1 起）
     * @param fileTotal 文件总数
     */
    public Path downloadAndVerify(String version, FileEntry entry, int fileIndex, int fileTotal) throws IOException {
        String encodedPath = HttpClient.encodeUrlPath(entry.getPath());
        String url = serverUrl + "/api/file/" + HttpClient.encodeUrlPath(version) + "/" + encodedPath;
        Path dest = downloadDir.resolve(entry.getPath());

        long expectedSize = entry.getSize();

        int attempt = 1;
        // 续传前已下载字节数
        long existingSize = Files.exists(dest) ? Files.size(dest) : 0L;
        boolean canResume = existingSize > 0 && existingSize < expectedSize;

        while (true) {
            try {
                long rangeStart = canResume ? existingSize : 0L;
                final int attemptNow = attempt;
                final long rangeStartNow = rangeStart;
                long written = HttpClient.download(url, dest, rangeStart, bytesWritten -> {
                    if (progressCallback != null) {
                        progressCallback.onProgress(entry.getPath(), fileIndex, fileTotal,
                                rangeStartNow + bytesWritten, expectedSize, attemptNow);
                    }
                });
                if (written < 0) {
                    // 防御性检查，理论上不会发生
                    throw new IOException("下载返回负值: " + entry.getPath());
                }
                // 下载完成校验 SHA-256
                String actual = Sha256Util.hexFile(dest);
                if (actual.equalsIgnoreCase(entry.getSha256())) {
                    return dest;
                }
                // 校验失败：删除重试，且不再续传
                Files.deleteIfExists(dest);
                canResume = false;
                existingSize = 0L;
                if (attempt >= retryMax) {
                    throw new IOException("SHA-256 校验失败，重试耗尽: " + entry.getPath()
                            + " 期望=" + entry.getSha256() + " 实际=" + actual);
                }
            } catch (IOException e) {
                if (attempt >= retryMax) {
                    throw new IOException("下载失败（重试 " + (attempt - 1) + " 次后耗尽）: " + entry.getPath()
                            + " 原因=" + e.getMessage(), e);
                }
                // 重新计算续传起点
                existingSize = Files.exists(dest) ? Files.size(dest) : 0L;
                canResume = existingSize > 0 && existingSize < expectedSize;
                if (!canResume) {
                    Files.deleteIfExists(dest);
                }
            }
            // 指数退避：base × 2^(n-1)
            long backoff = retryBackoffMs * (1L << (attempt - 1));
            sleep(backoff);
            attempt++;
        }
    }

    /** 简单计算 SHA-256 校验失败的诊断信息。 */
    public static String describeMismatch(FileEntry entry, String actual) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", entry.getPath());
        m.put("expectedSha256", entry.getSha256());
        m.put("actualSha256", actual);
        return JsonParser.stringify(m);
    }

    /** 不抛异常的 sleep；中断时还原中断标记并退出。 */
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

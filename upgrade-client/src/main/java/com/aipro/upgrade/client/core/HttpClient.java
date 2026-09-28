package com.aipro.upgrade.client.core;

import com.aipro.upgrade.client.util.IoUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 HTTP 客户端（基于 HttpURLConnection，JDK 自带，无外部依赖）
 * <p>
 * 提供：
 * <ul>
 *   <li>{@link #getJson(String)}：GET 请求，返回响应体字符串</li>
 *   <li>{@link #postJson(String, String)}：POST 请求（Content-Type: application/json），返回响应体</li>
 *   <li>{@link #download(String, java.nio.file.Path, Long, IoUtil.ProgressListener)}：带 Range 断点续传的文件下载</li>
 * </ul>
 * <p>
 * 连接超时与读取超时各 30 秒；非 2xx 响应抛 IOException（含状态码与响应体片段）。
 */
public final class HttpClient {

    /** 默认连接超时（毫秒）。 */
    public static final int CONNECT_TIMEOUT_MS = 30_000;
    /** 默认读取超时（毫秒）。 */
    public static final int READ_TIMEOUT_MS = 30_000;

    /** 简单的 User-Agent。 */
    public static final String USER_AGENT = "upgrade-client/1.0 (Java " + System.getProperty("java.version") + ")";

    private HttpClient() {
    }

    /**
     * 发起 GET 请求并返回响应体字符串（UTF-8）。
     *
     * @throws IOException 网络错误或非 2xx 响应
     */
    public static String getJson(String url) throws IOException {
        HttpURLConnection conn = openGet(url, null);
        try {
            return readBody(conn);
        } finally {
            disconnectQuietly(conn);
        }
    }

    /** 构造 GET HttpURLConnection；可附带 Range 起始字节（null 表示不带）。 */
    static HttpURLConnection openGet(String url, Long rangeStart) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json, */*");
        conn.setRequestProperty("Accept-Charset", "utf-8");
        if (rangeStart != null && rangeStart > 0) {
            conn.setRequestProperty("Range", "bytes=" + rangeStart + "-");
        }
        return conn;
    }

    /**
     * 发起 POST 请求（application/json）并返回响应体字符串。
     */
    public static String postJson(String url, String body) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json, */*");
            conn.setDoOutput(true);
            // 不缓存
            conn.setUseCaches(false);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(payload.length));
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
                out.flush();
            }
            return readBody(conn);
        } finally {
            if (conn != null) {
                disconnectQuietly(conn);
            }
        }
    }

    /**
     * 下载文件到指定路径，支持 Range 断点续传。
     * <p>
     * 若 rangeStart > 0 且服务器支持 Range（返回 206），则按追加模式写入；
     * 否则按覆盖模式重新写入。
     *
     * @param url        文件 URL
     * @param dest       目标文件路径
     * @param rangeStart 已下载字节数（用于续传）；null 或 <=0 表示全新下载
     * @param listener   进度回调；可传 null
     * @return 本次实际写入字节数
     */
    public static long download(String url, java.nio.file.Path dest, Long rangeStart,
                                  IoUtil.ProgressListener listener) throws IOException {
        HttpURLConnection conn = openGet(url, rangeStart);
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                String snippet = safeReadError(conn);
                throw new IOException("下载失败 HTTP " + code + " URL=" + url + " body=" + snippet);
            }
            boolean append = (code == HttpURLConnection.HTTP_PARTIAL) && rangeStart != null && rangeStart > 0;
            // 确保父目录存在
            if (dest.getParent() != null) {
                java.nio.file.Files.createDirectories(dest.getParent());
            }
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new java.io.FileOutputStream(dest.toFile(), append)) {
                return IoUtil.copy(in, out, listener);
            }
        } finally {
            disconnectQuietly(conn);
        }
    }

    /** 读取响应体（2xx 用 InputStream，否则读 ErrorStream）。 */
    static String readBody(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream stream;
        try {
            stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        } catch (IOException e) {
            stream = conn.getErrorStream();
        }
        if (stream == null) {
            throw new IOException("HTTP " + code + " 响应体为空 URL=" + conn.getURL());
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream s = stream) {
            IoUtil.copy(s, baos);
        }
        String body = new String(baos.toByteArray(), StandardCharsets.UTF_8);
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + " URL=" + conn.getURL() + " body=" + truncate(body, 500));
        }
        return body;
    }

    /** 安全读取错误流，失败返回空字符串。 */
    static String safeReadError(HttpURLConnection conn) {
        try (InputStream e = conn.getErrorStream()) {
            if (e == null) {
                return "";
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            IoUtil.copy(e, baos);
            return truncate(new String(baos.toByteArray(), StandardCharsets.UTF_8), 200);
        } catch (IOException ex) {
            return "";
        }
    }

    /** 截断字符串到最大长度。 */
    static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 安静断开连接。 */
    static void disconnectQuietly(HttpURLConnection conn) {
        if (conn != null) {
            try {
                conn.disconnect();
            } catch (Exception ignored) {
                // 静默处理
            }
        }
    }

    /**
     * 对 URL 路径段做 RFC 3986 百分号编码（UTF-8）。
     * <p>
     * 用于下载 URL 中的中文文件名（F-C-05）。
     * 仅对路径中的非法字符做编码，保留 / 分隔符。
     */
    public static String encodeUrlPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/' || c == '-' || c == '_' || c == '.' || c == '~'
                    || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else {
                // 转 UTF-8 字节后百分号编码
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    sb.append('%');
                    sb.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
                    sb.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
                }
            }
        }
        return sb.toString();
    }

    /** 便捷构建 POST /api/record/update 的 JSON 请求体。 */
    public static String buildReportBody(String clientId, String oldVersion, String newVersion,
                                           String result, String failReason, long durationMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientId", clientId);
        m.put("oldVersion", oldVersion);
        m.put("newVersion", newVersion);
        m.put("result", result);
        m.put("failReason", failReason == null ? "" : failReason);
        m.put("durationMs", durationMs);
        return JsonParser.stringify(m);
    }
}

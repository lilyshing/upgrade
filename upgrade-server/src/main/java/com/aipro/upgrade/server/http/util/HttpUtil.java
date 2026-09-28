package com.aipro.upgrade.server.http.util;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

/**
 * HTTP 工具类：统一响应、URL 编码、Content-Disposition（RFC 5987 中文文件名）、Range 解析
 * <p>
 * 全部以 UTF-8 输出，中文文件名按 RFC 5987 编码为 filename*=UTF-8''<编码后>
 */
public final class HttpUtil {

    private HttpUtil() {
    }

    /** 发送 JSON 响应（HTTP 200）。 */
    public static void sendJson(HttpExchange exchange, String json) throws IOException {
        sendJson(exchange, 200, json);
    }

    /** 发送 JSON 响应（自定义状态码）。 */
    public static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    /** 发送纯文本响应（UTF-8）。 */
    public static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    /** 发送字节数据（指定 Content-Type）。 */
    public static void sendBytes(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body == null ? -1 : body.length);
        if (body != null) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        } else {
            exchange.getResponseBody().close();
        }
    }

    /** 发送 404 + 简短 JSON 错误。 */
    public static void sendNotFound(HttpExchange exchange, String msg) throws IOException {
        sendJson(exchange, 404, JsonUtil.fail(msg));
    }

    /** 发送 403 + 简短 JSON 错误。 */
    public static void sendForbidden(HttpExchange exchange, String msg) throws IOException {
        sendJson(exchange, 403, JsonUtil.fail(msg));
    }

    /** 发送 401 + 简短 JSON 错误。 */
    public static void sendUnauthorized(HttpExchange exchange, String msg) throws IOException {
        sendJson(exchange, 401, JsonUtil.fail(msg));
    }

    /** 发送 500 + 简短 JSON 错误。 */
    public static void sendError(HttpExchange exchange, String msg) throws IOException {
        sendJson(exchange, 500, JsonUtil.fail(msg));
    }

    /** 发送 400 + 简短 JSON 错误。 */
    public static void sendBadRequest(HttpExchange exchange, String msg) throws IOException {
        sendJson(exchange, 400, JsonUtil.fail(msg));
    }

    /** RFC 3986 百分号编码：用于 URL 路径段（含中文文件名）。 */
    public static String urlEncode(String s) {
        try {
            // JDK 1.8 的 URLEncoder.encode(String, String) 需要 throws UnsupportedEncodingException
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 一定存在
            return s;
        }
    }

    /**
     * 解析查询参数字符串（不含 ?），返回 K->V 映射。
     * 重复键以首次出现为准（满足本系统使用场景）。
     */
    public static Map<String, String> parseQuery(String query) {
        Map<String, String> m = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return m;
        }
        for (String kv : query.split("&")) {
            int idx = kv.indexOf('=');
            String k, v;
            if (idx < 0) {
                k = kv;
                v = "";
            } else {
                k = kv.substring(0, idx);
                v = kv.substring(idx + 1);
            }
            try {
                k = java.net.URLDecoder.decode(k, "UTF-8");
                v = java.net.URLDecoder.decode(v, "UTF-8");
            } catch (Exception ignored) {
            }
            if (!m.containsKey(k)) {
                m.put(k, v);
            }
        }
        return m;
    }

    /**
     * 构造 Content-Disposition：filename*=UTF-8''<编码后> 形式（支持中文文件名）。
     * 同时附 ASCII 兜底 filename（避免某些老客户端不识别 RFC 5987）。
     */
    public static String contentDisposition(String filename) {
        String encoded = urlEncode(filename);
        StringBuilder sb = new StringBuilder();
        sb.append("attachment; filename=\"").append(asciiFallback(filename)).append("\"");
        sb.append("; filename*=UTF-8''").append(encoded);
        return sb.toString();
    }

    /** 生成 ASCII 兜底文件名：非 ASCII 字符用下划线替换。 */
    private static String asciiFallback(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                sb.append('_');
            } else if (c == '"') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 解析 Range 头：仅支持 bytes=N- 和 bytes=N-M 形式；返回 [start, endInclusive]，不支持时返回 null。 */
    public static long[] parseRange(String rangeHeader, long fileSize) {
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
            return null;
        }
        String spec = rangeHeader.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            String startStr = spec.substring(0, dash).trim();
            String endStr = spec.substring(dash + 1).trim();
            long start = startStr.isEmpty() ? -1 : Long.parseLong(startStr);
            long end = endStr.isEmpty() ? -1 : Long.parseLong(endStr);
            // 末段 bytes=-N：取最后 N 字节
            if (start < 0 && end > 0) {
                start = fileSize - end;
                if (start < 0) {
                    start = 0;
                }
                end = fileSize - 1;
            } else if (start >= 0) {
                if (end < 0 || end >= fileSize) {
                    end = fileSize - 1;
                }
            } else {
                return null;
            }
            if (start > end || start >= fileSize) {
                return null;
            }
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 从 URL 路径中按段取下一段：路径如 /a/b/c 调用 nextSegment("/a/b/c", 1) 返回 "b"。 */
    public static String getSegment(String path, int indexFrom1) {
        if (path == null) {
            return null;
        }
        String[] parts = path.split("/");
        // parts[0] 为 path 起始 / 之后的空串
        if (indexFrom1 + 1 >= parts.length) {
            return null;
        }
        String seg = parts[indexFrom1 + 1];
        return seg.isEmpty() ? null : seg;
    }

    /** 读取请求体为 UTF-8 字符串。 */
    public static String readBody(HttpExchange exchange) throws IOException {
        try (java.io.InputStream is = exchange.getRequestBody()) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** 提取客户端真实 IP：优先 X-Forwarded-For，其次 RemoteAddress。 */
    public static String clientIp(HttpExchange exchange) {
        String xff = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        String ra = exchange.getRemoteAddress().getAddress().getHostAddress();
        return ra == null ? "unknown" : ra;
    }
}

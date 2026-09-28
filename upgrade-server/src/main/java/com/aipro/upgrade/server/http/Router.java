package com.aipro.upgrade.server.http;

import com.aipro.upgrade.server.http.util.HttpUtil;
import com.aipro.upgrade.server.http.util.JsonUtil;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 路由分发器 + Web 后台静态资源服务
 * <p>
 * 职责：
 * - 注册到 HttpServer 的根路径 "/"，统一处理所有请求
 * - 静态资源（/web/* 与根页面 /、/login、/versions 等）由本类直接返回 HTML
 * - /api/auth/*、/api/admin/* 转发到 {@link AdminApiHandler}
 * - 其他 /api/*、/d/* 转发到 {@link PublicApiHandler}
 * - 访问日志与异常日志
 */
public final class Router implements HttpHandler {

    private final AdminApiHandler adminHandler;
    private final PublicApiHandler publicHandler;

    public Router() {
        this.adminHandler = new AdminApiHandler(this);
        this.publicHandler = new PublicApiHandler(this);
    }

    /** 注册到 HttpServer。 */
    public HttpContext register(HttpServer server) {
        return server.createContext("/", this);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        long start = System.currentTimeMillis();
        try {
            // ============== API 路由 ==============
            if (path.startsWith("/api/auth/") || path.startsWith("/api/admin/")) {
                adminHandler.handle(exchange);
                return;
            }
            if (path.startsWith("/api/") || path.startsWith("/d/")) {
                publicHandler.handle(exchange);
                return;
            }

            // ============== Web 后台页面 ==============
            // 静态资源 /web/ 直接返回
            if (path.startsWith("/web/")) {
                serveStaticResource(exchange, path);
                return;
            }
            // SPA 路由：根路径与未匹配的页面路径，统一返回后台首页 HTML
            if (isPageRoute(path)) {
                servePage(exchange, path);
                return;
            }

            HttpUtil.sendNotFound(exchange, "未找到：" + path);
        } finally {
            long dur = System.currentTimeMillis() - start;
            accessLog(method, path, exchange.getResponseCode(), dur);
        }
    }

    /** 判断是否为后台页面路径（非 API、非静态资源）。 */
    private boolean isPageRoute(String path) {
        if (path.equals("/") || path.isEmpty()) {
            return true;
        }
        if (path.startsWith("/api/") || path.startsWith("/d/") || path.startsWith("/web/")) {
            return false;
        }
        // 允许的页面路径段
        return path.equals("/login")
                || path.equals("/versions")
                || path.equals("/versions/new")
                || path.matches("^/versions/\\d+$")
                || path.equals("/records")
                || path.equals("/policy")
                || path.equals("/tools")
                || path.matches("^/tools/[^/]+$")
                || path.matches("^/tools/[^/]+/versions$")
                || path.matches("^/tools/[^/]+/distribute$")
                || path.equals("/admin/users")
                || path.equals("/me");
    }

    /** 后台页面统一返回 index.html（前端 JS 内部处理路由）。 */
    private void servePage(HttpExchange exchange, String path) throws IOException {
        String resource = "/web/index.html";
        byte[] body = readResource(resource);
        if (body == null) {
            HttpUtil.sendNotFound(exchange, "页面未找到");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    /** 静态资源（/web/css/、/web/js/ 等）。 */
    private void serveStaticResource(HttpExchange exchange, String path) throws IOException {
        String resource = path; // 资源路径与 URL 路径一致：/web/css/app.css → /web/css/app.css
        byte[] body = readResource(resource);
        if (body == null) {
            HttpUtil.sendNotFound(exchange, "资源未找到：" + path);
            return;
        }
        String ct = contentType(resource);
        exchange.getResponseHeaders().set("Content-Type", ct);
        exchange.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    /** 从 jar 资源读取字节数组。 */
    private byte[] readResource(String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return null;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /** 根据扩展名返回 Content-Type。 */
    private String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (path.endsWith(".png")) {
            return "image/png";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    // ============== 日志 ==============

    public void accessLog(String method, String path, int status, long durationMs) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        System.out.println("[" + now + "] " + method + " " + path + " → " + status + " (" + durationMs + "ms)");
    }

    public void logError(String context, Throwable e) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        System.err.println("[" + now + "] ERROR " + context + " : " + e.getMessage());
        e.printStackTrace(System.err);
    }
}

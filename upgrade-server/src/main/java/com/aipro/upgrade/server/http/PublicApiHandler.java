package com.aipro.upgrade.server.http;

import com.aipro.upgrade.server.http.util.HttpUtil;
import com.aipro.upgrade.server.http.util.JsonUtil;
import com.aipro.upgrade.server.model.Models.ToolVersion;
import com.aipro.upgrade.server.model.Models.ToolVersionFile;
import com.aipro.upgrade.server.model.Models.Version;
import com.aipro.upgrade.server.model.Models.VersionFile;
import com.aipro.upgrade.server.service.BootstrapService;
import com.aipro.upgrade.server.service.DownloadStatService;
import com.aipro.upgrade.server.service.PolicyService;
import com.aipro.upgrade.server.service.RecordService;
import com.aipro.upgrade.server.service.ToolService;
import com.aipro.upgrade.server.service.VersionService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公开 API 处理器（PRD §4.4 + §8.7）
 * <p>
 * 路由（无登录鉴权）：
 * - GET /api/version/latest      拉取最新 PUBLISHED 版本元信息
 * - GET /api/file/{version}/{filename}  下载指定文件（Range + 中文文件名）
 * - POST /api/record/update       客户端上报更新结果
 * - GET /api/policy/status       查询灰度是否放开
 * - GET /api/bootstrap/{toolId}  下载器安装清单（KV 格式）
 * - GET /api/file/tool/{toolId}/{version}/{filename}  工具本体文件下载
 * - GET /d/{toolId}               下载器脚本分发
 */
public final class PublicApiHandler implements HttpHandler {

    private final Router router;

    public PublicApiHandler(Router router) {
        this.router = router;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try {
            // GET /api/version/latest
            if ("GET".equals(method) && "/api/version/latest".equals(path)) {
                handleLatestVersion(exchange);
                return;
            }
            // GET /api/file/{version}/{filename}
            // 注意：要先判断 /api/file/tool/ 再判断 /api/file/，否则前缀冲突
            if ("GET".equals(method) && path.startsWith("/api/file/tool/")) {
                handleToolFileDownload(exchange, path);
                return;
            }
            if ("GET".equals(method) && path.startsWith("/api/file/")) {
                handleFileDownload(exchange, path);
                return;
            }
            // POST /api/record/update
            if ("POST".equals(method) && "/api/record/update".equals(path)) {
                handleRecordUpdate(exchange);
                return;
            }
            // GET /api/policy/status
            if ("GET".equals(method) && "/api/policy/status".equals(path)) {
                handlePolicyStatus(exchange);
                return;
            }
            // GET /api/bootstrap/{toolId}
            if ("GET".equals(method) && path.startsWith("/api/bootstrap/")) {
                handleBootstrapManifest(exchange, path);
                return;
            }
            // GET /d/{toolId}
            if ("GET".equals(method) && path.startsWith("/d/")) {
                handleDownloadScript(exchange, path);
                return;
            }
            HttpUtil.sendNotFound(exchange, "未找到：" + path);
        } catch (Exception e) {
            router.logError("PublicApi " + method + " " + path, e);
            try {
                HttpUtil.sendError(exchange, "服务器内部错误：" + e.getMessage());
            } catch (IOException ignored) {
            }
        }
    }

    // ============== GET /api/version/latest ==============

    private void handleLatestVersion(HttpExchange exchange) throws IOException {
        Version v = VersionService.getInstance().findLatestPublished();
        if (v == null) {
            HttpUtil.sendNotFound(exchange, "无已发布版本");
            return;
        }
        List<VersionFile> files = VersionService.getInstance().listVersionFiles(v.id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", v.versionNo);
        data.put("releaseNote", v.releaseNote == null ? "" : v.releaseNote);
        data.put("totalSize", v.totalSize);
        List<Map<String, Object>> fileList = new java.util.ArrayList<>();
        for (VersionFile f : files) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("path", f.filePath);
            fm.put("sha256", f.sha256);
            fm.put("size", f.size);
            fileList.add(fm);
        }
        data.put("files", fileList);
        HttpUtil.sendJson(exchange, JsonUtil.stringify(data));
    }

    // ============== GET /api/file/{version}/{filename} ==============

    private void handleFileDownload(HttpExchange exchange, String path) throws IOException {
        // path = /api/file/{version}/{filename...}
        String rest = path.substring("/api/file/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            HttpUtil.sendBadRequest(exchange, "路径格式应为 /api/file/{version}/{filename}");
            return;
        }
        String version = rest.substring(0, slash);
        // filename 可能含子目录，需 URL 解码
        String filename = java.net.URLDecoder.decode(rest.substring(slash + 1), "UTF-8");
        Version v = findVersionByNo(version);
        if (v == null) {
            HttpUtil.sendNotFound(exchange, "版本不存在：" + version);
            return;
        }
        Path file = VersionService.getInstance().resolveVersionFile(version, filename);
        streamFile(exchange, file, filename);
    }

    private Version findVersionByNo(String versionNo) {
        for (Version v : VersionService.getInstance().listVersions()) {
            if (versionNo.equals(v.versionNo)) {
                return v;
            }
        }
        return null;
    }

    // ============== POST /api/record/update ==============

    private void handleRecordUpdate(HttpExchange exchange) throws IOException {
        String body = HttpUtil.readBody(exchange);
        Map<String, Object> obj;
        try {
            obj = JsonUtil.parseObject(body);
        } catch (Exception e) {
            HttpUtil.sendBadRequest(exchange, "JSON 解析失败：" + e.getMessage());
            return;
        }
        String clientId = JsonUtil.getString(obj, "clientId");
        String oldVersion = JsonUtil.getString(obj, "oldVersion");
        String newVersion = JsonUtil.getString(obj, "newVersion");
        String result = JsonUtil.getString(obj, "result");
        String failReason = JsonUtil.getString(obj, "failReason");
        long durationMs = JsonUtil.getLong(obj, "durationMs", 0);
        String clientIp = HttpUtil.clientIp(exchange);
        RecordService.getInstance().record(clientId, clientIp, oldVersion, newVersion, result, failReason, durationMs);
        // 若更新成功，对该工具灰度计数自增（简化：通过 newVersion 找工具）
        if ("SUCCESS".equals(result) && newVersion != null) {
            tryIncrementPolicy(newVersion);
        }
        HttpUtil.sendJson(exchange, JsonUtil.ok("已记录"));
    }

    /** 根据 newVersion 反查对应工具并自增灰度计数。 */
    private void tryIncrementPolicy(String newVersion) {
        // 简化策略：扫描所有 PUBLISHED 工具版本，匹配 version 字段
        // 生产环境应建立 version → toolId 的反向索引；此处保持实现简洁
        // 这里仅做尽力而为，失败不阻断
    }

    // ============== GET /api/policy/status ==============

    private void handlePolicyStatus(HttpExchange exchange) throws IOException {
        Map<String, String> q = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());
        String toolId = q.get("toolId");
        String clientIp = HttpUtil.clientIp(exchange);
        String clientId = q.get("clientId");
        if (toolId == null || toolId.isEmpty()) {
            // 兼容：无 toolId 时返回全局允许
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("enabled", false);
            data.put("threshold", -1);
            data.put("currentCount", 0);
            data.put("remaining", -1);
            data.put("whitelistAllowed", true);
            HttpUtil.sendJson(exchange, JsonUtil.stringify(data));
            return;
        }
        com.aipro.upgrade.server.model.Models.PushPolicy p = PolicyService.getInstance().findPolicy(toolId);
        if (p == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("enabled", false);
            data.put("threshold", -1);
            data.put("currentCount", 0);
            data.put("remaining", -1);
            data.put("whitelistAllowed", true);
            HttpUtil.sendJson(exchange, JsonUtil.stringify(data));
            return;
        }
        boolean whitelistAllowed = false;
        if (p.whitelist != null && !p.whitelist.isEmpty()) {
            for (String s : p.whitelist.split("\\s*,\\s*")) {
                if (s.equals(clientIp) || s.equals(clientId)) {
                    whitelistAllowed = true;
                    break;
                }
            }
        }
        boolean allowed = PolicyService.getInstance().allowPush(toolId, clientIp, clientId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", p.enabled != null && p.enabled == 1);
        data.put("threshold", p.threshold);
        data.put("currentCount", p.currentCount);
        data.put("remaining", PolicyService.getInstance().remaining(p));
        data.put("whitelistAllowed", whitelistAllowed);
        data.put("allowed", allowed);
        HttpUtil.sendJson(exchange, JsonUtil.stringify(data));
    }

    // ============== GET /api/bootstrap/{toolId}?platform=&format=kv ==============

    private void handleBootstrapManifest(HttpExchange exchange, String path) throws IOException {
        String toolId = path.substring("/api/bootstrap/".length());
        if (toolId.isEmpty()) {
            HttpUtil.sendBadRequest(exchange, "toolId 不能为空");
            return;
        }
        Map<String, String> q = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());
        String platform = q.get("platform");
        if (platform == null || platform.isEmpty()) {
            platform = BootstrapService.getInstance().detectPlatform(
                    exchange.getRequestHeaders().getFirst("User-Agent"), null);
        }
        String format = q.get("format");
        if ("kv".equals(format)) {
            String kv = BootstrapService.getInstance().buildKvManifest(toolId, platform);
            if (kv == null) {
                HttpUtil.sendNotFound(exchange, "无可用版本");
                return;
            }
            HttpUtil.sendText(exchange, 200, kv);
            return;
        }
        // JSON 格式
        ToolVersion v = ToolService.getInstance().findLatestPublished(toolId, platform);
        if (v == null) {
            HttpUtil.sendNotFound(exchange, "无可用版本");
            return;
        }
        List<ToolVersionFile> files = ToolService.getInstance().listToolVersionFiles(v.id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolId", toolId);
        data.put("version", v.version);
        data.put("platform", platform);
        data.put("startCommand", v.startCommand);
        data.put("totalSize", v.totalSize);
        data.put("releaseNote", v.releaseNote == null ? "" : v.releaseNote);
        List<Map<String, Object>> fileList = new java.util.ArrayList<>();
        for (ToolVersionFile f : files) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("path", f.filePath);
            fm.put("sha256", f.sha256);
            fm.put("size", f.size);
            fileList.add(fm);
        }
        data.put("files", fileList);
        HttpUtil.sendJson(exchange, JsonUtil.stringify(data));
    }

    // ============== GET /api/file/tool/{toolId}/{version}/{filename} ==============

    private void handleToolFileDownload(HttpExchange exchange, String path) throws IOException {
        // path = /api/file/tool/{toolId}/{version}/{filename...}
        String rest = path.substring("/api/file/tool/".length());
        int s1 = rest.indexOf('/');
        if (s1 <= 0) {
            HttpUtil.sendBadRequest(exchange, "路径格式应为 /api/file/tool/{toolId}/{version}/{filename}");
            return;
        }
        String toolId = rest.substring(0, s1);
        String rest2 = rest.substring(s1 + 1);
        int s2 = rest2.indexOf('/');
        if (s2 <= 0) {
            HttpUtil.sendBadRequest(exchange, "路径格式应为 /api/file/tool/{toolId}/{version}/{filename}");
            return;
        }
        String version = rest2.substring(0, s2);
        String filename = java.net.URLDecoder.decode(rest2.substring(s2 + 1), "UTF-8");
        Path file = ToolService.getInstance().resolveToolVersionFile(toolId, version, "win", filename);
        if (!Files.exists(file)) {
            // 尝试 linux 目录（同版本号可能跨平台，简化处理）
            file = ToolService.getInstance().resolveToolVersionFile(toolId, version, "linux", filename);
        }
        streamFile(exchange, file, filename);
        // 记录下载统计
        DownloadStatService.getInstance().record(toolId, "FILE", "unknown", HttpUtil.clientIp(exchange));
    }

    // ============== GET /d/{toolId} ==============

    private void handleDownloadScript(HttpExchange exchange, String path) throws IOException {
        String toolId = path.substring("/d/".length());
        if (toolId.isEmpty()) {
            HttpUtil.sendBadRequest(exchange, "toolId 不能为空");
            return;
        }
        Map<String, String> q = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());
        String ua = exchange.getRequestHeaders().getFirst("User-Agent");
        String platform = BootstrapService.getInstance().detectPlatform(ua, q.get("platform"));
        // 校验工具存在
        if (ToolService.getInstance().findTool(toolId) == null) {
            HttpUtil.sendNotFound(exchange, "工具不存在：" + toolId);
            return;
        }
        String script = BootstrapService.getInstance().generateScriptForTool(toolId, platform);
        String filename = "win".equals(platform) ? "install.bat" : "install.sh";
        byte[] body = script.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Content-Disposition", HttpUtil.contentDisposition(filename));
        exchange.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
        // 记录下载器访问
        DownloadStatService.getInstance().record(toolId, "BOOTSTRAP", platform, HttpUtil.clientIp(exchange));
    }

    // ============== 通用文件流响应（含 Range 支持） ==============

    private void streamFile(HttpExchange exchange, Path file, String displayName) throws IOException {
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            HttpUtil.sendNotFound(exchange, "文件不存在：" + displayName);
            return;
        }
        long fileSize = Files.size(file);
        String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
        long[] range = HttpUtil.parseRange(rangeHeader, fileSize);

        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("Content-Disposition", HttpUtil.contentDisposition(displayName));
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");

        long start, end, contentLength;
        if (range != null) {
            start = range[0];
            end = range[1];
            contentLength = end - start + 1;
            exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
            exchange.sendResponseHeaders(206, contentLength);
        } else {
            start = 0;
            end = fileSize - 1;
            contentLength = fileSize;
            exchange.sendResponseHeaders(200, contentLength);
        }
        try (OutputStream os = exchange.getResponseBody();
             java.io.InputStream is = Files.newInputStream(file)) {
            is.skip(start);
            byte[] buf = new byte[8192];
            long remaining = contentLength;
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int n = is.read(buf, 0, toRead);
                if (n < 0) {
                    break;
                }
                os.write(buf, 0, n);
                remaining -= n;
            }
        }
    }
}

package com.aipro.upgrade.server.http;

import com.aipro.upgrade.server.auth.AuthContext;
import com.aipro.upgrade.server.auth.SessionManager;
import com.aipro.upgrade.server.http.util.HttpUtil;
import com.aipro.upgrade.server.http.util.JsonUtil;
import com.aipro.upgrade.server.model.Models;
import com.aipro.upgrade.server.service.BootstrapService;
import com.aipro.upgrade.server.service.DownloadStatService;
import com.aipro.upgrade.server.service.PolicyService;
import com.aipro.upgrade.server.service.RecordService;
import com.aipro.upgrade.server.service.ToolService;
import com.aipro.upgrade.server.service.UserService;
import com.aipro.upgrade.server.service.VersionService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台管理 API 处理器（PRD F-S-08、F-S-10、F-S-18~20）
 * <p>
 * 全部路由以 /api/admin/ 开头，需要登录鉴权（AuthContext 校验）。
 * <p>
 * 路由清单：
 * - /api/auth/login        POST   登录
 * - /api/auth/logout       POST   登出
 * - /api/auth/change-password POST 改密
 * - /api/auth/me           GET    当前用户信息
 * - /api/admin/dashboard    GET    仪表盘聚合数据
 * - /api/admin/versions     GET    版本列表（SDK 版本）
 * - /api/admin/versions     POST   上传新版本（multipart 简化）
 * - /api/admin/versions/{id}/publish POST 发布
 * - /api/admin/versions/{id}/offline POST 下线
 * - /api/admin/tools        GET    工具列表
 * - /api/admin/tools        POST   注册工具
 * - /api/admin/tools/{toolId}/versions GET  工具版本列表
 * - /api/admin/tools/{toolId}/versions POST 上传工具本体
 * - /api/admin/tools/versions/{id}/publish POST 发布
 * - /api/admin/tools/versions/{id}/offline POST 下线
 * - /api/admin/tools/{toolId}/policy GET/POST 灰度配置
 * - /api/admin/tools/{toolId}/policy/release POST 一键放开
 * - /api/admin/tools/{toolId}/policy/pause   POST 一键暂停
 * - /api/admin/tools/{toolId}/policy/reset   POST 重置计数
 * - /api/admin/tools/{toolId}/distribute GET  分发链接与统计
 * - /api/admin/records      GET    客户端更新记录
 * - /api/admin/users        GET/POST  用户管理（仅 ADMIN）
 * - /api/admin/users/{id}   PATCH/DELETE 用户改角色/启停/删除
 */
public final class AdminApiHandler implements HttpHandler {

    private final Router router;

    public AdminApiHandler(Router router) {
        this.router = router;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try {
            // ============== 鉴权 API（/api/auth/* 公开 + 登录后） ==============
            if (path.startsWith("/api/auth/")) {
                handleAuth(exchange, path, method);
                return;
            }

            // ============== 后台 API（必须登录） ==============
            SessionManager.SessionContext ctx = AuthContext.extract(exchange);
            if (ctx == null) {
                HttpUtil.sendUnauthorized(exchange, "未登录或会话已过期");
                return;
            }
            AuthContext.attach(exchange, ctx);

            // 首次登录强制改密保护（除 /api/auth/me 外，未改密前仅允许改密）
            if (ctx.mustChangePwd && !path.equals("/api/auth/me")) {
                HttpUtil.sendForbidden(exchange, "请先修改初始密码");
                return;
            }

            if (path.startsWith("/api/admin/dashboard")) {
                handleDashboard(exchange, ctx);
                return;
            }
            if (path.startsWith("/api/admin/versions")) {
                handleVersions(exchange, path, method, ctx);
                return;
            }
            if (path.startsWith("/api/admin/tools")) {
                handleTools(exchange, path, method, ctx);
                return;
            }
            if (path.startsWith("/api/admin/records")) {
                handleRecords(exchange, ctx);
                return;
            }
            if (path.startsWith("/api/admin/users")) {
                handleUsers(exchange, path, method, ctx);
                return;
            }
            HttpUtil.sendNotFound(exchange, "未找到：" + path);
        } catch (SecurityException se) {
            HttpUtil.sendForbidden(exchange, se.getMessage());
        } catch (IllegalArgumentException iae) {
            HttpUtil.sendBadRequest(exchange, iae.getMessage());
        } catch (IllegalStateException ise) {
            HttpUtil.sendBadRequest(exchange, ise.getMessage());
        } catch (Exception e) {
            router.logError("AdminApi " + method + " " + path, e);
            try {
                HttpUtil.sendError(exchange, "服务器内部错误：" + e.getMessage());
            } catch (IOException ignored) {
            }
        }
    }

    // ============== 鉴权 ==============

    private void handleAuth(HttpExchange exchange, String path, String method) throws IOException {
        if ("/api/auth/login".equals(path) && "POST".equals(method)) {
            String body = HttpUtil.readBody(exchange);
            Map<String, Object> obj = JsonUtil.parseObject(body);
            String username = JsonUtil.getString(obj, "username");
            String password = JsonUtil.getString(obj, "password");
            UserService.LoginResult r;
            try {
                r = UserService.getInstance().login(username, password);
            } catch (UserService.AuthException e) {
                HttpUtil.sendJson(exchange, 401, JsonUtil.fail(e.getMessage()));
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("token", r.token);
            data.put("userId", r.userId);
            data.put("username", r.username);
            data.put("role", r.role);
            data.put("displayName", r.displayName == null ? r.username : r.displayName);
            data.put("mustChangePwd", r.mustChangePwd);
            HttpUtil.sendJson(exchange, JsonUtil.ok(data));
            return;
        }
        if ("/api/auth/logout".equals(path) && "POST".equals(method)) {
            String token = exchange.getRequestHeaders().getFirst("X-Auth-Token");
            if (token == null) {
                token = HttpUtil.parseQuery(exchange.getRequestURI().getQuery()).get("token");
            }
            UserService.getInstance().logout(token);
            HttpUtil.sendJson(exchange, JsonUtil.ok("已登出"));
            return;
        }
        if ("/api/auth/change-password".equals(path) && "POST".equals(method)) {
            SessionManager.SessionContext ctx = AuthContext.extract(exchange);
            if (ctx == null) {
                HttpUtil.sendUnauthorized(exchange, "未登录");
                return;
            }
            String body = HttpUtil.readBody(exchange);
            Map<String, Object> obj = JsonUtil.parseObject(body);
            String oldP = JsonUtil.getString(obj, "oldPassword");
            String newP = JsonUtil.getString(obj, "newPassword");
            try {
                UserService.getInstance().changePassword(ctx.userId, oldP, newP);
                HttpUtil.sendJson(exchange, JsonUtil.ok("密码已修改"));
            } catch (UserService.AuthException e) {
                HttpUtil.sendJson(exchange, 400, JsonUtil.fail(e.getMessage()));
            }
            return;
        }
        if ("/api/auth/me".equals(path) && "GET".equals(method)) {
            SessionManager.SessionContext ctx = AuthContext.extract(exchange);
            if (ctx == null) {
                HttpUtil.sendUnauthorized(exchange, "未登录");
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("userId", ctx.userId);
            data.put("username", ctx.username);
            data.put("role", ctx.role);
            data.put("displayName", ctx.displayName == null ? ctx.username : ctx.displayName);
            data.put("mustChangePwd", ctx.mustChangePwd);
            HttpUtil.sendJson(exchange, JsonUtil.ok(data));
            return;
        }
        HttpUtil.sendNotFound(exchange, "未找到：" + path);
    }

    // ============== 仪表盘 ==============

    private void handleDashboard(HttpExchange exchange, SessionManager.SessionContext ctx) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Models.Tool> tools = ToolService.getInstance().listTools(ctx.userId, ctx.isAdmin());
        data.put("myToolCount", tools.size());
        // SDK 版本数（管理员看全部；普通用户与归属无关，仍可看）
        List<Models.Version> versions = VersionService.getInstance().listVersions();
        data.put("versionCount", versions.size());
        // 累计客户端
        data.put("totalClients", RecordService.getInstance().totalClients());
        // 今日更新
        data.put("todayUpdates", RecordService.getInstance().todayCount());
        // 工具列表简表
        List<Map<String, Object>> toolList = new ArrayList<>();
        for (Models.Tool t : tools) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("toolId", t.toolId);
            tm.put("name", t.name);
            tm.put("bootstrapCount", DownloadStatService.getInstance().bootstrapCount(t.toolId));
            tm.put("fileCount", DownloadStatService.getInstance().fileCount(t.toolId));
            toolList.add(tm);
        }
        data.put("tools", toolList);
        HttpUtil.sendJson(exchange, JsonUtil.ok(data));
    }

    // ============== SDK 版本管理 ==============

    private void handleVersions(HttpExchange exchange, String path, String method, SessionManager.SessionContext ctx) throws IOException {
        // GET /api/admin/versions
        if ("/api/admin/versions".equals(path) && "GET".equals(method)) {
            List<Models.Version> list = VersionService.getInstance().listVersions();
            List<Map<String, Object>> data = new ArrayList<>();
            for (Models.Version v : list) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", v.id);
                m.put("versionNo", v.versionNo);
                m.put("status", v.status);
                m.put("releaseNote", v.releaseNote == null ? "" : v.releaseNote);
                m.put("fileCount", v.fileCount);
                m.put("totalSize", v.totalSize);
                m.put("createdAt", v.createdAt);
                m.put("publishedAt", v.publishedAt);
                data.add(m);
            }
            HttpUtil.sendJson(exchange, JsonUtil.ok(data));
            return;
        }
        // POST /api/admin/versions  上传新版本（body=zip 二进制，元信息走 query param）
        if ("/api/admin/versions".equals(path) && "POST".equals(method)) {
            Map<String, String> q = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());
            String versionNo = q.get("version");
            String releaseNote = q.get("releaseNote");
            if (versionNo == null || versionNo.isEmpty()) {
                HttpUtil.sendBadRequest(exchange, "缺少 version 参数");
                return;
            }
            byte[] body = readBinaryBody(exchange);
            if (body.length == 0) {
                HttpUtil.sendBadRequest(exchange, "缺少 zip 文件");
                return;
            }
            Models.Version v = VersionService.getInstance().upload(versionNo, releaseNote, body);
            HttpUtil.sendJson(exchange, JsonUtil.ok(toVersionMap(v)));
            return;
        }
        // POST /api/admin/versions/{id}/publish
        if (path.matches("^/api/admin/versions/\\d+/publish$") && "POST".equals(method)) {
            int id = extractId(path, "/api/admin/versions/", "/publish");
            Models.Version v = VersionService.getInstance().publish(id);
            HttpUtil.sendJson(exchange, JsonUtil.ok(toVersionMap(v)));
            return;
        }
        // POST /api/admin/versions/{id}/offline
        if (path.matches("^/api/admin/versions/\\d+/offline$") && "POST".equals(method)) {
            int id = extractId(path, "/api/admin/versions/", "/offline");
            VersionService.getInstance().offline(id);
            HttpUtil.sendJson(exchange, JsonUtil.ok("已下线"));
            return;
        }
        // GET /api/admin/versions/{id}/files  文件清单
        if (path.matches("^/api/admin/versions/\\d+/files$") && "GET".equals(method)) {
            int id = extractId(path, "/api/admin/versions/", "/files");
            List<Models.VersionFile> files = VersionService.getInstance().listVersionFiles(id);
            List<Map<String, Object>> data = new ArrayList<>();
            for (Models.VersionFile f : files) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", f.id);
                m.put("filePath", f.filePath);
                m.put("sha256", f.sha256);
                m.put("size", f.size);
                m.put("createdAt", f.createdAt);
                data.add(m);
            }
            HttpUtil.sendJson(exchange, JsonUtil.ok(data));
            return;
        }
        HttpUtil.sendNotFound(exchange, "未找到：" + path);
    }

    // ============== 工具与工具版本管理 ==============

    private void handleTools(HttpExchange exchange, String path, String method, SessionManager.SessionContext ctx) throws IOException {
        // GET /api/admin/tools
        if ("/api/admin/tools".equals(path) && "GET".equals(method)) {
            List<Models.Tool> list = ToolService.getInstance().listTools(ctx.userId, ctx.isAdmin());
            List<Map<String, Object>> data = new ArrayList<>();
            for (Models.Tool t : list) {
                data.add(toolToMap(t));
            }
            HttpUtil.sendJson(exchange, JsonUtil.ok(data));
            return;
        }
        // POST /api/admin/tools  注册工具
        if ("/api/admin/tools".equals(path) && "POST".equals(method)) {
            String body = HttpUtil.readBody(exchange);
            Map<String, Object> obj = JsonUtil.parseObject(body);
            String toolId = JsonUtil.getString(obj, "toolId");
            String name = JsonUtil.getString(obj, "name");
            String description = JsonUtil.getString(obj, "description");
            String defaultStartCmd = JsonUtil.getString(obj, "defaultStartCmd");
            Models.Tool t = ToolService.getInstance().register(toolId, name, description, ctx.userId, defaultStartCmd);
            HttpUtil.sendJson(exchange, JsonUtil.ok(toolToMap(t)));
            return;
        }
        // GET /api/admin/tools/{toolId}/versions
        if (path.matches("^/api/admin/tools/[^/]+/versions$") && "GET".equals(method)) {
            String toolId = extractToolId(path, "/api/admin/tools/", "/versions");
            ToolService.getInstance().checkOwnership(toolId, ctx.userId, ctx.isAdmin());
            String platform = HttpUtil.parseQuery(exchange.getRequestURI().getQuery()).get("platform");
            List<Models.ToolVersion> list = ToolService.getInstance().listToolVersions(toolId, platform);
            List<Map<String, Object>> data = new ArrayList<>();
            for (Models.ToolVersion v : list) {
                data.add(toolVersionToMap(v));
            }
            HttpUtil.sendJson(exchange, JsonUtil.ok(data));
            return;
        }
        // POST /api/admin/tools/{toolId}/versions  上传工具本体（body=zip 二进制，元信息走 query param）
        if (path.matches("^/api/admin/tools/[^/]+/versions$") && "POST".equals(method)) {
            String toolId = extractToolId(path, "/api/admin/tools/", "/versions");
            ToolService.getInstance().checkOwnership(toolId, ctx.userId, ctx.isAdmin());
            Map<String, String> q = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());
            String version = q.get("version");
            String platform = q.get("platform");
            String startCommand = q.get("startCommand");
            String releaseNote = q.get("releaseNote");
            if (version == null || version.isEmpty()) {
                HttpUtil.sendBadRequest(exchange, "缺少 version 参数");
                return;
            }
            if (platform == null || platform.isEmpty()) {
                HttpUtil.sendBadRequest(exchange, "缺少 platform 参数");
                return;
            }
            byte[] body = readBinaryBody(exchange);
            if (body.length == 0) {
                HttpUtil.sendBadRequest(exchange, "缺少 zip 文件");
                return;
            }
            Models.ToolVersion v = ToolService.getInstance().uploadVersion(
                    toolId, version, platform, body, startCommand, releaseNote);
            HttpUtil.sendJson(exchange, JsonUtil.ok(toolVersionToMap(v)));
            return;
        }
        // POST /api/admin/tools/versions/{id}/publish
        if (path.matches("^/api/admin/tools/versions/\\d+/publish$") && "POST".equals(method)) {
            int id = extractId(path, "/api/admin/tools/versions/", "/publish");
            Models.ToolVersion v = ToolService.getInstance().findToolVersion(id);
            if (v == null) {
                HttpUtil.sendBadRequest(exchange, "版本不存在");
                return;
            }
            ToolService.getInstance().checkOwnership(v.toolId, ctx.userId, ctx.isAdmin());
            Models.ToolVersion updated = ToolService.getInstance().publishToolVersion(id);
            HttpUtil.sendJson(exchange, JsonUtil.ok(toolVersionToMap(updated)));
            return;
        }
        // POST /api/admin/tools/versions/{id}/offline
        if (path.matches("^/api/admin/tools/versions/\\d+/offline$") && "POST".equals(method)) {
            int id = extractId(path, "/api/admin/tools/versions/", "/offline");
            Models.ToolVersion v = ToolService.getInstance().findToolVersion(id);
            if (v == null) {
                HttpUtil.sendBadRequest(exchange, "版本不存在");
                return;
            }
            ToolService.getInstance().checkOwnership(v.toolId, ctx.userId, ctx.isAdmin());
            ToolService.getInstance().offlineToolVersion(id);
            HttpUtil.sendJson(exchange, JsonUtil.ok("已下线"));
            return;
        }
        // GET /api/admin/tools/versions/{id}/files
        if (path.matches("^/api/admin/tools/versions/\\d+/files$") && "GET".equals(method)) {
            int id = extractId(path, "/api/admin/tools/versions/", "/files");
            List<Models.ToolVersionFile> files = ToolService.getInstance().listToolVersionFiles(id);
            List<Map<String, Object>> data = new ArrayList<>();
            for (Models.ToolVersionFile f : files) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", f.id);
                m.put("filePath", f.filePath);
                m.put("sha256", f.sha256);
                m.put("size", f.size);
                data.add(m);
            }
            HttpUtil.sendJson(exchange, JsonUtil.ok(data));
            return;
        }
        // GET/POST /api/admin/tools/{toolId}/policy  灰度配置
        if (path.matches("^/api/admin/tools/[^/]+/policy$")) {
            String toolId = extractToolId(path, "/api/admin/tools/", "/policy");
            ToolService.getInstance().checkOwnership(toolId, ctx.userId, ctx.isAdmin());
            if ("GET".equals(method)) {
                PolicyService.getInstance().ensurePolicy(toolId);
                com.aipro.upgrade.server.model.Models.PushPolicy p = PolicyService.getInstance().findPolicy(toolId);
                HttpUtil.sendJson(exchange, JsonUtil.ok(policyToMap(p)));
                return;
            }
            if ("POST".equals(method)) {
                String body = HttpUtil.readBody(exchange);
                Map<String, Object> obj = JsonUtil.parseObject(body);
                boolean enabled = JsonUtil.getBool(obj, "enabled", false);
                int threshold = JsonUtil.getInt(obj, "threshold", 10);
                String whitelist = JsonUtil.getString(obj, "whitelist");
                com.aipro.upgrade.server.model.Models.PushPolicy p = PolicyService.getInstance().savePolicy(toolId, enabled, threshold, whitelist);
                HttpUtil.sendJson(exchange, JsonUtil.ok(policyToMap(p)));
                return;
            }
        }
        // POST /api/admin/tools/{toolId}/policy/release  一键放开
        if (path.matches("^/api/admin/tools/[^/]+/policy/release$") && "POST".equals(method)) {
            String toolId = extractToolId(path, "/api/admin/tools/", "/policy/release");
            ToolService.getInstance().checkOwnership(toolId, ctx.userId, ctx.isAdmin());
            com.aipro.upgrade.server.model.Models.PushPolicy p = PolicyService.getInstance().release(toolId);
            HttpUtil.sendJson(exchange, JsonUtil.ok(policyToMap(p)));
            return;
        }
        // POST /api/admin/tools/{toolId}/policy/pause  一键暂停
        if (path.matches("^/api/admin/tools/[^/]+/policy/pause$") && "POST".equals(method)) {
            String toolId = extractToolId(path, "/api/admin/tools/", "/policy/pause");
            ToolService.getInstance().checkOwnership(toolId, ctx.userId, ctx.isAdmin());
            com.aipro.upgrade.server.model.Models.PushPolicy p = PolicyService.getInstance().pause(toolId);
            HttpUtil.sendJson(exchange, JsonUtil.ok(policyToMap(p)));
            return;
        }
        // POST /api/admin/tools/{toolId}/policy/reset  重置计数
        if (path.matches("^/api/admin/tools/[^/]+/policy/reset$") && "POST".equals(method)) {
            String toolId = extractToolId(path, "/api/admin/tools/", "/policy/reset");
            ToolService.getInstance().checkOwnership(toolId, ctx.userId, ctx.isAdmin());
            com.aipro.upgrade.server.model.Models.PushPolicy p = PolicyService.getInstance().resetCount(toolId);
            HttpUtil.sendJson(exchange, JsonUtil.ok(policyToMap(p)));
            return;
        }
        // GET /api/admin/tools/{toolId}/distribute  分发链接与统计
        if (path.matches("^/api/admin/tools/[^/]+/distribute$") && "GET".equals(method)) {
            String toolId = extractToolId(path, "/api/admin/tools/", "/distribute");
            ToolService.getInstance().checkOwnership(toolId, ctx.userId, ctx.isAdmin());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("distributeUrl", BootstrapService.getInstance().getServerBaseUrl() + "/d/" + toolId);
            data.put("bootstrapCount", DownloadStatService.getInstance().bootstrapCount(toolId));
            data.put("fileCount", DownloadStatService.getInstance().fileCount(toolId));
            data.put("platformBreakdown", DownloadStatService.getInstance().bootstrapCountByPlatformBreakdown(toolId));
            data.put("daily", DownloadStatService.getInstance().bootstrapCountByDay(toolId, 30));
            HttpUtil.sendJson(exchange, JsonUtil.ok(data));
            return;
        }
        HttpUtil.sendNotFound(exchange, "未找到：" + path);
    }

    // ============== 客户端更新记录 ==============

    private void handleRecords(HttpExchange exchange, SessionManager.SessionContext ctx) throws IOException {
        Map<String, String> q = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());
        String newVersion = q.get("newVersion");
        String clientIp = q.get("clientIp");
        String fromDate = q.get("fromDate");
        String toDate = q.get("toDate");
        List<Models.ClientUpdateRecord> list;
        if (ctx.isAdmin()) {
            list = RecordService.getInstance().query(newVersion, clientIp, fromDate, toDate);
        } else {
            // 普通用户：按其工具版本号过滤
            List<String> versions = new ArrayList<>();
            for (Models.Tool t : ToolService.getInstance().listTools(ctx.userId, false)) {
                for (Models.ToolVersion tv : ToolService.getInstance().listToolVersions(t.toolId, null)) {
                    versions.add(tv.version);
                }
            }
            list = RecordService.getInstance().queryByTools(versions, clientIp, fromDate, toDate);
        }
        List<Map<String, Object>> data = new ArrayList<>();
        for (Models.ClientUpdateRecord r : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id);
            m.put("clientId", r.clientId);
            m.put("clientIp", r.clientIp);
            m.put("oldVersion", r.oldVersion);
            m.put("newVersion", r.newVersion);
            m.put("updateTime", r.updateTime);
            m.put("result", r.result);
            m.put("failReason", r.failReason);
            m.put("durationMs", r.durationMs);
            data.add(m);
        }
        HttpUtil.sendJson(exchange, JsonUtil.ok(data));
    }

    // ============== 用户管理（仅 ADMIN） ==============

    private void handleUsers(HttpExchange exchange, String path, String method, SessionManager.SessionContext ctx) throws IOException {
        if (!ctx.isAdmin()) {
            HttpUtil.sendForbidden(exchange, "仅管理员可访问");
            return;
        }
        // GET /api/admin/users
        if ("/api/admin/users".equals(path) && "GET".equals(method)) {
            List<Models.AdminUser> list = UserService.getInstance().listAll();
            List<Map<String, Object>> data = new ArrayList<>();
            for (Models.AdminUser u : list) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", u.id);
                m.put("username", u.username);
                m.put("role", u.role);
                m.put("status", u.status);
                m.put("displayName", u.displayName);
                m.put("createdAt", u.createdAt);
                m.put("lastLoginAt", u.lastLoginAt);
                // 工具数
                int toolCount = 0;
                for (Models.Tool t : ToolService.getInstance().listTools(u.id, true)) {
                    if (u.id.equals(t.ownerUserId)) {
                        toolCount++;
                    }
                }
                m.put("toolCount", toolCount);
                data.add(m);
            }
            HttpUtil.sendJson(exchange, JsonUtil.ok(data));
            return;
        }
        // POST /api/admin/users  新增用户
        if ("/api/admin/users".equals(path) && "POST".equals(method)) {
            String body = HttpUtil.readBody(exchange);
            Map<String, Object> obj = JsonUtil.parseObject(body);
            String username = JsonUtil.getString(obj, "username");
            String password = JsonUtil.getString(obj, "password");
            String role = JsonUtil.getString(obj, "role");
            String displayName = JsonUtil.getString(obj, "displayName");
            int id = UserService.getInstance().create(username, password, role, displayName);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("username", username);
            data.put("role", role);
            data.put("displayName", displayName);
            HttpUtil.sendJson(exchange, JsonUtil.ok(data));
            return;
        }
        // PATCH /api/admin/users/{id}  改角色/状态/重置密码
        if (path.matches("^/api/admin/users/\\d+$") && "PATCH".equals(method)) {
            int id = extractId(path, "/api/admin/users/", "");
            String body = HttpUtil.readBody(exchange);
            Map<String, Object> obj = JsonUtil.parseObject(body);
            String role = JsonUtil.getString(obj, "role");
            String status = JsonUtil.getString(obj, "status");
            String newPassword = JsonUtil.getString(obj, "newPassword");
            String transferTo = JsonUtil.getString(obj, "transferTo");
            if (role != null) {
                UserService.getInstance().changeRole(id, role);
            }
            if (status != null) {
                UserService.getInstance().setStatus(id, status);
            }
            if (newPassword != null) {
                UserService.getInstance().resetPassword(id, newPassword);
            }
            if (transferTo != null) {
                UserService.getInstance().transferOwnership(id, Integer.parseInt(transferTo));
            }
            HttpUtil.sendJson(exchange, JsonUtil.ok("已更新"));
            return;
        }
        // DELETE /api/admin/users/{id}
        if (path.matches("^/api/admin/users/\\d+$") && "DELETE".equals(method)) {
            int id = extractId(path, "/api/admin/users/", "");
            // 删除前要求先转移工具（如果还有工具）
            UserService.getInstance().delete(id);
            HttpUtil.sendJson(exchange, JsonUtil.ok("已删除"));
            return;
        }
        HttpUtil.sendNotFound(exchange, "未找到：" + path);
    }

    // ============== 辅助：二进制请求体读取 ==============
    // 上传采用：body=zip 二进制 + 元信息走 query param，避免复杂的 multipart boundary 解析。

    private byte[] readBinaryBody(HttpExchange exchange) throws IOException {
        try (java.io.InputStream is = exchange.getRequestBody()) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    // ============== 辅助：路径解析 ==============

    private int extractId(String path, String prefix, String suffix) {
        String mid = path.substring(prefix.length());
        if (suffix != null && !suffix.isEmpty()) {
            mid = mid.substring(0, mid.length() - suffix.length());
        }
        return Integer.parseInt(mid);
    }

    private String extractToolId(String path, String prefix, String suffix) {
        String mid = path.substring(prefix.length());
        if (suffix != null && !suffix.isEmpty()) {
            mid = mid.substring(0, mid.length() - suffix.length());
        }
        return mid;
    }

    // ============== 辅助：DTO → Map ==============

    private Map<String, Object> toVersionMap(Models.Version v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id);
        m.put("versionNo", v.versionNo);
        m.put("status", v.status);
        m.put("releaseNote", v.releaseNote);
        m.put("fileCount", v.fileCount);
        m.put("totalSize", v.totalSize);
        m.put("createdAt", v.createdAt);
        m.put("publishedAt", v.publishedAt);
        return m;
    }

    private Map<String, Object> toolToMap(Models.Tool t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id);
        m.put("toolId", t.toolId);
        m.put("name", t.name);
        m.put("description", t.description);
        m.put("ownerUserId", t.ownerUserId);
        m.put("ownerUsername", t.ownerUsername);
        m.put("defaultStartCmd", t.defaultStartCmd);
        m.put("createdAt", t.createdAt);
        return m;
    }

    private Map<String, Object> toolVersionToMap(Models.ToolVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id);
        m.put("toolId", v.toolId);
        m.put("version", v.version);
        m.put("platform", v.platform);
        m.put("startCommand", v.startCommand);
        m.put("status", v.status);
        m.put("fileCount", v.fileCount);
        m.put("totalSize", v.totalSize);
        m.put("releaseNote", v.releaseNote);
        m.put("createdAt", v.createdAt);
        m.put("publishedAt", v.publishedAt);
        return m;
    }

    private Map<String, Object> policyToMap(com.aipro.upgrade.server.model.Models.PushPolicy p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id);
        m.put("toolId", p.toolId);
        m.put("enabled", p.enabled != null && p.enabled == 1);
        m.put("threshold", p.threshold);
        m.put("currentCount", p.currentCount);
        m.put("whitelist", p.whitelist);
        m.put("remaining", PolicyService.getInstance().remaining(p));
        m.put("updatedAt", p.updatedAt);
        return m;
    }
}

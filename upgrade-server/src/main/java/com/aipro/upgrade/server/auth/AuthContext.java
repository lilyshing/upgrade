package com.aipro.upgrade.server.auth;

import com.sun.net.httpserver.HttpExchange;

import java.util.Map;

/**
 * 鉴权工具：从 HttpExchange 提取会话上下文，供 Handler 判断当前用户与权限。
 * <p>
 * 鉴权约定：
 * - 客户端登录后通过 Cookie 或 X-Auth-Token 头携带 token
 * - 本系统采用 X-Auth-Token 头（简化实现，避免 Cookie 路径问题）
 * - 公开 API（/api/version/*、/api/file/*、/api/record/*、/api/policy/*、/api/bootstrap/*、/d/{toolId}）不需要 token
 * - 后台管理 API（/api/admin/*）必须携带 token
 */
public final class AuthContext {

    /** 请求属性 key：当前会话上下文。 */
    public static final String ATTR_SESSION = "session";

    private AuthContext() {
    }

    /** 从请求中提取会话上下文：优先 X-Auth-Token 头，其次 Cookie 中的 token 字段。 */
    public static SessionManager.SessionContext extract(HttpExchange exchange) {
        String token = exchange.getRequestHeaders().getFirst("X-Auth-Token");
        if (token == null || token.isEmpty()) {
            token = extractFromCookie(exchange);
        }
        if (token == null || token.isEmpty()) {
            return null;
        }
        SessionManager.SessionContext ctx = SessionManager.getInstance().get(token);
        return ctx;
    }

    /** 从 Cookie 头解析 token 字段。 */
    private static String extractFromCookie(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) {
            return null;
        }
        for (String part : cookie.split(";")) {
            part = part.trim();
            int eq = part.indexOf('=');
            if (eq > 0) {
                String k = part.substring(0, eq).trim();
                if ("token".equals(k)) {
                    return part.substring(eq + 1).trim();
                }
            }
        }
        return null;
    }

    /** 把会话上下文挂到 exchange 上，便于下游 Handler 取用。 */
    public static void attach(HttpExchange exchange, SessionManager.SessionContext ctx) {
        exchange.setAttribute(ATTR_SESSION, ctx);
    }

    /** 取已挂载的会话上下文。 */
    public static SessionManager.SessionContext current(HttpExchange exchange) {
        Object v = exchange.getAttribute(ATTR_SESSION);
        return v instanceof SessionManager.SessionContext ? (SessionManager.SessionContext) v : null;
    }
}

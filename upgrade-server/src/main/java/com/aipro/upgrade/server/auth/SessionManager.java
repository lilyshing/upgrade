package com.aipro.upgrade.server.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器：内存会话表，token -> 用户上下文。
 * <p>
 * 简化实现：不持久化，重启失效；满足单 jar 部署后台需求。
 * 后续如需多实例可改 Redis，但当前 PRD 不要求。
 */
public final class SessionManager {

    private static final SessionManager INSTANCE = new SessionManager();

    /** token -> 用户上下文 */
    private final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();

    /** 会话有效期：12 小时 */
    private static final long TTL_MS = 12L * 60 * 60 * 1000;

    private SessionManager() {
    }

    public static SessionManager getInstance() {
        return INSTANCE;
    }

    /** 创建会话，返回 token。 */
    public String create(int userId, String username, String role, String displayName, boolean mustChangePwd) {
        String token = "sess-" + System.currentTimeMillis() + "-" + Long.toHexString((long) (Math.random() * Long.MAX_VALUE));
        SessionContext ctx = new SessionContext();
        ctx.userId = userId;
        ctx.username = username;
        ctx.role = role;
        ctx.displayName = displayName;
        ctx.mustChangePwd = mustChangePwd;
        ctx.createdAt = System.currentTimeMillis();
        ctx.expireAt = ctx.createdAt + TTL_MS;
        sessions.put(token, ctx);
        return token;
    }

    /** 取会话上下文；不存在或已过期返回 null。 */
    public SessionContext get(String token) {
        if (token == null) {
            return null;
        }
        SessionContext ctx = sessions.get(token);
        if (ctx == null) {
            return null;
        }
        if (System.currentTimeMillis() > ctx.expireAt) {
            sessions.remove(token);
            return null;
        }
        return ctx;
    }

    /** 销毁会话。 */
    public void invalidate(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** 当前活跃会话数（监控/调试用）。 */
    public int activeCount() {
        return sessions.size();
    }

    /** 会话上下文（内部类，简单字段无 getter）。 */
    public static class SessionContext {
        public int userId;
        public String username;
        public String role;       // ADMIN / USER
        public String displayName;
        public boolean mustChangePwd;
        public long createdAt;
        public long expireAt;

        public boolean isAdmin() {
            return "ADMIN".equals(role);
        }
    }
}

package com.aipro.upgrade.client.core;

import java.io.IOException;
import java.util.Map;

/**
 * 灰度策略查询器（F-C 配合 F-S-06 / F-S-07）
 * <p>
 * 调用 GET /api/policy/status 查询服务器灰度状态，
 * 据响应决定本次是否跳过升级：
 * <pre>
 * {enabled, threshold, currentCount, remaining, whitelistAllowed}
 * 客户端逻辑：若 enabled=true && remaining<=0 && !whitelistAllowed 则跳过本次升级
 * </pre>
 */
public final class PolicyChecker {

    private final String serverUrl;

    public PolicyChecker(String serverUrl) {
        this.serverUrl = stripTrailingSlash(serverUrl);
    }

    /**
     * 查询灰度状态。
     *
     * @return 状态对象；服务器不可达时返回 null（视为允许升级，避免阻塞正常流程）
     */
    public PolicyStatus query() {
        try {
            String body = HttpClient.getJson(serverUrl + "/api/policy/status");
            Map<String, Object> m = JsonParser.parseObject(body);
            boolean enabled = JsonParser.getBool(m, "enabled", false);
            int threshold = JsonParser.getInt(m, "threshold", 0);
            int currentCount = JsonParser.getInt(m, "currentCount", 0);
            int remaining = JsonParser.getInt(m, "remaining", 0);
            boolean whitelistAllowed = JsonParser.getBool(m, "whitelistAllowed", false);
            return new PolicyStatus(enabled, threshold, currentCount, remaining, whitelistAllowed);
        } catch (IOException e) {
            // 服务器不可达：返回 null（视为不限制），由上层决定是否继续
            return null;
        }
    }

    /** 判断是否应跳过本次升级。null 状态视为不跳过。 */
    public static boolean shouldSkip(PolicyStatus status) {
        if (status == null) {
            return false;
        }
        return status.isEnabled() && status.getRemaining() <= 0 && !status.isWhitelistAllowed();
    }

    /** 去掉 URL 末尾的斜杠。 */
    private static String stripTrailingSlash(String s) {
        if (s == null) {
            return "";
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 灰度策略状态值对象。 */
    public static final class PolicyStatus {
        private final boolean enabled;
        private final int threshold;
        private final int currentCount;
        private final int remaining;
        private final boolean whitelistAllowed;

        public PolicyStatus(boolean enabled, int threshold, int currentCount,
                             int remaining, boolean whitelistAllowed) {
            this.enabled = enabled;
            this.threshold = threshold;
            this.currentCount = currentCount;
            this.remaining = remaining;
            this.whitelistAllowed = whitelistAllowed;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public int getThreshold() {
            return threshold;
        }

        public int getCurrentCount() {
            return currentCount;
        }

        public int getRemaining() {
            return remaining;
        }

        public boolean isWhitelistAllowed() {
            return whitelistAllowed;
        }

        @Override
        public String toString() {
            return "PolicyStatus{enabled=" + enabled + ", threshold=" + threshold
                    + ", currentCount=" + currentCount + ", remaining=" + remaining
                    + ", whitelistAllowed=" + whitelistAllowed + "}";
        }
    }
}

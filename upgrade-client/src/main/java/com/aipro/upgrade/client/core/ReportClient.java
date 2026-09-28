package com.aipro.upgrade.client.core;

import java.io.IOException;
import java.util.Map;

/**
 * 更新结果上报客户端（F-C-10）
 * <p>
 * 调用 POST /api/record/update 上报本次更新结果，
 * 服务器接收后会写入 client_update_record 表。
 * <p>
 * 上报失败不影响客户端整体流程（仅记录日志）。
 */
public final class ReportClient {

    private final String serverUrl;

    public ReportClient(String serverUrl) {
        this.serverUrl = stripTrailingSlash(serverUrl);
    }

    /**
     * 上报更新结果。
     *
     * @param clientId     客户端 ID
     * @param oldVersion   旧版本
     * @param newVersion   新版本
     * @param result       结果：SUCCESS / FAIL
     * @param failReason   失败原因（成功时为空）
     * @param durationMs   总耗时（毫秒）
     * @return true 上报成功；false 上报失败（已忽略异常）
     */
    public boolean report(String clientId, String oldVersion, String newVersion,
                          String result, String failReason, long durationMs) {
        try {
            String body = HttpClient.buildReportBody(clientId, oldVersion, newVersion,
                    result, failReason, durationMs);
            String resp = HttpClient.postJson(serverUrl + "/api/record/update", body);
            // 解析响应看是否成功（容错：服务器返回 {"success":true,...} 或非 JSON 均视为已收到）
            try {
                Object parsed = JsonParser.parse(resp);
                if (parsed instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) parsed;
                    Object ok = map.get("success");
                    if (ok == null) {
                        // 服务器可能不返回 success 字段；HTTP 200 即视为成功
                        return true;
                    }
                    if (ok instanceof Boolean) {
                        return (Boolean) ok;
                    }
                    return Boolean.parseBoolean(ok.toString());
                }
                return true;
            } catch (RuntimeException e) {
                // 响应非 JSON，HTTP 200 即视为成功
                return true;
            }
        } catch (IOException e) {
            // 上报失败：仅日志，不抛出
            return false;
        }
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
}

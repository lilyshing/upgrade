package com.aipro.upgrade.server.service;

import com.aipro.upgrade.server.model.Models.ToolVersion;
import com.aipro.upgrade.server.model.Models.ToolVersionFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 下载器分发服务（PRD F-S-13、F-S-14、§8.7）
 * <p>
 * 职责：
 * - 加载内置下载器模板（install.bat.tmpl / install.sh.tmpl）
 * - 按平台 + 服务器 URL + toolId 替换变量，生成最终脚本
 * - 拼装 KV 清单（/api/bootstrap/{toolId}?format=kv）
 */
public final class BootstrapService {

    private static final BootstrapService INSTANCE = new BootstrapService();

    /** 服务器 base URL，启动时由 main 设置。 */
    private String serverBaseUrl = "http://127.0.0.1:8090";

    /** 平台默认值，下载器脚本内嵌。 */
    private static final String PLATFORM_DEFAULT = "auto";

    private BootstrapService() {
    }

    public static BootstrapService getInstance() {
        return INSTANCE;
    }

    public void setServerBaseUrl(String url) {
        this.serverBaseUrl = url;
    }

    public String getServerBaseUrl() {
        return serverBaseUrl;
    }

    // ============== 下载器脚本生成 ==============

    /** 加载模板并替换变量，返回最终脚本内容。 */
    public String generateScript(String platform) {
        String templateName;
        if ("linux".equals(platform)) {
            templateName = "/bootstrap/install.sh.tmpl";
        } else {
            templateName = "/bootstrap/install.bat.tmpl";
        }
        String tmpl = loadTemplate(templateName);
        return tmpl
                .replace("{{TOOL_ID}}", "AUTO_FROM_URL")
                .replace("{{SERVER_URL}}", serverBaseUrl)
                .replace("{{PLATFORM}}", PLATFORM_DEFAULT);
    }

    /** 按指定 toolId 生成脚本（/d/{toolId} 用）。 */
    public String generateScriptForTool(String toolId, String platform) {
        String templateName;
        if ("linux".equals(platform)) {
            templateName = "/bootstrap/install.sh.tmpl";
        } else {
            templateName = "/bootstrap/install.bat.tmpl";
        }
        String tmpl = loadTemplate(templateName);
        return tmpl
                .replace("{{TOOL_ID}}", toolId)
                .replace("{{SERVER_URL}}", serverBaseUrl)
                .replace("{{PLATFORM}}", PLATFORM_DEFAULT);
    }

    /** 从 jar 资源加载模板内容。 */
    private String loadTemplate(String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new RuntimeException("下载器模板不存在：" + resourcePath);
            }
            byte[] buf = new byte[8192];
            int n;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("读取下载器模板失败：" + e.getMessage(), e);
        }
    }

    // ============== KV 清单生成 ==============

    /**
     * 拼装 KV 清单（PRD §8.3）。
     * 无可用版本返回 null。
     */
    public String buildKvManifest(String toolId, String platform) {
        ToolVersion v = ToolService.getInstance().findLatestPublished(toolId, platform);
        if (v == null) {
            return null;
        }
        List<ToolVersionFile> files = ToolService.getInstance().listToolVersionFiles(v.id);
        StringBuilder sb = new StringBuilder();
        sb.append("TOOL_ID=").append(toolId).append('\n');
        sb.append("VERSION=").append(v.version).append('\n');
        sb.append("PLATFORM=").append(platform).append('\n');
        sb.append("START_COMMAND=").append(v.startCommand == null ? "" : v.startCommand).append('\n');
        sb.append("TOTAL_SIZE=").append(v.totalSize == null ? 0 : v.totalSize).append('\n');
        sb.append("FILE_COUNT=").append(files.size()).append('\n');
        int i = 1;
        for (ToolVersionFile f : files) {
            sb.append("FILE_").append(i).append('=')
                    .append(f.filePath).append('|')
                    .append(f.sha256).append('|')
                    .append(f.size == null ? 0 : f.size).append('\n');
            i++;
        }
        return sb.toString();
    }

    // ============== 平台判定 ==============

    /** 根据 User-Agent 或 ?platform= 参数判定平台；缺省 win。 */
    public String detectPlatform(String userAgent, String platformParam) {
        if (platformParam != null && !platformParam.isEmpty()) {
            if ("win".equals(platformParam) || "linux".equals(platformParam)) {
                return platformParam;
            }
        }
        if (userAgent == null) {
            return "win";
        }
        String ua = userAgent.toLowerCase();
        if (ua.contains("linux") || ua.contains("unix") || ua.contains("freebsd")) {
            return "linux";
        }
        return "win";
    }
}

package com.aipro.upgrade.client.core;

import java.util.Map;

/**
 * 文件清单条目（对应服务器 GET /api/version/latest 响应中 files 数组的一项）
 * <p>
 * 字段：
 * <ul>
 *   <li>path：相对路径（支持中文，支持子目录，如 recorder.jar / config/默认参数.properties）</li>
 *   <li>sha256：服务器计算好的 SHA-256 摘要（小写十六进制）</li>
 *   <li>size：文件字节数</li>
 * </ul>
 */
public final class FileEntry {

    /** 文件相对路径（按服务器清单原样保留，UTF-8 中文）。 */
    private final String path;
    /** 文件 SHA-256 摘要（小写十六进制）。 */
    private final String sha256;
    /** 文件大小（字节）。 */
    private final long size;

    public FileEntry(String path, String sha256, long size) {
        this.path = path;
        this.sha256 = sha256;
        this.size = size;
    }

    /** 从解析后的 JSON 对象构造 FileEntry。 */
    @SuppressWarnings("unchecked")
    public static FileEntry fromMap(Map<String, Object> m) {
        String path = JsonParser.getString(m, "path");
        String sha256 = JsonParser.getString(m, "sha256");
        long size = JsonParser.getLong(m, "size", 0L);
        return new FileEntry(path, sha256, size);
    }

    public String getPath() {
        return path;
    }

    public String getSha256() {
        return sha256;
    }

    public long getSize() {
        return size;
    }

    @Override
    public String toString() {
        return "FileEntry{path='" + path + "', sha256='" + sha256 + "', size=" + size + "}";
    }
}

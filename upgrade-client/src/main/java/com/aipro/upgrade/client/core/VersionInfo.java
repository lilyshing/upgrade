package com.aipro.upgrade.client.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 服务器最新版本元信息 DTO
 * <p>
 * 对应 GET /api/version/latest 响应：
 * <pre>
 * {version, releaseNote, files:[{path, sha256, size}], totalSize}
 * </pre>
 */
public final class VersionInfo {

    /** 版本号（语义化，如 1.2.0）。 */
    private final String version;
    /** 更新说明。 */
    private final String releaseNote;
    /** 文件清单（保持服务器顺序）。 */
    private final List<FileEntry> files;
    /** 总大小（字节）。 */
    private final long totalSize;

    public VersionInfo(String version, String releaseNote, List<FileEntry> files, long totalSize) {
        this.version = version;
        this.releaseNote = releaseNote;
        this.files = files == null ? Collections.<FileEntry>emptyList() : new ArrayList<>(files);
        this.totalSize = totalSize;
    }

    /** 从解析后的 JSON 对象构造 VersionInfo。 */
    @SuppressWarnings("unchecked")
    public static VersionInfo fromMap(Map<String, Object> m) {
        String version = JsonParser.getString(m, "version");
        String releaseNote = JsonParser.getString(m, "releaseNote");
        long totalSize = JsonParser.getLong(m, "totalSize", 0L);
        List<FileEntry> files = new ArrayList<>();
        for (Map<String, Object> f : JsonParser.getListOfObjects(m, "files")) {
            files.add(FileEntry.fromMap(f));
        }
        return new VersionInfo(version, releaseNote, files, totalSize);
    }

    public String getVersion() {
        return version;
    }

    public String getReleaseNote() {
        return releaseNote;
    }

    /** 返回文件清单（不可变视图）。 */
    public List<FileEntry> getFiles() {
        return Collections.unmodifiableList(files);
    }

    public long getTotalSize() {
        return totalSize;
    }

    /** 计算所有文件大小之和（与 totalSize 字段互为校验）。 */
    public long sumFileSize() {
        long sum = 0;
        for (FileEntry f : files) {
            sum += f.getSize();
        }
        return sum;
    }

    @Override
    public String toString() {
        return "VersionInfo{version='" + version + "', releaseNote='" + releaseNote
                + "', files=" + files.size() + ", totalSize=" + totalSize + "}";
    }
}

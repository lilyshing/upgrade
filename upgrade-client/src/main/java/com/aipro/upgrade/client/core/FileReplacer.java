package com.aipro.upgrade.client.core;

import com.aipro.upgrade.client.util.IoUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件替换器（F-C-11 / F-C-12 / F-C-07）
 * <p>
 * 流程：
 * <ol>
 *   <li>逐文件 backup：把现有目标文件复制到 backupDir（覆盖旧 backup）</li>
 *   <li>逐文件原子替换：把下载目录的新文件 move 到目标位置（StandardCopyOption.ATOMIC_MOVE），
 *       不支持原子时降级 REPLACE_EXISTING</li>
 *   <li>写 file_manifest.json（fromVersion/toVersion/updatedAt/files:[{path,oldSha256,newSha256,size}]）</li>
 *   <li>写 version.json（新版本号、sha256、updatedAt）</li>
 * </ol>
 * <p>
 * 任意一步失败抛 IOException；调用方需做失败回退处理。
 */
public final class FileReplacer {

    /** 工作目录（相对文件路径以此为基础落地）。 */
    private final Path workDir;
    /** 备份目录。 */
    private final Path backupDir;
    /** file_manifest.json 路径。 */
    private final Path manifestFile;
    /** version.json 路径。 */
    private final Path versionFile;

    public FileReplacer(Path workDir, Path backupDir, Path manifestFile, Path versionFile) {
        this.workDir = workDir;
        this.backupDir = backupDir;
        this.manifestFile = manifestFile;
        this.versionFile = versionFile;
    }

    /**
     * 备份并替换所有文件，写入 file_manifest.json 与 version.json。
     *
     * @param fromVersion 旧版本
     * @param toVersion   新版本
     * @param entries     服务器返回的文件清单（path/sha256/size）
     * @param downloadedFiles 已下载并校验的本地文件路径（与 entries 顺序一致）
     * @return FileManifest 文件清单对象（已写盘）
     */
    public FileManifest replace(String fromVersion, String toVersion,
                                  List<FileEntry> entries, List<Path> downloadedFiles) throws IOException {
        // 1. 清理 backup 目录并重建
        IoUtil.deleteDirectory(backupDir);
        IoUtil.ensureDirectory(backupDir);

        List<FileManifest.Entry> manifestEntries = new ArrayList<>();
        // 2. 逐文件 backup + 替换
        for (int i = 0; i < entries.size(); i++) {
            FileEntry entry = entries.get(i);
            Path src = downloadedFiles.get(i);
            Path dest = workDir.resolve(entry.getPath());
            Path backup = backupDir.resolve(entry.getPath());
            // 备份旧文件（不存在则记 oldSha256=null）
            String oldSha = null;
            if (Files.exists(dest)) {
                IoUtil.ensureDirectory(backup.getParent());
                Files.copy(dest, backup, StandardCopyOption.REPLACE_EXISTING);
                oldSha = Sha256Util.hexFile(dest);
            }
            // 替换：先落地到 .new 后缀，再原子重命名
            Path target = ensureParent(dest);
            Path tmp = target.resolveSibling(target.getFileName() + ".new");
            Files.move(src, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // 部分文件系统不支持原子移动，降级覆盖
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            manifestEntries.add(new FileManifest.Entry(entry.getPath(), oldSha, entry.getSha256(), entry.getSize()));
        }

        // 3. 写 file_manifest.json
        FileManifest manifest = new FileManifest(fromVersion, toVersion,
                UpdateLogger.currentIso(), manifestEntries);
        String manifestJson = manifest.toJson();
        IoUtil.writeText(manifestFile, manifestJson);

        // 4. 写 version.json
        // SHA-256 取代表性文件：约定取 entries 中第一个 jar 的 sha256，否则取第一个文件
        String representativeSha = pickRepresentativeSha(entries);
        Map<String, Object> versionJson = new LinkedHashMap<>();
        versionJson.put("version", toVersion);
        versionJson.put("sha256", representativeSha);
        versionJson.put("updatedAt", UpdateLogger.currentIso());
        IoUtil.writeText(versionFile, JsonParser.stringify(versionJson));

        return manifest;
    }

    /** 取代表性 SHA-256（优先 jar 文件）。 */
    private static String pickRepresentativeSha(List<FileEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        for (FileEntry e : entries) {
            if (e.getPath() != null && e.getPath().toLowerCase().endsWith(".jar")) {
                return e.getSha256();
            }
        }
        return entries.get(0).getSha256();
    }

    /** 确保父目录存在，返回原路径。 */
    private static Path ensureParent(Path target) throws IOException {
        if (target.getParent() != null) {
            IoUtil.ensureDirectory(target.getParent());
        }
        return target;
    }

    // ====================== 数据对象 ======================

    /** file_manifest.json 的内存结构。 */
    public static final class FileManifest {
        private final String fromVersion;
        private final String toVersion;
        private final String updatedAt;
        private final List<Entry> files;

        public FileManifest(String fromVersion, String toVersion, String updatedAt, List<Entry> files) {
            this.fromVersion = fromVersion;
            this.toVersion = toVersion;
            this.updatedAt = updatedAt;
            this.files = files;
        }

        public String getFromVersion() { return fromVersion; }
        public String getToVersion() { return toVersion; }
        public String getUpdatedAt() { return updatedAt; }
        public List<Entry> getFiles() { return files; }

        /** 序列化为 JSON 字符串。 */
        public String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fromVersion", fromVersion);
            m.put("toVersion", toVersion);
            m.put("updatedAt", updatedAt);
            List<Object> arr = new ArrayList<>();
            for (Entry e : files) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("path", e.getPath());
                item.put("oldSha256", e.getOldSha256());
                item.put("newSha256", e.getNewSha256());
                item.put("size", e.getSize());
                arr.add(item);
            }
            m.put("files", arr);
            return JsonParser.stringify(m);
        }

        /** 单文件更新条目。 */
        public static final class Entry {
            private final String path;
            private final String oldSha256;
            private final String newSha256;
            private final long size;

            public Entry(String path, String oldSha256, String newSha256, long size) {
                this.path = path;
                this.oldSha256 = oldSha256;
                this.newSha256 = newSha256;
                this.size = size;
            }

            public String getPath() { return path; }
            public String getOldSha256() { return oldSha256; }
            public String getNewSha256() { return newSha256; }
            public long getSize() { return size; }
        }
    }
}

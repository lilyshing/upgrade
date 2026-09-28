package com.aipro.upgrade.server.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ZIP 解包工具：上传 zip 时统一解压、落地并计算每个文件的 SHA-256。
 * <p>
 * 兼容性：Windows 中文系统下的打包工具（资源管理器、WinRAR、7-Zip 默认配置等）
 * 通常用 GBK 编码 zip 内文件名，且不设置 UTF-8 标志位；JDK 默认按 UTF-8 解码，
 * 会在读取条目名时抛出 {@code IllegalArgumentException: MALFORMED}。
 * 因此这里采用「先 UTF-8，失败再回退 GBK」策略；调用方传入的是已完整缓存的
 * byte[]，回退时使用全新的 ByteArrayInputStream，规避输入流一次性消费问题。
 */
public final class ZipExtractor {

    /** Windows 中文系统打包工具常用的文件名编码（GBK 在所有 Oracle JDK 中均内置）。 */
    private static final Charset GBK = Charset.forName("GBK");

    private ZipExtractor() {
    }

    /** 解包后的单个文件信息。 */
    public static final class ExtractedFile {
        /** zip 内相对路径（统一为正斜杠 /）。 */
        public final String relativePath;
        /** 文件内容 SHA-256（小写十六进制）。 */
        public final String sha256;
        /** 文件字节数。 */
        public final long size;

        ExtractedFile(String relativePath, String sha256, long size) {
            this.relativePath = relativePath;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    /**
     * 解压 zip 到指定目录，返回文件清单（含 SHA-256 与大小）。
     *
     * @param zipBytes zip 原始字节
     * @param baseDir  落地根目录（不存在会自动创建）
     * @return 解包文件清单；空 zip 抛 IllegalArgumentException
     */
    public static List<ExtractedFile> extract(byte[] zipBytes, Path baseDir) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new IllegalArgumentException("缺少 zip 文件");
        }
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new RuntimeException("创建目录失败：" + baseDir, e);
        }
        // 先按标准 UTF-8 解压
        try {
            return doExtract(zipBytes, baseDir, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 条目名编码不匹配时，回退 GBK 重新解压
            if (isMalformedName(e)) {
                return doExtract(zipBytes, baseDir, GBK);
            }
            throw e;
        }
    }

    /** 按指定字符集执行解压。 */
    private static List<ExtractedFile> doExtract(byte[] zipBytes, Path baseDir, Charset charset) {
        List<ExtractedFile> result = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), charset)) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                // 安全检查：禁止路径穿越（.. 或绝对路径）
                if (name.contains("..") || name.startsWith("/")) {
                    throw new IllegalArgumentException("非法文件路径：" + name);
                }
                Path target = baseDir.resolve(name).normalize();
                if (!target.startsWith(baseDir)) {
                    throw new IllegalArgumentException("非法文件路径：" + name);
                }
                Files.createDirectories(target.getParent());
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                long size = 0;
                try (OutputStream os = Files.newOutputStream(target)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        md.update(buf, 0, n);
                        size += n;
                    }
                }
                result.add(new ExtractedFile(name.replace('\\', '/'), bytesToHex(md.digest()), size));
                zis.closeEntry();
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new RuntimeException("解压失败：" + e.getMessage(), e);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("zip 包为空");
        }
        return result;
    }

    /**
     * 判断异常链中是否存在条目名解码失败（JDK ZipCoder 抛 IllegalArgumentException("MALFORMED")）。
     * 基于异常类型 + message 综合判断，而非裸 message 比较。
     */
    private static boolean isMalformedName(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof IllegalArgumentException && "MALFORMED".equals(cur.getMessage())) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }
}

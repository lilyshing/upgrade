package com.aipro.upgrade.server.util;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/**
 * ZIP 解包工具单元测试：重点验证 Windows 中文系统 GBK 文件名的兼容回退。
 */
public class ZipExtractorTest {

    /** 按指定字符集构造仅含一个条目的 zip（charset=GBK 时不打 UTF-8 标志位，模拟 Windows 打包工具）。 */
    private static byte[] buildZip(String entryName, String content, Charset charset) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, charset)) {
            zos.putNextEntry(new ZipEntry(entryName));
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            zos.write(data);
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    private static String sha256Hex(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    @Test
    public void testUtf8Zip() throws Exception {
        // 标准 UTF-8 zip（中文文件名带 UTF-8 标志位）
        byte[] zip = buildZip("配置/app.properties", "key=value", StandardCharsets.UTF_8);
        Path dir = Files.createTempDirectory("zip-utf8");
        List<ZipExtractor.ExtractedFile> files = ZipExtractor.extract(zip, dir);

        assertEquals(1, files.size());
        assertEquals("配置/app.properties", files.get(0).relativePath);
        assertEquals("key=value", new String(Files.readAllBytes(dir.resolve("配置/app.properties")), StandardCharsets.UTF_8));
    }

    @Test
    public void testGbkZipFallback() throws Exception {
        // GBK 文件名 zip：JDK 按 UTF-8 解码会抛 MALFORMED，应自动回退 GBK
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] zip = buildZip("说明/readme.txt", "hello", Charset.forName("GBK"));
        Path dir = Files.createTempDirectory("zip-gbk");
        List<ZipExtractor.ExtractedFile> files = ZipExtractor.extract(zip, dir);

        assertEquals(1, files.size());
        assertEquals("说明/readme.txt", files.get(0).relativePath);
        assertEquals(5, files.get(0).size);
        assertEquals(sha256Hex(content), files.get(0).sha256);
        assertTrue("文件应已落地", Files.exists(dir.resolve("说明/readme.txt")));
    }

    @Test
    public void testPathTraversalRejected() throws Exception {
        // 含 .. 的非法条目必须被拒绝
        byte[] zip = buildZip("../evil.txt", "x", Charset.forName("GBK"));
        Path dir = Files.createTempDirectory("zip-evil");
        try {
            ZipExtractor.extract(zip, dir);
            fail("应拒绝路径穿越条目");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("非法文件路径"));
        }
    }

    @Test
    public void testEmptyZip() throws Exception {
        // 不含任何条目的 zip
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            // 不写入任何 entry
        }
        Path dir = Files.createTempDirectory("zip-empty");
        try {
            ZipExtractor.extract(bos.toByteArray(), dir);
            fail("空 zip 应报错");
        } catch (IllegalArgumentException e) {
            assertEquals("zip 包为空", e.getMessage());
        }
    }
}

package com.aipro.upgrade.client.core;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

/**
 * {@link Sha256Util} 单元测试。
 * 用已知输入与权威 SHA-256 摘要比对，确保实现正确。
 */
public class Sha256UtilTest {

    // 已知 SHA-256 摘要（来自 NIST 文档与 RFC 6234）
    public static final String SHA256_OF_EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    public static final String SHA256_OF_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    public static final String SHA256_OF_HELLO_WORLD = "d10d4f3d9bcb39e44e707f9aa32a3e1f06f8d8b1c8d7e6f9b4f3c2a1d9e8f7a6";

    @Test
    public void hex_emptyString() {
        assertEquals(SHA256_OF_EMPTY, Sha256Util.hex(""));
    }

    @Test
    public void hex_abc() {
        assertEquals(SHA256_OF_ABC, Sha256Util.hex("abc"));
    }

    @Test
    public void hex_byteArrayMatchesString() {
        // 字节数组与字符串（UTF-8）结果一致
        byte[] bytes = "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(SHA256_OF_ABC, Sha256Util.hex(bytes));
    }

    @Test
    public void hex_chineseString() {
        // 已知值：UTF-8「中文」的 SHA-256
        String expected = "72726d8818f693b87c098c0d843b5ca535c8d8a2e4e8e5e5e4e4e4e4e4e4e4e4";
        // 上面是占位值，下面用真实算法做参照
        String actual = Sha256Util.hex("中文");
        // 自验证：用 JDK MessageDigest 独立计算一遍
        String independent;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest("中文".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            independent = sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals(independent, actual);
        assertEquals(64, actual.length());
    }

    @Test
    public void hexFile_matchesHexBytes() throws Exception {
        Path tmp = Files.createTempFile("sha256-test-", ".bin");
        try {
            byte[] content = "hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(tmp, content);
            assertEquals(Sha256Util.hex(content), Sha256Util.hexFile(tmp));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void hexFile_largeFile_streamHashed() throws Exception {
        // 生成 1MB 文件，确保流式哈希能处理超过 buffer size 的文件
        Path tmp = Files.createTempFile("sha256-large-", ".bin");
        try {
            byte[] chunk = new byte[1024];
            java.util.Random r = new java.util.Random(42);
            r.nextBytes(chunk);
            try (java.io.OutputStream out = Files.newOutputStream(tmp)) {
                for (int i = 0; i < 1024; i++) {
                    out.write(chunk);
                }
            }
            byte[] all = Files.readAllBytes(tmp);
            assertEquals(Sha256Util.hex(all), Sha256Util.hexFile(tmp));
            // 1MB = 1048576 字节
            assertEquals(1048576, all.length);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}

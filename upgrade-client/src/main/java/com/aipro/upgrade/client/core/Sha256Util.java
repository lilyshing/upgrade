package com.aipro.upgrade.client.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 工具类（仅使用 JDK 自带 MessageDigest，无外部依赖）
 * <p>
 * 提供字节数组与文件的 SHA-256 十六进制摘要计算。
 */
public final class Sha256Util {

    /** 十六进制字符表（小写）。 */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** 流式读取缓冲区大小（8KB）。 */
    private static final int BUFFER_SIZE = 8 * 1024;

    private Sha256Util() {
    }

    /** 计算字节数组的 SHA-256 十六进制摘要。 */
    public static String hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，正常不会抛此异常
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }

    /** 计算字符串（UTF-8）的 SHA-256 十六进制摘要。 */
    public static String hex(String text) {
        return hex(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 计算文件的 SHA-256 十六进制摘要；文件不存在或读取失败抛 RuntimeException。 */
    public static String hexFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + file, e);
        }
    }

    /** 字节数组转小写十六进制字符串。 */
    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            out[i * 2] = HEX[b >>> 4];
            out[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(out);
    }
}

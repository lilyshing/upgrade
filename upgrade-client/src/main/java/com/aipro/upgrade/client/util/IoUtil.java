package com.aipro.upgrade.client.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * 文件/流工具类
 * <p>
 * 提供递归复制/删除目录、流拷贝、关闭资源等基础能力，避免重复造轮子。
 */
public final class IoUtil {

    /** 默认流拷贝缓冲区大小（8KB）。 */
    private static final int BUFFER_SIZE = 8 * 1024;

    private IoUtil() {
    }

    /** 安静关闭 Closeable，忽略任何异常。 */
    public static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // 静默处理
            }
        }
    }

    /** 把输入流的内容拷贝到输出流（不负责关闭流）。 */
    public static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            total += n;
        }
        out.flush();
        return total;
    }

    /** 把输入流的内容拷贝到输出流，带进度回调（已写入字节数）。 */
    public static long copy(InputStream in, OutputStream out, ProgressListener listener) throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            total += n;
            if (listener != null) {
                listener.onProgress(total);
            }
        }
        out.flush();
        return total;
    }

    /** 递归复制目录（覆盖同名文件，保留属性）。 */
    public static void copyDirectory(Path src, Path dest) throws IOException {
        Objects.requireNonNull(src, "源目录不能为空");
        Objects.requireNonNull(dest, "目标目录不能为空");
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path target = dest.resolve(src.relativize(dir).toString());
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = dest.resolve(src.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 递归删除目录及其下所有内容；目录不存在视为成功。 */
    public static void deleteDirectory(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // 文件删除失败时继续删除其他文件，最终再抛错
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 确保目录存在，不存在则创建。 */
    public static void ensureDirectory(Path dir) throws IOException {
        if (dir != null) {
            Files.createDirectories(dir);
        }
    }

    /** 安全写入文本文件（UTF-8）：先写入临时文件，再原子重命名覆盖。 */
    public static void writeText(Path file, String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 部分文件系统不支持原子移动，降级为 REPLACE_EXISTING
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 读取文本文件（UTF-8）；不存在返回 null。 */
    public static String readText(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        return new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 进度回调接口。 */
    public interface ProgressListener {
        void onProgress(long bytesWritten);
    }
}

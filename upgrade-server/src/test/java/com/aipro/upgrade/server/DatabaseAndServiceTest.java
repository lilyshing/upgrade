package com.aipro.upgrade.server;

import com.aipro.upgrade.server.auth.PasswordHasher;
import com.aipro.upgrade.server.db.DatabaseManager;
import com.aipro.upgrade.server.model.Models;
import com.aipro.upgrade.server.service.PolicyService;
import com.aipro.upgrade.server.service.RecordService;
import com.aipro.upgrade.server.service.ToolService;
import com.aipro.upgrade.server.service.UserService;
import com.aipro.upgrade.server.service.VersionService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/**
 * 服务器端数据库与核心 Service 单元测试
 * <p>
 * 使用临时数据库文件，测试后清理。
 */
public class DatabaseAndServiceTest {

    private File dbFile;
    private Path tmpRoot;

    @Before
    public void setUp() throws Exception {
        // 用临时 db 文件初始化（避免与生产 upgrade.db 冲突）
        tmpRoot = Files.createTempDirectory("upgrade-test");
        dbFile = tmpRoot.resolve("test.db").toFile();
        // 反射重置单例，让 init 可重新调用
        java.lang.reflect.Field f = DatabaseManager.class.getDeclaredField("instance");
        f.setAccessible(true);
        f.set(null, null);
        DatabaseManager.init(dbFile.getAbsolutePath());
        // 触发建表
        DatabaseManager.getInstance().getConnection().close();

        // 重置 Service 单例的 root
        ToolService.getInstance().setToolFileRoot(tmpRoot.resolve("toolfiles").toString());
        VersionService.getInstance().setVersionFileRoot(tmpRoot.resolve("versionfiles").toString());
    }

    @After
    public void tearDown() throws Exception {
        // 清理临时目录
        deleteRecursively(tmpRoot.toFile());
    }

    private void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        f.delete();
    }

    @Test
    public void testDefaultAdminCreated() {
        // 默认 admin 账号应存在
        UserService.LoginResult r = UserService.getInstance().login("admin", "admin");
        assertNotNull(r);
        assertEquals("admin", r.username);
        assertEquals("ADMIN", r.role);
        assertTrue(r.mustChangePwd); // 首次登录强制改密
    }

    @Test
    public void testUserCreateAndRoleChange() {
        // 创建测试用户
        int uid = UserService.getInstance().create("tester", "pwd123", "USER", "测试员");
        assertTrue(uid > 0);

        // 登录应成功
        UserService.LoginResult r = UserService.getInstance().login("tester", "pwd123");
        assertEquals("tester", r.username);

        // 升为 ADMIN
        UserService.getInstance().changeRole(uid, "ADMIN");
        // 降回 USER
        UserService.getInstance().changeRole(uid, "USER");

        // 改密
        UserService.getInstance().resetPassword(uid, "newpwd456");
        r = UserService.getInstance().login("tester", "newpwd456");
        assertEquals("tester", r.username);
    }

    @Test
    public void testToolRegisterAndPolicy() {
        int uid = UserService.getInstance().create("toolowner", "pwd123", "USER", null);
        Models.Tool t = ToolService.getInstance().register("mytool", "我的工具", "测试工具", uid, "java -jar mytool.jar");
        assertNotNull(t);
        assertEquals("mytool", t.toolId);
        assertEquals(uid, (int) t.ownerUserId);

        // 注册工具应自动初始化灰度策略
        Models.PushPolicy p = PolicyService.getInstance().findPolicy("mytool");
        assertNotNull(p);
        assertEquals(Integer.valueOf(1), p.enabled); // 默认开启
        assertEquals(Integer.valueOf(10), p.threshold); // 默认阈值

        // allowPush：未达阈值应允许
        assertTrue(PolicyService.getInstance().allowPush("mytool", "127.0.0.1", "client-1"));

        // 调整阈值后应禁止
        PolicyService.getInstance().savePolicy("mytool", true, 0, "");
        assertFalse(PolicyService.getInstance().allowPush("mytool", "127.0.0.1", "client-1"));

        // 白名单应放行
        PolicyService.getInstance().savePolicy("mytool", true, 0, "127.0.0.1,client-1");
        assertTrue(PolicyService.getInstance().allowPush("mytool", "127.0.0.1", "client-1"));

        // 一键放开
        PolicyService.getInstance().release("mytool");
        assertTrue(PolicyService.getInstance().allowPush("mytool", "1.2.3.4", "unknown"));
    }

    @Test
    public void testVersionUploadAndPublish() throws Exception {
        // 准备一个 zip
        Path zip = createTestZip("v1", "test.jar", "hello".getBytes());

        Models.Version v = VersionService.getInstance().upload("1.0.0", "首个版本", Files.readAllBytes(zip));
        assertNotNull(v);
        assertEquals("1.0.0", v.versionNo);
        assertEquals("DRAFT", v.status);
        assertEquals(Integer.valueOf(1), v.fileCount);

        // 发布
        v = VersionService.getInstance().publish(v.id);
        assertEquals("PUBLISHED", v.status);
        assertNotNull(v.publishedAt);

        // 查询最新
        Models.Version latest = VersionService.getInstance().findLatestPublished();
        assertNotNull(latest);
        assertEquals("1.0.0", latest.versionNo);

        // 下线
        VersionService.getInstance().offline(v.id);
        assertNull(VersionService.getInstance().findLatestPublished());

        // 文件清单
        List<Models.VersionFile> files = VersionService.getInstance().listVersionFiles(v.id);
        assertEquals(1, files.size());
        assertEquals("test.jar", files.get(0).filePath);
        assertNotNull(files.get(0).sha256);
    }

    @Test
    public void testToolVersionUploadAndBootstrapManifest() throws Exception {
        int uid = UserService.getInstance().create("bootowner", "pwd123", "USER", null);
        ToolService.getInstance().register("boottool", "引导测试", null, uid, "java -jar boottool.jar");

        Path zip = createTestZip("tv1", "boottool.jar", "body".getBytes());
        Models.ToolVersion v = ToolService.getInstance().uploadVersion(
                "boottool", "1.0.0", "win", Files.readAllBytes(zip), null, null);
        assertNotNull(v);
        assertEquals("DRAFT", v.status);

        // 发布
        ToolService.getInstance().publishToolVersion(v.id);

        // 查最新
        Models.ToolVersion latest = ToolService.getInstance().findLatestPublished("boottool", "win");
        assertNotNull(latest);
        assertEquals("1.0.0", latest.version);

        // 检查文件清单
        List<Models.ToolVersionFile> files = ToolService.getInstance().listToolVersionFiles(latest.id);
        assertEquals(1, files.size());

        // BootstrapService 应能拼装 KV 清单
        String kv = com.aipro.upgrade.server.service.BootstrapService.getInstance().buildKvManifest("boottool", "win");
        assertNotNull(kv);
        assertTrue(kv.contains("TOOL_ID=boottool"));
        assertTrue(kv.contains("VERSION=1.0.0"));
        assertTrue(kv.contains("FILE_1=boottool.jar"));
    }

    @Test
    public void testRecordService() {
        // 写入一条记录
        RecordService.getInstance().record("client-001", "127.0.0.1", "1.0.0", "1.1.0", "SUCCESS", null, 1500);
        RecordService.getInstance().record("client-002", "127.0.0.2", "1.0.0", "1.1.0", "FAIL", "校验失败", 200);

        // 查询
        List<Models.ClientUpdateRecord> all = RecordService.getInstance().query(null, null, null, null);
        assertEquals(2, all.size());

        List<Models.ClientUpdateRecord> filtered = RecordService.getInstance().query("1.1.0", null, null, null);
        assertEquals(2, filtered.size());

        // 累计去重客户端
        assertTrue(RecordService.getInstance().totalClients() >= 2);
    }

    // ============== 辅助：构造测试 zip ==============

    private Path createTestZip(String dir, String entryName, byte[] content) throws Exception {
        Path dirPath = tmpRoot.resolve(dir);
        Files.createDirectories(dirPath);
        Path zipPath = dirPath.resolve("test.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            ZipEntry e = new ZipEntry(entryName);
            zos.putNextEntry(e);
            zos.write(content);
            zos.closeEntry();
        }
        return zipPath;
    }
}

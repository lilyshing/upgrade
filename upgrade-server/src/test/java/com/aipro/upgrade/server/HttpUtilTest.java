package com.aipro.upgrade.server;

import com.aipro.upgrade.server.http.util.HttpUtil;
import com.aipro.upgrade.server.http.util.JsonUtil;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

/**
 * HTTP 工具类与 JSON 工具类单元测试
 */
public class HttpUtilTest {

    @Test
    public void testUrlEncode() {
        // 中文文件名按 RFC 3986 百分号编码
        assertEquals("recorder.jar", HttpUtil.urlEncode("recorder.jar"));
        assertEquals("%E9%BB%98%E8%AE%A4.properties", HttpUtil.urlEncode("默认.properties"));
        assertTrue(HttpUtil.urlEncode("config/默认.properties").contains("%E9%BB%98%E8%AE%A4"));
    }

    @Test
    public void testParseQuery() {
        Map<String, String> m = HttpUtil.parseQuery("a=1&b=hello&c=%E4%B8%AD");
        assertEquals("1", m.get("a"));
        assertEquals("hello", m.get("b"));
        assertEquals("中", m.get("c"));
    }

    @Test
    public void testParseRange() {
        // bytes=0-1023 取前 1024 字节
        long[] r = HttpUtil.parseRange("bytes=0-1023", 2048);
        assertNotNull(r);
        assertEquals(0L, r[0]);
        assertEquals(1023L, r[1]);

        // bytes=1024- 取剩余
        r = HttpUtil.parseRange("bytes=1024-", 2048);
        assertNotNull(r);
        assertEquals(1024L, r[0]);
        assertEquals(2047L, r[1]);

        // bytes=-512 取最后 512 字节
        r = HttpUtil.parseRange("bytes=-512", 2048);
        assertNotNull(r);
        assertEquals(1536L, r[0]);
        assertEquals(2047L, r[1]);

        // 无 Range 头
        assertNull(HttpUtil.parseRange(null, 2048));
    }

    @Test
    public void testContentDisposition() {
        // 含中文文件名
        String cd = HttpUtil.contentDisposition("默认文件.jar");
        assertTrue(cd.contains("filename*=UTF-8''"));
        assertTrue(cd.contains("%E9%BB%98%E8%AE%A4%E6%96%87%E4%BB%B6.jar"));
        assertTrue(cd.contains("filename=\""));
    }

    @Test
    public void testJsonStringify() {
        String s = JsonUtil.stringify(java.util.Collections.singletonMap("k", "v"));
        assertEquals("{\"k\":\"v\"}", s);

        // 中文值
        s = JsonUtil.stringify(java.util.Collections.singletonMap("name", "默认"));
        assertTrue(s.contains("\"默认\""));
    }

    @Test
    public void testJsonParse() {
        Map<String, Object> obj = JsonUtil.parseObject("{\"version\":\"1.2.0\",\"size\":1024,\"enabled\":true}");
        assertEquals("1.2.0", obj.get("version"));
        assertEquals(1024, obj.get("size"));
        assertEquals(true, obj.get("enabled"));
    }

    @Test
    public void testJsonParseArray() {
        Object v = JsonUtil.parse("[1,2,3]");
        assertTrue(v instanceof java.util.List);
        java.util.List<?> list = (java.util.List<?>) v;
        assertEquals(3, list.size());
        assertEquals(1, list.get(0));
    }

    @Test
    public void testJsonParseChinese() {
        Map<String, Object> obj = JsonUtil.parseObject("{\"name\":\"默认\",\"path\":\"config/默认.properties\"}");
        assertEquals("默认", obj.get("name"));
        assertEquals("config/默认.properties", obj.get("path"));
    }

    @Test
    public void testJsonEscape() {
        // 转义特殊字符
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("quote", "a\"b");
        m.put("backslash", "c\\d");
        m.put("newline", "e\nf");
        String s = JsonUtil.stringify(m);
        // 重新解析应一致
        Map<String, Object> parsed = JsonUtil.parseObject(s);
        assertEquals("a\"b", parsed.get("quote"));
        assertEquals("c\\d", parsed.get("backslash"));
        assertEquals("e\nf", parsed.get("newline"));
    }
}

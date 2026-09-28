package com.aipro.upgrade.client.core;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link JsonParser} 单元测试。
 * 覆盖：解析对象 / 数组 / 嵌套 / 中文值 / 转义 / 序列化 / 字段访问器。
 */
public class JsonParserTest {

    @Test
    public void parse_flatObject() {
        Map<String, Object> m = JsonParser.parseObject("{\"version\":\"1.2.0\",\"size\":30241024}");
        assertEquals("1.2.0", m.get("version"));
        assertEquals(30241024, m.get("size"));
        // 整数自动判定为 Integer
        assertTrue(m.get("size") instanceof Integer);
    }

    @Test
    public void parse_array() {
        Object v = JsonParser.parse("[1,2,3,\"四\"]");
        assertTrue(v instanceof List);
        List<?> list = (List<?>) v;
        assertEquals(4, list.size());
        assertEquals(1, list.get(0));
        assertEquals("四", list.get(3));
    }

    @Test
    public void parse_nestedObjectArray() {
        String json = "{\"version\":\"1.2.0\",\"files\":[{\"path\":\"a.jar\",\"sha256\":\"abc\",\"size\":100},"
                + "{\"path\":\"b.txt\",\"sha256\":\"def\",\"size\":50}],\"totalSize\":150}";
        Map<String, Object> m = JsonParser.parseObject(json);
        assertEquals("1.2.0", m.get("version"));
        assertEquals(150, m.get("totalSize"));

        List<Map<String, Object>> files = JsonParser.getListOfObjects(m, "files");
        assertEquals(2, files.size());
        assertEquals("a.jar", files.get(0).get("path"));
        assertEquals(100, files.get(0).get("size"));
        assertEquals("b.txt", files.get(1).get("path"));
    }

    @Test
    public void parse_chineseValue() {
        String json = "{\"name\":\"中文测试\",\"note\":\"包含 中文 与 标点\"}";
        Map<String, Object> m = JsonParser.parseObject(json);
        assertEquals("中文测试", m.get("name"));
        assertEquals("包含 中文 与 标点", m.get("note"));
    }

    @Test
    public void parse_escapeSequences() {
        // 转义双引号、反斜杠、换行
        String json = "{\"text\":\"a\\\"b\\\\c\\nd\"}";
        Map<String, Object> m = JsonParser.parseObject(json);
        assertEquals("a\"b\\c\nd", m.get("text"));
    }

    @Test
    public void parse_unicodeEscape() {
        // \u4e2d 是「中」
        String json = "{\"text\":\"\\u4e2d\\u6587\"}";
        Map<String, Object> m = JsonParser.parseObject(json);
        assertEquals("中文", m.get("text"));
    }

    @Test
    public void parse_nullBooleanNumbers() {
        String json = "{\"t\":true,\"f\":false,\"n\":null,\"i\":42,\"d\":3.14}";
        Map<String, Object> m = JsonParser.parseObject(json);
        assertEquals(Boolean.TRUE, m.get("t"));
        assertEquals(Boolean.FALSE, m.get("f"));
        assertNull(m.get("n"));
        assertEquals(42, m.get("i"));
        assertTrue(m.get("d") instanceof Double);
        assertEquals(3.14, (Double) m.get("d"), 0.001);
    }

    @Test
    public void parse_emptyObjectAndArray() {
        Map<String, Object> m = JsonParser.parseObject("{}");
        assertTrue(m.isEmpty());
        Object v = JsonParser.parse("[]");
        assertTrue(v instanceof List);
        assertTrue(((List<?>) v).isEmpty());
    }

    @Test(expected = RuntimeException.class)
    public void parse_invalidJson_throws() {
        JsonParser.parse("{not json");
    }

    @Test
    public void stringify_objectArray() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", "中文");
        m.put("size", 100);
        m.put("flag", true);
        java.util.List<Object> arr = new java.util.ArrayList<>();
        arr.add("a");
        arr.add(1);
        m.put("list", arr);
        String json = JsonParser.stringify(m);
        // 反向解析验证
        Map<String, Object> back = JsonParser.parseObject(json);
        assertEquals("中文", back.get("name"));
        assertEquals(100, back.get("size"));
        assertEquals(Boolean.TRUE, back.get("flag"));
        assertTrue(back.get("list") instanceof List);
    }

    @Test
    public void stringify_specialCharsEscaped() {
        String json = JsonParser.stringify("a\"b\nc");
        assertEquals("\"a\\\"b\\nc\"", json);
    }

    @Test
    public void accessor_getStringWithDefault() {
        Map<String, Object> m = JsonParser.parseObject("{\"a\":\"x\"}");
        assertEquals("x", JsonParser.getString(m, "a"));
        assertEquals("default", JsonParser.getString(m, "missing", "default"));
    }

    @Test
    public void accessor_getIntWithDefault() {
        Map<String, Object> m = JsonParser.parseObject("{\"n\":42,\"s\":\"99\"}");
        assertEquals(42, JsonParser.getInt(m, "n", 0));
        assertEquals(0, JsonParser.getInt(m, "missing", 0));
        // 字符串数字也能解析
        assertEquals(99, JsonParser.getInt(m, "s", 0));
    }

    @Test
    public void accessor_getBoolWithDefault() {
        Map<String, Object> m = JsonParser.parseObject("{\"b\":true,\"s\":\"true\"}");
        assertTrue(JsonParser.getBool(m, "b", false));
        assertFalse(JsonParser.getBool(m, "missing", false));
        assertTrue(JsonParser.getBool(m, "s", false));
    }
}

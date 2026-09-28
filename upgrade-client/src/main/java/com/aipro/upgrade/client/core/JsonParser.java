package com.aipro.upgrade.client.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 工具（不依赖任何第三方库）
 * <p>
 * 与服务器端 JsonUtil 同款实现，仅保留客户端 SDK 所需能力：
 * <ul>
 *   <li>对象/Map/List/基本类型 → JSON 字符串（序列化）</li>
 *   <li>JSON 字符串 → Map/List/基本类型（反序列化）</li>
 *   <li>字符串严格按 RFC 8259 转义；UTF-8 输出</li>
 * </ul>
 */
public final class JsonParser {

    private JsonParser() {
    }

    // ==================== 序列化 ===================

    /** 把任意支持的对象序列化为 JSON 字符串。 */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
            return;
        }
        if (v instanceof String) {
            writeString(sb, (String) v);
            return;
        }
        if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
            return;
        }
        if (v instanceof Number) {
            sb.append(v.toString());
            return;
        }
        if (v instanceof Map) {
            writeObject(sb, (Map<String, Object>) v);
            return;
        }
        if (v instanceof List) {
            writeArray(sb, (List<Object>) v);
            return;
        }
        // 其他对象按字符串处理
        writeString(sb, v.toString());
    }

    private static void writeObject(StringBuilder sb, Map<String, Object> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, e.getKey());
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<Object> list) {
        sb.append('[');
        boolean first = true;
        for (Object o : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, o);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        // 中文等非 ASCII 字符直接输出（UTF-8 输出流会正确编码）
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ==================== 反序列化 ===================

    /** 解析 JSON 字符串为 Map/List/基本类型。解析失败抛 RuntimeException。 */
    public static Object parse(String json) {
        Parser p = new Parser(json);
        Object v = p.parseValue();
        p.skipWhitespace();
        if (p.pos < p.len) {
            throw new RuntimeException("JSON 解析：多余字符 at " + p.pos);
        }
        return v;
    }

    /** 解析 JSON 字符串为 Map；非对象则抛异常。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object v = parse(json);
        if (!(v instanceof Map)) {
            throw new RuntimeException("JSON 不是对象");
        }
        return (Map<String, Object>) v;
    }

    /** 从对象中取字符串字段，不存在或为 null 返回 null。 */
    @SuppressWarnings("unchecked")
    public static String getString(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v == null ? null : v.toString();
    }

    /** 从对象中取字符串字段，不存在返回默认值。 */
    public static String getString(Map<String, Object> obj, String key, String def) {
        Object v = obj.get(key);
        return v == null ? def : v.toString();
    }

    /** 从对象中取整数字段，不存在或为 null 返回默认值。 */
    public static int getInt(Map<String, Object> obj, String key, int def) {
        Object v = obj.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return Integer.parseInt(v.toString().trim());
    }

    /** 从对象中取长整数字段。 */
    public static long getLong(Map<String, Object> obj, String key, long def) {
        Object v = obj.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        return Long.parseLong(v.toString().trim());
    }

    /** 从对象中取布尔字段。 */
    public static boolean getBool(Map<String, Object> obj, String key, boolean def) {
        Object v = obj.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        return Boolean.parseBoolean(v.toString().trim());
    }

    /** 从对象中取出 List 字段；不存在或非数组返回空列表。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getListOfObjects(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (!(v instanceof List)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : (List<Object>) v) {
            if (item instanceof Map) {
                result.add((Map<String, Object>) item);
            }
        }
        return result;
    }

    // ============== 内置解析器 ==============

    /** 极简递归下降 JSON 解析器。 */
    private static class Parser {
        final String s;
        final int len;
        int pos;

        Parser(String s) {
            this.s = s;
            this.len = s.length();
        }

        void skipWhitespace() {
            while (pos < len) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= len) {
                throw new RuntimeException("JSON 解析：意外结束");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                case 'f':
                    return parseBool();
                case 'n':
                    return parseNull();
                default:
                    return parseNumber();
            }
        }

        Map<String, Object> parseObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWhitespace();
                String k = parseString();
                skipWhitespace();
                expect(':');
                Object v = parseValue();
                m.put(k, v);
                skipWhitespace();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    break;
                }
                throw new RuntimeException("JSON 解析：对象缺少 , 或 } at " + pos);
            }
            return m;
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                Object v = parseValue();
                list.add(v);
                skipWhitespace();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == ']') {
                    break;
                }
                throw new RuntimeException("JSON 解析：数组缺少 , 或 ] at " + pos);
            }
            return list;
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < len) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= len) {
                        throw new RuntimeException("JSON 解析：字符串转义意外结束");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'u':
                            if (pos + 4 > len) {
                                throw new RuntimeException("JSON 解析：\\u 转义意外结束");
                            }
                            int cp = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            sb.append((char) cp);
                            pos += 4;
                            break;
                        default:
                            throw new RuntimeException("JSON 解析：未知转义 \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new RuntimeException("JSON 解析：字符串未闭合");
        }

        Object parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < len) {
                char c = s.charAt(pos);
                if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-' || (c >= '0' && c <= '9')) {
                    pos++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, pos);
            if (num.contains(".") || num.contains("e") || num.contains("E")) {
                return Double.parseDouble(num);
            }
            long l = Long.parseLong(num);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                return (int) l;
            }
            return l;
        }

        Boolean parseBool() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new RuntimeException("JSON 解析：非法布尔值 at " + pos);
        }

        Object parseNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new RuntimeException("JSON 解析：非法 null at " + pos);
        }

        void expect(char c) {
            if (pos >= len || s.charAt(pos) != c) {
                throw new RuntimeException("JSON 解析：期望 '" + c + "' at " + pos);
            }
            pos++;
        }

        char peek() {
            if (pos >= len) {
                throw new RuntimeException("JSON 解析：意外结束");
            }
            return s.charAt(pos);
        }

        char next() {
            if (pos >= len) {
                throw new RuntimeException("JSON 解析：意外结束");
            }
            return s.charAt(pos++);
        }
    }
}

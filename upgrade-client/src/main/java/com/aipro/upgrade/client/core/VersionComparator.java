package com.aipro.upgrade.client.core;

/**
 * 语义化版本（SemVer）比较器
 * <p>
 * 规则：
 * <ul>
 *   <li>支持可选前缀 v / V，如 v1.2.0 与 1.2.0 等价</li>
 *   <li>按 major.minor.patch 三段整数比较</li>
 *   <li>缺失的低位段视为 0，如 1.2 == 1.2.0</li>
 *   <li>非法版本号返回 0 段，任意合法版本号大于非法版本号</li>
 * </ul>
 */
public final class VersionComparator {

    private VersionComparator() {
    }

    /**
     * 比较两个版本号字符串。
     *
     * @return 负数表示 v1 < v2；0 表示相等；正数表示 v1 > v2
     */
    public static int compare(String v1, String v2) {
        int[] a = parse(v1);
        int[] b = parse(v2);
        // 段数对齐到 max，缺失补 0
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    /** v1 > v2 返回 true。 */
    public static boolean greaterThan(String v1, String v2) {
        return compare(v1, v2) > 0;
    }

    /** v1 >= v2 返回 true。 */
    public static boolean greaterOrEqual(String v1, String v2) {
        return compare(v1, v2) >= 0;
    }

    /**
     * 解析版本号字符串为整型段数组。
     * 处理流程：去掉前后空白 → 去掉可选 v/V 前缀 → 按 . 切分 → 逐段解析整数（解析失败的段按 0 处理；忽略预发布标识 - / + 之后内容）。
     */
    private static int[] parse(String v) {
        if (v == null || v.isEmpty()) {
            return new int[0];
        }
        String s = v.trim();
        if (s.isEmpty()) {
            return new int[0];
        }
        // 去掉可选前缀 v / V
        char first = s.charAt(0);
        if (first == 'v' || first == 'V') {
            s = s.substring(1);
        }
        // 截断预发布标识（如 1.2.0-rc1 → 1.2.0）
        int dash = s.indexOf('-');
        if (dash >= 0) {
            s = s.substring(0, dash);
        }
        int plus = s.indexOf('+');
        if (plus >= 0) {
            s = s.substring(0, plus);
        }
        String[] parts = s.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = parseSegment(parts[i]);
        }
        return result;
    }

    /** 解析单段为整数；非数字返回 0。 */
    private static int parseSegment(String seg) {
        if (seg == null || seg.isEmpty()) {
            return 0;
        }
        String s = seg.trim();
        // 仅允许数字开头
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(s.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

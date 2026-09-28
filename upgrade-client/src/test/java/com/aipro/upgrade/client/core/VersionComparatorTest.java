package com.aipro.upgrade.client.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link VersionComparator} 单元测试。
 * 覆盖：基本比较、含 v 前缀、缺失段补 0、相等、非法输入。
 */
public class VersionComparatorTest {

    @Test
    public void compare_basicOrdering_1_2_0_greaterThan_1_1_0() {
        assertTrue(VersionComparator.greaterThan("1.2.0", "1.1.0"));
        assertFalse(VersionComparator.greaterThan("1.1.0", "1.2.0"));
    }

    @Test
    public void compare_patchHigher_1_0_0_lessThan_1_0_1() {
        assertTrue("1.0.0 应小于 1.0.1", VersionComparator.greaterThan("1.0.1", "1.0.0"));
        assertFalse("1.0.0 应小于 1.0.1", VersionComparator.greaterThan("1.0.0", "1.0.1"));
    }

    @Test
    public void compare_sameVersion_returnsZero() {
        assertEquals(0, VersionComparator.compare("1.0.0", "1.0.0"));
        assertTrue(VersionComparator.greaterOrEqual("1.0.0", "1.0.0"));
    }

    @Test
    public void compare_withVPrefix_treatedAsSameAsNoPrefix() {
        assertEquals(0, VersionComparator.compare("v1.2.0", "1.2.0"));
        assertEquals(0, VersionComparator.compare("V1.2.0", "1.2.0"));
        assertTrue(VersionComparator.greaterThan("v2.0.0", "1.9.9"));
    }

    @Test
    public void compare_missingSegments_paddedAsZero() {
        assertEquals(0, VersionComparator.compare("1.2", "1.2.0"));
        assertEquals(0, VersionComparator.compare("1", "1.0.0"));
        assertTrue(VersionComparator.greaterThan("1.2.1", "1.2"));
    }

    @Test
    public void compare_preReleaseSuffix_stripped() {
        // 1.2.0-rc1 与 1.2.0 视为相等
        assertEquals(0, VersionComparator.compare("1.2.0-rc1", "1.2.0"));
    }

    @Test
    public void compare_emptyAndNullInput_handlesGracefully() {
        assertEquals(0, VersionComparator.compare("", ""));
        assertEquals(0, VersionComparator.compare(null, null));
        // 任意合法版本号 > 非法版本号（视为 0.0.0）
        assertTrue(VersionComparator.greaterThan("0.0.1", ""));
    }

    @Test
    public void compare_buildMetadata_stripped() {
        // 1.2.0+build123 与 1.2.0 视为相等
        assertEquals(0, VersionComparator.compare("1.2.0+build123", "1.2.0"));
    }

    @Test
    public void compare_multiDigitMajor() {
        assertTrue(VersionComparator.greaterThan("2.0.0", "10.0.0") == false);
        assertTrue(VersionComparator.greaterThan("10.0.0", "9.99.99"));
    }
}

package com.huaxia.inventory.util;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class EpcNormalizerTest {

    @Test
    public void trimsWhitespace() {
        assertEquals("000000003500309099027210", EpcNormalizer.normalize(" 3500309099027210 "));
    }

    @Test
    public void accepts16Digits() {
        // 16 位原始码, 高位补 8 个 0
        assertEquals("000000003500309099027210", EpcNormalizer.normalize("3500309099027210"));
    }

    @Test
    public void accepts17Digits() {
        // 17 位原始码, 高位补 7 个 0
        assertEquals("000000010000000000000000", EpcNormalizer.normalize("10000000000000000"));
    }

    @Test
    public void trimsWhitespace17() {
        assertEquals("000000099999999999999999", EpcNormalizer.normalize(" 99999999999999999 "));
    }

    @Test
    public void accepts24Digits() {
        // 24 位已是规范形态, 原样返回
        assertEquals("000000003500309099027210",
                EpcNormalizer.normalize("000000003500309099027210"));
    }

    @Test
    public void acceptsPrePadded16Digits() {
        // 已经是 24 位 0 前缀的, 不二次补 0
        assertEquals("000000000000000000000000",
                EpcNormalizer.normalize("000000000000000000000000"));
    }

    @Test
    public void acceptsPrePadded17Digits() {
        // 24 位但前 7 位为 0 的也算规范形态
        assertEquals("000000010000000000000000",
                EpcNormalizer.normalize("000000010000000000000000"));
    }

    @Test
    public void rejectsNull() {
        assertNull(EpcNormalizer.normalize(null));
    }

    @Test
    public void rejectsEmpty() {
        assertNull(EpcNormalizer.normalize(""));
    }

    @Test
    public void rejectsShort() {
        assertNull(EpcNormalizer.normalize("350030909902721"));
    }

    @Test
    public void rejects15Digits() {
        assertNull(EpcNormalizer.normalize("999999999999999"));
    }

    @Test
    public void rejects18Digits() {
        assertNull(EpcNormalizer.normalize("350030909902721000"));
    }

    @Test
    public void rejects25Digits() {
        assertNull(EpcNormalizer.normalize("0000000035003090990272100"));
    }

    @Test
    public void rejectsLetters() {
        assertNull(EpcNormalizer.normalize("3500309099027ABC"));
    }

    @Test
    public void rejectsLettersAt17() {
        assertNull(EpcNormalizer.normalize("3500309099027210A"));
    }

    @Test
    public void rejectsHexChars() {
        // 即使看似 hex 也拒绝, 本合约只认十进制
        assertNull(EpcNormalizer.normalize("FFFFFFFFFFFFFFFF"));
    }

    @Test
    public void rejectsWhitespaceInside() {
        assertNull(EpcNormalizer.normalize("3500 309099027210"));
    }

    @Test
    public void toDisplayForm_stripsLeadingZerosFrom16DigitOrigin() {
        // 24 位 canonical (8 个 0 前缀 + 16 位原始码) → 16 位原始
        assertEquals("3500309099027210",
                EpcNormalizer.toDisplayForm("000000003500309099027210"));
    }

    @Test
    public void toDisplayForm_stripsLeadingZerosFrom17DigitOrigin() {
        // 24 位 canonical (7 个 0 前缀 + 17 位原始码) → 17 位原始
        assertEquals("35003090110722819",
                EpcNormalizer.toDisplayForm("000000035003090110722819"));
    }

    @Test
    public void toDisplayForm_handlesAllZeros() {
        // 全 0 → 保留一位 "0", 不能返回空串
        assertEquals("0",
                EpcNormalizer.toDisplayForm("000000000000000000000000"));
    }

    @Test
    public void toDisplayForm_passesThroughUnpadded() {
        // 没有前导 0 的 24 位串原样返回
        assertEquals("123456789012345678901234",
                EpcNormalizer.toDisplayForm("123456789012345678901234"));
    }

    @Test
    public void toDisplayForm_nullSafe() {
        assertNull(EpcNormalizer.toDisplayForm(null));
    }
}

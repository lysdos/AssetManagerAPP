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
    public void accepts16CharHexWithLetters() {
        // 2026-11 新合约: 16 位含字母 = 十六进制标签 EPC（旧合约会拒掉）
        assertEquals("3500309099027ABC", EpcNormalizer.normalize("3500309099027abc"));
    }

    @Test
    public void rejectsLettersAt17() {
        assertNull(EpcNormalizer.normalize("3500309099027210A"));
    }

    @Test
    public void acceptsAllFHexTag() {
        // 16 位全 F 是合法十六进制标签（旧合约会拒掉）
        assertEquals("FFFFFFFFFFFFFFFF", EpcNormalizer.normalize("FFFFFFFFFFFFFFFF"));
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

    // ---- 十六进制标签 EPC ----

    @Test
    public void accepts16CharHexTag() {
        // 真机实测的标签格式：64 bit EPC，含 A-F
        assertEquals("300833B200000055", EpcNormalizer.normalize("300833B200000055"));
    }

    @Test
    public void hexTag_isUppercased() {
        assertEquals("300833B200000055", EpcNormalizer.normalize("300833b200000055"));
        assertEquals("AA05002522B00046", EpcNormalizer.normalize(" aa05002522b00046 "));
    }

    @Test
    public void accepts24CharHexTag() {
        // 96 bit EPC
        assertEquals("E280116060000203A1B2C3D4",
                EpcNormalizer.normalize("e280116060000203a1b2c3d4"));
    }

    @Test
    public void rejectsHexOfWrongLength() {
        assertNull(EpcNormalizer.normalize("300833B2"));
        assertNull(EpcNormalizer.normalize("300833B200000055A"));
        assertNull(EpcNormalizer.normalize("300833B20000005512"));
    }

    @Test
    public void rejectsNonHexCharacters() {
        assertNull(EpcNormalizer.normalize("300833G200000055"));
        assertNull(EpcNormalizer.normalize("ZZ0833B200000055"));
    }

    @Test
    public void allDigitString_stillGoesDecimalBranch() {
        // BCD 编码的数字标签读回来与十进制写法相同，走十进制补 0 分支
        assertEquals("000000003500310014100167",
                EpcNormalizer.normalize("3500310014100167"));
    }

    @Test
    public void toDisplayForm_passesHexThroughUnchanged() {
        // 十六进制不能剥前导 0，否则 EPC 被破坏
        assertEquals("0A05002522B00046",
                EpcNormalizer.toDisplayForm("0A05002522B00046"));
        assertEquals("300833B200000055",
                EpcNormalizer.toDisplayForm("300833B200000055"));
    }

    @Test
    public void hexTag_roundTripsThroughDisplayForm() {
        String canonical = EpcNormalizer.normalize("300833b200000055");
        assertEquals("300833B200000055", EpcNormalizer.toDisplayForm(canonical));
        assertEquals(canonical, EpcNormalizer.normalize(EpcNormalizer.toDisplayForm(canonical)));
    }
}

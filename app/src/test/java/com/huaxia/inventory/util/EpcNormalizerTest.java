package com.huaxia.inventory.util;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class EpcNormalizerTest {

    @Test
    public void trimsWhitespace() {
        assertEquals("3500309099027210", EpcNormalizer.normalize(" 3500309099027210 "));
    }

    @Test
    public void accepts16Digits() {
        assertEquals("3500309099027210", EpcNormalizer.normalize("3500309099027210"));
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
    public void rejectsLong() {
        assertNull(EpcNormalizer.normalize("35003090990272100"));
    }

    @Test
    public void rejectsLetters() {
        assertNull(EpcNormalizer.normalize("3500309099027ABC"));
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
}

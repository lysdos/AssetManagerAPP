package com.huaxia.inventory.util;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class EpcNormalizerTest {

    @Test
    public void trimsWhitespace() {
        assertEquals("3034A1B2", EpcNormalizer.normalize(" 3034A1B2 "));
    }

    @Test
    public void uppercasesHex() {
        assertEquals("3034A1B2", EpcNormalizer.normalize("3034a1b2"));
    }

    @Test
    public void rejectsOddLength() {
        assertNull(EpcNormalizer.normalize("3034A1B"));
    }

    @Test
    public void rejectsNonHex() {
        assertNull(EpcNormalizer.normalize("3034G1B2"));
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
    public void accepts24Hex() {
        assertEquals("3034A1B23034A1B23034A1B2",
                EpcNormalizer.normalize("3034a1b23034a1b23034a1b2"));
    }
}

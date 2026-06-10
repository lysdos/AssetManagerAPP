package com.huaxia.inventory.util;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class EpcCodecTest {

    @Test
    public void decimalToHex_basic() {
        assertEquals("0C72C8A220BB2A6A", EpcCodec.toHex("3500309099027210"));
    }

    @Test
    public void decimalToHex_maxValue() {
        assertEquals("DE0B6B3A76400000", EpcCodec.toHex("9999999999999999"));
    }

    @Test
    public void decimalToHex_zero() {
        assertEquals("0000000000000000", EpcCodec.toHex("0000000000000000"));
    }

    @Test
    public void decimalToHex_rejectsNull() {
        assertNull(EpcCodec.toHex(null));
    }

    @Test
    public void decimalToHex_rejectsWrongLength() {
        assertNull(EpcCodec.toHex("12345"));
    }

    @Test
    public void hexToDecimal_basic() {
        assertEquals("3500309099027210", EpcCodec.fromHex("0C72C8A220BB2A6A"));
    }

    @Test
    public void hexToDecimal_maxValue() {
        assertEquals("9999999999999999", EpcCodec.fromHex("DE0B6B3A76400000"));
    }

    @Test
    public void hexToDecimal_zero() {
        assertEquals("0000000000000000", EpcCodec.fromHex("0000000000000000"));
    }

    @Test
    public void hexToDecimal_rejectsNull() {
        assertNull(EpcCodec.fromHex(null));
    }

    @Test
    public void hexToDecimal_rejectsNonHex() {
        assertNull(EpcCodec.fromHex("GGGGGGGGGGGGGGGG"));
    }

    @Test
    public void roundTrip_fiveValues() {
        String[] values = {
                "3500309099027210",
                "0000000000000001",
                "9999999999999999",
                "1234567890123456",
                "0000000000000000"
        };
        for (String d : values) {
            String hex = EpcCodec.toHex(d);
            String back = EpcCodec.fromHex(hex);
            assertEquals("roundTrip failed for " + d, d, back);
        }
    }
}

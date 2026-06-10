package com.huaxia.inventory.util;

import android.util.Log;

public final class EpcCodec {

    private static final String TAG = "EpcCodec";

    private EpcCodec() {}

    /** Convert a 16-digit decimal asset code to a 16-char uppercase hex
     *  string (8-byte big-endian integer). Returns null on invalid input. */
    public static String toHex(String decimal16) {
        if (decimal16 == null || decimal16.length() != 16 || !decimal16.matches("[0-9]+")) {
            Log.w(TAG, "invalid decimal asset code: " + decimal16);
            return null;
        }
        return String.format("%016X", Long.parseUnsignedLong(decimal16, 10));
    }

    /** Convert a 16-char hex string (8 bytes) to a 16-digit decimal asset code
     *  (zero-padded). Returns null on invalid input. */
    public static String fromHex(String hex) {
        if (hex == null || hex.length() != 16 || !hex.matches("[0-9A-Fa-f]+")) {
            Log.w(TAG, "invalid hex: " + hex);
            return null;
        }
        return String.format("%016d", Long.parseUnsignedLong(hex, 16));
    }
}

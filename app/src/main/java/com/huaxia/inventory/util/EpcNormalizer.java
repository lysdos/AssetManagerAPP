package com.huaxia.inventory.util;

import android.util.Log;

public final class EpcNormalizer {
    private static final String TAG = "EpcNormalizer";

    private EpcNormalizer() {}

    /**
     * Validate and normalize a raw asset-code string into its canonical form:
     * a 16-digit decimal string (the "固资编号" / unified asset code).
     *
     * @param raw input from Excel cell, RFID scan, or user entry
     * @return the trimmed 16-digit decimal string, or {@code null} if invalid
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() != 16) {
            Log.w(TAG, "asset code length != 16: " + s);
            return null;
        }
        if (!s.matches("[0-9]+")) {
            Log.w(TAG, "asset code not 16 decimal digits: " + s);
            return null;
        }
        return s;
    }
}

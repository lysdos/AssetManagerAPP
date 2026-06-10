package com.huaxia.inventory.util;

import android.util.Log;
import java.util.Locale;

public final class EpcNormalizer {
    private static final String TAG = "EpcNormalizer";

    private EpcNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (s.length() % 4 != 0) {
            Log.w(TAG, "EPC length not multiple of 4: " + s);
            return null;
        }
        if (!s.matches("[0-9A-F]+")) {
            Log.w(TAG, "EPC not hex: " + s);
            return null;
        }
        return s;
    }
}

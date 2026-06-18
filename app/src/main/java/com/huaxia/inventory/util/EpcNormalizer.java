package com.huaxia.inventory.util;

public final class EpcNormalizer {
    private EpcNormalizer() {}

    private static final int LEN_16 = 16;
    private static final int LEN_17 = 17;
    private static final int LEN_24 = 24;
    private static final String ZEROS_8 = "00000000";
    private static final String ZEROS_7 = "0000000";

    /**
     * Validate and normalize a raw asset-code string into its canonical form:
     * a 24-digit decimal string (the "固资编号" / unified asset code).
     *
     * <p>Accepts three input forms:</p>
     * <ul>
     *   <li>16-digit decimal → high-bit zero-padded with 8 leading zeros</li>
     *   <li>17-digit decimal → high-bit zero-padded with 7 leading zeros</li>
     *   <li>24-digit decimal → already canonical, returned as-is</li>
     * </ul>
     *
     * @param raw input from Excel cell, RFID scan, or user entry
     * @return the canonical 24-digit decimal string, or {@code null} if invalid
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (!s.matches("[0-9]+")) return null;
        int len = s.length();
        switch (len) {
            case LEN_16: return ZEROS_8 + s;
            case LEN_17: return ZEROS_7 + s;
            case LEN_24: return s;
            default:     return null;
        }
    }

    /**
     * Convert a canonical 24-digit form back to its display form by stripping
     * the high-bit zero-padding. Used by the UI to show the original 16/17-digit
     * code that the customer sees in their spreadsheets.
     *
     * <p>This is a display-only transformation — DB writes must continue to use
     * the canonical 24-digit form via {@link #normalize(String)} so that joins
     * with {@code scan_record} and import/export stay consistent.</p>
     *
     * @param canonical the canonical 24-digit decimal string; may be {@code null}
     * @return the unpadded decimal string, or {@code null} if input is {@code null};
     *         all-zero input returns {@code "0"}
     */
    public static String toDisplayForm(String canonical) {
        if (canonical == null) return null;
        String stripped = canonical.replaceFirst("^0+", "");
        return stripped.isEmpty() ? "0" : stripped;
    }
}

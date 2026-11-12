package com.huaxia.inventory.util;

import java.util.Locale;

public final class EpcNormalizer {
    private EpcNormalizer() {}

    private static final int LEN_16 = 16;
    private static final int LEN_17 = 17;
    private static final int LEN_24 = 24;
    private static final String ZEROS_8 = "00000000";
    private static final String ZEROS_7 = "0000000";

    /**
     * Validate and normalize a raw asset-code string into its canonical form.
     *
     * <p>接受两类输入：</p>
     *
     * <p><b>十进制固资编号</b>（Excel 导入的主格式）→ 归一化为 24 位十进制：</p>
     * <ul>
     *   <li>16 位 → 高位补 8 个 0</li>
     *   <li>17 位 → 高位补 7 个 0</li>
     *   <li>24 位 → 已规范，原样返回</li>
     * </ul>
     *
     * <p><b>十六进制标签 EPC</b>（RFID 标签上的值，如 {@code 300833B200000055}）
     * → 原样保存，仅统一转大写：</p>
     * <ul>
     *   <li>16 位 hex（64 bit EPC）</li>
     *   <li>24 位 hex（96 bit EPC）</li>
     * </ul>
     *
     * <p>十六进制<b>不做十进制换算</b>：标签上的 EPC 与台账「固资编号」是同一个字符串，
     * 两侧都过本方法即可匹配。历史实现里曾有 {@code EpcCodec}（十进制 ↔ 8 字节 hex），
     * 已于 2026-06-17 删除；实测标签值（{@code 300833B200000055} 换算成十进制是 19 位）
     * 与 16 位固资编号不存在换算关系，那条路径不成立。</p>
     *
     * <p>注意：全数字的 16/24 位串一律走十进制分支（BCD 编码的数字标签读回来与十进制
     * 写法完全相同，两种解释结果一致），只有含 {@code A-F} 的串才判定为十六进制。</p>
     *
     * @param raw input from Excel cell, RFID scan, or user entry
     * @return 规范形态（24 位十进制 或 16/24 位大写十六进制），非法返回 {@code null}
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        if (s.matches("[0-9]+")) {
            switch (s.length()) {
                case LEN_16: return ZEROS_8 + s;
                case LEN_17: return ZEROS_7 + s;
                case LEN_24: return s;
                default:     return null;
            }
        }

        String upper = s.toUpperCase(Locale.ROOT);
        if (upper.matches("[0-9A-F]{16}") || upper.matches("[0-9A-F]{24}")) {
            return upper;
        }
        return null;
    }

    /**
     * Convert a canonical form back to what the customer sees in their spreadsheets.
     *
     * <p>十进制规范形态是 24 位高位补 0 的串，显示时剥掉前导 0 还原成原始的
     * 16/17 位固资编号；<b>十六进制形态原样返回</b>（剥前导 0 会破坏 EPC）。</p>
     *
     * <p>This is a display-only transformation — DB writes must continue to use
     * the canonical form via {@link #normalize(String)} so that joins with
     * {@code scan_record} and import/export stay consistent.</p>
     *
     * @param canonical 规范形态；可为 {@code null}
     * @return 十进制剥掉前导 0 的串（全 0 返回 {@code "0"}），十六进制原样返回
     */
    public static String toDisplayForm(String canonical) {
        if (canonical == null) return null;
        if (!canonical.matches("[0-9]+")) return canonical;
        String stripped = canonical.replaceFirst("^0+", "");
        return stripped.isEmpty() ? "0" : stripped;
    }
}

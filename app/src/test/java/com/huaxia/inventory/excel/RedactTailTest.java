package com.huaxia.inventory.excel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * 防回归: {@link ExcelExporter#redactTail(String)} 必须真的脱敏 —— 只保留末 4 位,
 * 前面全部替换为 {@code **}。旧实现保留了前 len-4 位明文, 原始值可完整还原,
 * 导致 3 处调用方 (ResultFragment / BarcodeCameraController / ScanViewModel)
 * 一直在"假脱敏"。
 */
public class RedactTailTest {

    @Test
    public void redactTail_hidesHead_andKeepsOnlyLast4() {
        String code = "3500309099027210";
        String redacted = ExcelExporter.redactTail(code);
        assertEquals("**7210", redacted);
        assertFalse("must NOT leak the head of the code", redacted.contains("350030909902"));
    }

    @Test
    public void redactTail_shortAndEmptyInputs() {
        assertEquals("<empty>", ExcelExporter.redactTail(null));
        assertEquals("<empty>", ExcelExporter.redactTail(""));
        // 长度 <= 4 时末 4 位即整串, 前面加 ** — 极短输入无隐私可言, 行为确定即可
        assertEquals("**abcd", ExcelExporter.redactTail("abcd"));
    }
}

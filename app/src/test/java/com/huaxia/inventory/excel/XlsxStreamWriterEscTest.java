package com.huaxia.inventory.excel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 防回归: {@link XlsxStreamWriter#esc} 必须丢弃 XML 1.0 非法控制字符 — 否则源单元格里
 * 的脏字符会让整个 sheet1.xml 非法, 导出的 xlsx 打不开。
 *
 * <p>测试输入一律用 {@code (char) 0x..} 构造, 不在源码里塞裸控制字符 / 转义序列。</p>
 */
public class XlsxStreamWriterEscTest {

    @Test
    public void esc_escapesMarkupChars() {
        String input = "a&b<c>d" + (char) 0x22 + "e" + (char) 0x27 + "f";
        assertEquals("a&amp;b&lt;c&gt;d&quot;e&apos;f", XlsxStreamWriter.esc(input));
    }

    @Test
    public void esc_dropsXmlIllegalControlChars() {
        // 0x01 / 0x08 / 0x0B / 0x1F 均非法 → 丢弃
        String illegal = "a" + (char) 0x01 + "b" + (char) 0x08 + (char) 0x0B + (char) 0x1F;
        assertEquals("ab", XlsxStreamWriter.esc(illegal));
    }

    @Test
    public void esc_keepsLegalWhitespace() {
        // 0x09(tab) / 0x0A(LF) / 0x0D(CR) 在 XML 1.0 中合法 → 保留
        String ws = "a" + (char) 0x09 + "b" + (char) 0x0A + "c" + (char) 0x0D + "d";
        assertEquals(ws, XlsxStreamWriter.esc(ws));
    }
}

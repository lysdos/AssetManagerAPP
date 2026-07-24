package com.huaxia.inventory.excel;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: ExcelExporter 的 rawRowJson 反序列化必须用字段化的
 * {@code ObjectReader RAW_ROW_READER} — 让 5w 行主循环的 readValue 走
 * Jackson 内部缓存的 deserializer, 每次省去 deserializer 反射初始化.
 * 修复前调用 {@code MAPPER.readValue(...)} (直接传 Map.class), 每行都触发
 * 类型解析 + JsonDeserializer 查找.
 *
 * <p>配套整改: writeAssetRow 必须用 {@code RAW_ROW_READER.readValue(...)}
 * 调用, 不再走 raw {@code MAPPER.readValue}.</p>
 */
public class ExcelExporterObjectReaderTest {

    @Test
    public void excelExporter_usesReusedReaderField() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/excel/ExcelExporter.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/excel/ExcelExporter.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 字段声明: private static final ObjectReader RAW_ROW_READER
        assertTrue("ExcelExporter must declare a static final ObjectReader field. "
                + "Source:\n" + text,
                text.contains("private static final ObjectReader RAW_ROW_READER"));
        assertTrue("RAW_ROW_READER must be initialized once via MAPPER.readerFor(...). "
                + "Source:\n" + text,
                text.contains("MAPPER.readerFor(Map.class)"));

        // 写循环内必须用 RAW_ROW_READER.readValue, 不再 MAPPER.readValue(Map.class)
        String writeRowBody = extractMethodBody(text,
                "private static void writeAssetRow(");
        assertTrue("writeAssetRow body not found", writeRowBody.length() > 0);
        assertTrue("writeAssetRow must use RAW_ROW_READER.readValue (cached reader). "
                + "Body:\n" + writeRowBody,
                writeRowBody.contains("RAW_ROW_READER.readValue"));
        assertFalse("writeAssetRow must NOT call raw MAPPER.readValue(Map.class) per "
                + "row anymore — deserializer init fires on every call. Body:\n"
                + writeRowBody,
                writeRowBody.contains("MAPPER.readValue(")
                        && writeRowBody.contains("Map.class"));
    }

    private static String extractMethodBody(String text, String signature) {
        int sigStart = text.indexOf(signature);
        if (sigStart < 0) return "";
        int braceStart = text.indexOf('{', sigStart);
        if (braceStart < 0) return "";
        int depth = 1;
        int i = braceStart + 1;
        while (i < text.length() && depth > 0) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        if (depth != 0) return "";
        return text.substring(braceStart + 1, i - 1);
    }
}

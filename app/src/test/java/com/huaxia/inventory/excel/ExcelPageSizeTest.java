package com.huaxia.inventory.excel;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: ExcelExporter.STREAM_PAGE_SIZE 必须 {@code <= 500}.
 * <p>单次 {@code ExtraClaimDao.findByScanRecordIds(IN (pageIds))} 的 IN 上限
 * 由 SQLITE_MAX_VARIABLE_NUMBER 决定, Android Room 默认 binding 上限 ≈ 999.
 * 修复前为 1000 — 导出第 2 页盘盈 (>999 条) 时 Room 会静默丢 binding 或直接抛
 * "too many SQL variables" — 1w 行任务导出大概率崩.</p>
 */
public class ExcelPageSizeTest {

    @Test
    public void streamPageSize_isAtMost500() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/excel/ExcelExporter.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/excel/ExcelExporter.java");
        }
        assertTrue("ExcelExporter.java not found: " + source.getAbsolutePath(),
                source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 必须保留 STREAM_PAGE_SIZE = 500 声明
        assertTrue("ExcelExporter.STREAM_PAGE_SIZE must be 500 (was 1000, crashes on "
                + ">999 IN clause in findByScanRecordIds). Source:\n" + text,
                text.contains("STREAM_PAGE_SIZE = 500"));
    }
}

package com.huaxia.inventory.excel;

import com.huaxia.inventory.data.entity.Asset;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExcelImporterStreamTest {

    @Test
    public void stream_yieldsBatchesInOrder() throws Exception {
        String[] headers = {
                "序号", "设备类型", "设备型号", "序列号",
                "固资编号", "领用部门", "领用人", "盘点日期"
        };
        // 7 行有效，batch=3 应触发 3 次回调（3+3+1）
        String[][] rows = new String[7][];
        for (int i = 0; i < 7; i++) {
            rows[i] = new String[]{
                    String.valueOf(i + 1), "X", "M", "D" + i,
                    String.format("%016d", 3500309099000000L + i), "O", "L", "P"
            };
        }

        final List<Integer> batchSizes = new ArrayList<>();
        final List<Asset> all = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        try (java.io.InputStream in = buildXlsx(headers, rows)) {
            ExcelImporter.stream(in, /* taskId */ 1L, /* batchSize */ 3,
                    new ExcelImporter.BatchHandler() {
                        @Override public void onBatch(List<Asset> batch) {
                            batchSizes.add(batch.size());
                            all.addAll(batch);
                        }
                        @Override public void onError(String message) {
                            errors.add(message);
                        }
                    });
        }

        assertEquals(3, batchSizes.size());
        assertEquals(Integer.valueOf(3), batchSizes.get(0));
        assertEquals(Integer.valueOf(3), batchSizes.get(1));
        assertEquals(Integer.valueOf(1), batchSizes.get(2));
        assertEquals(7, all.size());
        assertTrue(errors.isEmpty());
        // 顺序保留 (24 位规范形态)
        assertEquals("000000003500309099000000", all.get(0).epc);
        assertEquals("000000003500309099000006", all.get(6).epc);
        // 全部带上 taskId
        for (Asset a : all) assertEquals(1L, a.taskId);
    }

    @Test
    public void stream_collectsErrorsWithoutHalting() throws Exception {
        String[] headers = {
                "序号", "设备类型", "设备型号", "序列号",
                "固资编号", "领用部门", "领用人", "盘点日期"
        };
        String[][] rows = {
                {"1", "X", "X", "OK1", "3500309099027210", "X", "X", "X"},
                {"2", "X", "X", "BAD_LEN", "3500309", "X", "X", "X"},      // 短
                {"3", "X", "X", "OK2", "3500309019310435", "X", "X", "X"},
                {"4", "X", "X", "BAD_EMPTY", "", "X", "X", "X"}            // 空
        };
        final List<Asset> all = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        try (java.io.InputStream in = buildXlsx(headers, rows)) {
            ExcelImporter.stream(in, 1L, 100, new ExcelImporter.BatchHandler() {
                @Override public void onBatch(List<Asset> batch) { all.addAll(batch); }
                @Override public void onError(String message) { errors.add(message); }
            });
        }
        assertEquals(2, all.size());          // OK1, OK2
        assertEquals(2, errors.size());       // BAD_LEN, BAD_EMPTY
    }

    @Test
    public void parse_isCompatibleWrapperOverStream() throws Exception {
        // parse() 内部走 stream()，应保持旧行为
        String[] headers = {
                "序号", "设备类型", "设备型号", "序列号",
                "固资编号", "领用部门", "领用人", "盘点日期"
        };
        String[][] rows = {
                {"1", "X", "X", "P1", "3500309099027210", "X", "X", "X"}
        };
        try (java.io.InputStream in = buildXlsx(headers, rows)) {
            ExcelImporter.Result r = ExcelImporter.parse(in, 1L);
            assertEquals(1, r.success);
            assertEquals(0, r.errors.size());
            assertEquals("000000003500309099027210", r.assets.get(0).epc);
        }
    }

    private static java.io.InputStream buildXlsx(String[] headers, String[][] dataRows) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet();
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("固定资产 - 设备台账");
            Row meta = sheet.createRow(1);
            meta.createCell(0).setCellValue("制表人");
            Row headerRow = sheet.createRow(2);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }
            for (int r = 0; r < dataRows.length; r++) {
                Row row = sheet.createRow(3 + r);
                for (int c = 0; c < dataRows[r].length; c++) {
                    row.createCell(c).setCellValue(dataRows[r][c]);
                }
            }
            wb.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }
}

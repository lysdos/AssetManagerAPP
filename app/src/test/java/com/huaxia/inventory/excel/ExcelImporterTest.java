package com.huaxia.inventory.excel;

import com.huaxia.inventory.data.entity.Asset;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ExcelImporterTest {

    @Test
    public void parsesSampleFile_allValid() throws Exception {
        // sample-assets.xlsx 实际含 131 条 16/17 位有效码 (asset 列已对齐, 24 位规范后入库)
        try (java.io.InputStream in = getClass().getResourceAsStream("/sample-assets.xlsx")) {
            ExcelImporter.Result r = ExcelImporter.parse(in, /* taskId */ 1L);
            assertEquals(131, r.success);
            assertTrue("Expected zero errors, got " + r.errors.size(), r.errors.isEmpty());
        }
    }

    @Test
    public void rejectsNonExcel() {
        java.io.InputStream bad = new ByteArrayInputStream("not a zip".getBytes());
        assertThrows(Exception.class, () -> ExcelImporter.parse(bad, 1L));
    }

    @Test
    public void parsesValidAssetCodes() throws Exception {
        // 程序化构造一个含 2 行合法固资编号的 xlsx
        String[] headers = {
                "序号", "设备类型", "设备型号", "序列号",
                "固资编号", "领用部门", "领用人", "盘点日期"
        };
        String[][] rows = {
                {"1", "X", "X", "X", "3500309099027210", "X", "X", "X"},
                {"2", "X", "X", "X", "3500309019310435", "X", "X", "X"}
        };
        try (java.io.InputStream in = buildXlsx(headers, rows)) {
            ExcelImporter.Result r = ExcelImporter.parse(in, 1L);
            assertEquals(2, r.success);
            assertEquals(0, r.errors.size());
            List<Asset> assets = r.assets;
            assertEquals("000000003500309099027210", assets.get(0).epc);
            assertEquals("000000003500309019310435", assets.get(1).epc);
        }
    }

    @Test
    public void rejectsInvalidAssetCodes() throws Exception {
        String[] headers = {
                "序号", "设备类型", "设备型号", "序列号",
                "固资编号", "领用部门", "领用人", "盘点日期"
        };
        String[][] rows = {
                {"1", "X", "X", "X", "3500309099027210", "X", "X", "X"},
                {"2", "X", "X", "X", "350030909902721", "X", "X", "X"},         // 15 位
                {"3", "X", "X", "X", "3500309099027210A", "X", "X", "X"},       // 17 字符含字母
                {"4", "X", "X", "X", "3500309099ABC210", "X", "X", "X"},        // 16 位 hex 标签, 合法
                {"5", "X", "X", "X", "", "X", "X", "X"}                          // 空
        };
        try (java.io.InputStream in = buildXlsx(headers, rows)) {
            ExcelImporter.Result r = ExcelImporter.parse(in, 1L);
            // 2026-11 起 16 位十六进制标签合法 → 第 4 行也成功
            assertEquals(2, r.success);
            assertEquals(3, r.errors.size());
        }
    }

    @Test
    public void parsesValidAssetCodes_mixed16And17() throws Exception {
        String[] headers = {
                "序号", "设备类型", "设备型号", "序列号",
                "固资编号", "领用部门", "领用人", "盘点日期"
        };
        String[][] rows = {
                {"1", "X", "X", "X", "3500309099027210", "X", "X", "X"},          // 16 位
                {"2", "X", "X", "X", "10000000000000000", "X", "X", "X"},         // 17 位
                {"3", "X", "X", "X", "99999999999999999", "X", "X", "X"}          // 17 位 max
        };
        try (java.io.InputStream in = buildXlsx(headers, rows)) {
            ExcelImporter.Result r = ExcelImporter.parse(in, 1L);
            assertEquals(3, r.success);
            assertEquals(0, r.errors.size());
            List<Asset> assets = r.assets;
            assertEquals("000000003500309099027210", assets.get(0).epc);
            assertEquals(24, assets.get(0).epc.length());
            assertEquals("000000010000000000000000", assets.get(1).epc);
            assertEquals(24, assets.get(1).epc.length());
            assertEquals("000000099999999999999999", assets.get(2).epc);
            assertEquals(24, assets.get(2).epc.length());
        }
    }

    /**
     * 固资编号列若被存成"数字"格式(用户没设成文本), 旧实现 {@code String.valueOf(double)}
     * 会得到 "3.50030909902721E15" → EpcNormalizer 拒掉 → 整行丢失.
     * 修复: 用 NumberToTextConverter 转成纯十进制文本.
     */
    @Test
    public void parsesNumericCellAssetCode_noScientificNotation() throws Exception {
        String[] headers = {
                "序号", "设备类型", "设备型号", "序列号",
                "固资编号", "领用部门", "领用人", "盘点日期"
        };
        try (java.io.InputStream in = buildXlsxWithNumericEpc(headers, 3500309099027210L)) {
            ExcelImporter.Result r = ExcelImporter.parse(in, 1L);
            assertEquals(1, r.success);
            assertEquals(0, r.errors.size());
            assertEquals("000000003500309099027210", r.assets.get(0).epc);
        }
    }

    /**
     * 17 位数 (1e16) 已超 double 精确整数上限 2^53≈9.0e15, 低位不可信 → 必须报错,
     * 不能被 NumberToTextConverter 截出一个"看似合法"的 17 位串静默入库。
     */
    @Test
    public void rejectsNumericCellBeyond2Pow53() throws Exception {
        String[] headers = {
                "序号", "设备类型", "设备型号", "序列号",
                "固资编号", "领用部门", "领用人", "盘点日期"
        };
        try (java.io.InputStream in = buildXlsxWithNumericEpc(headers, 10000000000000000L)) {
            ExcelImporter.Result r = ExcelImporter.parse(in, 1L);
            assertEquals(0, r.success);
            assertEquals(1, r.errors.size());
        }
    }

    /**
     * onBatch 抛异常(模拟 DB 提交失败)必须向上传播、中止导入, <b>不能</b>被 per-row catch
     * 降级成 onError 后继续 — 否则中途失败会被当"导入成功"上报.
     */
    @Test
    public void onBatchFailure_propagates_andAbortsImport() throws Exception {
        String[] headers = {
                "序号", "设备类型", "设备型号", "序列号",
                "固资编号", "领用部门", "领用人", "盘点日期"
        };
        String[][] rows = {
                {"1", "X", "X", "X", "3500309099027210", "X", "X", "X"},
                {"2", "X", "X", "X", "3500309019310435", "X", "X", "X"}
        };
        boolean[] errorCalled = {false};
        try (java.io.InputStream in = buildXlsx(headers, rows)) {
            assertThrows(RuntimeException.class, () -> ExcelImporter.stream(in, 1L, /* batchSize */ 1,
                    new ExcelImporter.BatchHandler() {
                        @Override public void onBatch(List<Asset> batch) {
                            throw new RuntimeException("db commit failed");
                        }
                        @Override public void onError(String message) { errorCalled[0] = true; }
                    }));
        }
        assertFalse("onBatch 失败不应被降级成 per-row onError", errorCalled[0]);
    }

    private static java.io.InputStream buildXlsxWithNumericEpc(String[] headers, long epcNumeric)
            throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet();
            // 与 buildXlsx 同结构: row0 标题 / row1 meta / row2 表头 / row3 数据
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("固定资产 - 设备台账");
            Row meta = sheet.createRow(1);
            meta.createCell(0).setCellValue("制表人");
            Row headerRow = sheet.createRow(2);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }
            Row row = sheet.createRow(3);
            row.createCell(0).setCellValue("1");
            // 固资编号列 (索引 4) 以数字格式写入
            row.createCell(4).setCellValue((double) epcNumeric);
            wb.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    private static java.io.InputStream buildXlsx(String[] headers, String[][] dataRows) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet();
            // 标题行 (会被解析器跳过)
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("固定资产 - 设备台账");
            // meta 行 (会被跳过)
            Row meta = sheet.createRow(1);
            meta.createCell(0).setCellValue("制表人");
            meta.createCell(3).setCellValue("2026-06-10");
            // 表头行
            Row headerRow = sheet.createRow(2);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }
            // 数据行
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

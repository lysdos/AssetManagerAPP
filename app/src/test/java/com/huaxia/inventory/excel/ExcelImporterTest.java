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
                "序号", "设备名称", "设备类型", "设备型号", "所属机构",
                "详细地点", "放置地点", "资产状态", "固资编号"
        };
        String[][] rows = {
                {"1", "DPM901", "RFID设备", "DPM901", "运营管理部",
                        "前台", "可放置", "在用", "3500309099027210"},
                {"2", "UPS电池", "电池", "6-FML-100", "信息科技部",
                        "支行", "可放置", "在用", "3500309019310435"}
        };
        try (java.io.InputStream in = buildXlsx(headers, rows)) {
            ExcelImporter.Result r = ExcelImporter.parse(in, 1L);
            assertEquals(2, r.success);
            assertEquals(0, r.errors.size());
            List<Asset> assets = r.assets;
            assertEquals("000000003500309099027210", assets.get(0).epc);
            assertEquals("DPM901", assets.get(0).assetName);
            assertEquals("000000003500309019310435", assets.get(1).epc);
        }
    }

    @Test
    public void rejectsInvalidAssetCodes() throws Exception {
        String[] headers = {
                "序号", "设备名称", "设备类型", "设备型号", "所属机构",
                "详细地点", "放置地点", "资产状态", "固资编号"
        };
        String[][] rows = {
                {"1", "X", "X", "X", "X", "X", "X", "X", "3500309099027210"},
                {"2", "X", "X", "X", "X", "X", "X", "X", "350030909902721"},   // 15 位
                {"3", "X", "X", "X", "X", "X", "X", "X", "3500309099027210A"},  // 17 字符含字母
                {"4", "X", "X", "X", "X", "X", "X", "X", "3500309099ABC210"},  // 含字母
                {"5", "X", "X", "X", "X", "X", "X", "X", ""}                    // 空
        };
        try (java.io.InputStream in = buildXlsx(headers, rows)) {
            ExcelImporter.Result r = ExcelImporter.parse(in, 1L);
            assertEquals(1, r.success);
            assertEquals(4, r.errors.size());
        }
    }

    @Test
    public void parsesValidAssetCodes_mixed16And17() throws Exception {
        String[] headers = {
                "序号", "设备名称", "设备类型", "设备型号", "所属机构",
                "详细地点", "放置地点", "资产状态", "固资编号"
        };
        String[][] rows = {
                {"1", "DPM901", "RFID设备", "DPM901", "运营管理部",
                        "前台", "可放置", "在用", "3500309099027210"},          // 16 位
                {"2", "UPS电池", "电池", "6-FML-100", "信息科技部",
                        "支行", "可放置", "在用", "10000000000000000"},          // 17 位
                {"3", "服务器", "服务器", "PowerEdge", "信息科技部",
                        "机房", "机柜", "在用", "99999999999999999"}             // 17 位 max
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

package com.huaxia.inventory.excel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ScanRecord;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ExcelExporterTest {

    @Test
    public void exportsAllFoundAndMissingAndExtras() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Asset> assets = Arrays.asList(
                asset(1L, "EPC1", "已盘"),
                asset(2L, "EPC2", "盘亏"));
        List<ScanRecord> scans = Arrays.asList(
                scan(1L, "EPC1", 100L),
                scan(3L, "EPC3", 101L));  // EPC3 has no asset = EXTRA
        // 只用原始列，RESULT_COLUMNS（盘点状态/时间/强度）由导出器自动追加
        List<String> headers = Arrays.asList("序号", "设备名称", "固资编号");

        ExcelExporter.export(out, "测试任务", headers, assets, scans, id -> id == 1L);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet s = wb.getSheetAt(0);
            // 实际行布局：0=title, 1=meta, 2=header, 3..=data
            assertNotNull(s.getRow(2));   // header row
            assertNotNull(s.getRow(3));   // first data row
            // 盘点状态列在 base = originalHeaders.size() = 3
            int statusCol = headers.size();
            boolean foundFound = false, foundMissing = false;
            for (int r = 3; r <= 4; r++) {
                Row row = s.getRow(r);
                if (row == null) continue;
                String status = row.getCell(statusCol).getStringCellValue();
                if ("已盘".equals(status)) foundFound = true;
                if ("盘亏".equals(status)) foundMissing = true;
            }
            assertTrue(foundFound);
            assertTrue(foundMissing);
        }
    }

    @Test
    public void roundTripsRawRowJson() throws Exception {
        // Build a synthetic workbook with one row, export it, re-parse, ensure data preserved
        // 用 LinkedHashMap 保证列顺序，避免 HashMap 随机顺序导致测试 flaky
        Map<String, String> raw = new java.util.LinkedHashMap<>();
        raw.put("设备名称", "测试设备");
        raw.put("固资编号", "3500309099027210");
        String json = new ObjectMapper().writeValueAsString(raw);

        Asset a = new Asset();
        a.id = 1L;
        a.taskId = 1L;
        a.excelRowIndex = 4;
        a.epc = "3500309099027210";
        a.rawRowJson = json;

        List<String> headers = new ArrayList<>(raw.keySet());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelExporter.export(out, "任务", headers, Arrays.asList(a), new ArrayList<>(), id -> true);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet s = wb.getSheetAt(0);
            // 数据行在 row 3（不是 4）：0/1/2 已被 title/meta/header 占用
            Row row = s.getRow(3);
            assertEquals("测试设备", row.getCell(0).getStringCellValue());
            assertEquals("3500309099027210", row.getCell(1).getStringCellValue());
        }
    }

    private Asset asset(long id, String epc, String name) {
        Asset a = new Asset();
        a.id = id;
        a.taskId = 1L;
        a.excelRowIndex = (int) id + 3;
        a.epc = epc;
        a.rawRowJson = "{}";
        return a;
    }

    private ScanRecord scan(long matchedAssetId, String epc, long ts) {
        ScanRecord r = new ScanRecord();
        r.taskId = 1L;
        r.epc = epc;
        r.scannedAt = ts;
        r.rssi = "-50";
        r.matchedAssetId = matchedAssetId;
        return r;
    }
}

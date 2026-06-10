package com.huaxia.inventory.excel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.util.DateUtil;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class ExcelExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final List<String> RESULT_COLUMNS = Arrays.asList(
            "盘点状态", "盘点时间", "信号强度");

    private ExcelExporter() {}

    public static void export(
            OutputStream out,
            String taskName,
            List<String> originalHeaders,
            List<Asset> assets,
            List<ScanRecord> scans,
            java.util.function.LongPredicate isFound) throws IOException {

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("盘点结果");

            // Row 0: title
            Row r0 = sheet.createRow(0);
            r0.createCell(0).setCellValue("固定资产盘点结果 - " + taskName);

            // Row 1: meta
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("导出时间:");
            r1.createCell(1).setCellValue(DateUtil.formatTimestamp(System.currentTimeMillis()));

            // Row 2: header
            List<String> fullHeaders = new ArrayList<>(originalHeaders);
            fullHeaders.addAll(RESULT_COLUMNS);
            Row r2 = sheet.createRow(2);
            for (int i = 0; i < fullHeaders.size(); i++) {
                r2.createCell(i).setCellValue(fullHeaders.get(i));
            }

            // Rows 3..: assets
            int rowIdx = 3;
            for (Asset a : assets) {
                Row row = sheet.createRow(rowIdx++);
                writeAssetRow(row, a, originalHeaders, isFound);
            }

            // Extra section
            List<ScanRecord> extras = new ArrayList<>();
            for (ScanRecord s : scans) {
                if (s.matchedAssetId == null) extras.add(s);
            }
            if (!extras.isEmpty()) {
                rowIdx++;  // blank row
                Row rTitle = sheet.createRow(rowIdx++);
                rTitle.createCell(0).setCellValue("【盘盈记录】");
                Row rHeader = sheet.createRow(rowIdx++);
                for (int i = 0; i < fullHeaders.size(); i++) {
                    rHeader.createCell(i).setCellValue(fullHeaders.get(i));
                }
                for (ScanRecord s : extras) {
                    Row row = sheet.createRow(rowIdx++);
                    // original 35 cols left blank
                    int base = fullHeaders.size() - RESULT_COLUMNS.size();
                    row.createCell(base).setCellValue("盘盈");
                    row.createCell(base + 1).setCellValue(DateUtil.formatTimestamp(s.scannedAt));
                    row.createCell(base + 2).setCellValue(s.rssi == null ? "" : s.rssi);
                }
            }

            wb.write(out);
        }
    }

    private static void writeAssetRow(Row row, Asset a, List<String> originalHeaders,
                                       java.util.function.LongPredicate isFound) {
        // Restore original 35 columns from rawRowJson map
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> raw = MAPPER.readValue(
                    a.rawRowJson == null ? "{}" : a.rawRowJson, Map.class);
            for (int i = 0; i < originalHeaders.size(); i++) {
                String v = raw.get(originalHeaders.get(i));
                Cell cell = row.createCell(i);
                if (v != null) cell.setCellValue(v);
            }
        } catch (Exception e) {
            // Fallback: write whatever indexed fields we have
            row.createCell(0).setCellValue(a.excelRowIndex);
            if (a.assetName != null) row.createCell(1).setCellValue(a.assetName);
        }
        int base = originalHeaders.size();
        String status = isFound.test(a.id) ? "已盘" : "盘亏";
        row.createCell(base).setCellValue(status);
        row.createCell(base + 1).setCellValue("");
        row.createCell(base + 2).setCellValue("");
    }
}

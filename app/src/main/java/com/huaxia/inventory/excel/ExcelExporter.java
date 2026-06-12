package com.huaxia.inventory.excel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaxia.inventory.data.dao.AssetDao;
import com.huaxia.inventory.data.dao.ScanRecordDao;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.util.DateUtil;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ExcelExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final List<String> RESULT_COLUMNS = Arrays.asList(
            "盘点状态", "盘点时间", "信号强度");

    /** 流式导出的分页大小。经验值：1000 行 ≈ 5-10MB 堆，1s 内写完。 */
    public static final int STREAM_PAGE_SIZE = 1000;
    /** SXSSF 滑动窗口 — 仅最近 N 行驻留堆。 */
    private static final int SXSSF_WINDOW = 100;

    private ExcelExporter() {}

    /** 旧 API：全量入参，适合测试和小数据集。 */
    public static void export(
            OutputStream out,
            String taskName,
            List<String> originalHeaders,
            List<Asset> assets,
            List<ScanRecord> scans,
            java.util.function.LongPredicate isFound) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("盘点结果");
            writeTitle(sheet, taskName);
            writeHeaders(sheet, originalHeaders);

            int rowIdx = 3;
            for (Asset a : assets) {
                Row row = sheet.createRow(rowIdx++);
                writeAssetRow(row, a, originalHeaders, isFound);
            }
            List<ScanRecord> extras = new ArrayList<>();
            for (ScanRecord s : scans) {
                if (s.matchedAssetId == null) extras.add(s);
            }
            if (!extras.isEmpty()) {
                rowIdx++;
                rowIdx = writeExtrasHeader(sheet, rowIdx, originalHeaders);
                for (ScanRecord s : extras) {
                    Row row = sheet.createRow(rowIdx++);
                    writeExtraRow(row, originalHeaders, s);
                }
            }
            wb.write(out);
        }
    }

    /**
     * 流式导出：从 DAO 按页读取 + 立即写入。峰值内存 ≈ pageSize * Asset 大小，
     * 不再把 5w+ 行全表留在堆里，规避 {@code findAllByTask} 的 OOM 风险。
     *
     * <p>实现要点：
     * <ul>
     *   <li>使用 {@link SXSSFWorkbook} 替代 {@link XSSFWorkbook}，仅最近 100 行驻留堆</li>
     *   <li>资产按 {@code pageAll(taskId, pageSize, offset)} 分页读</li>
     *   <li>已匹配资产 ID 集合全量读一次（HashSet 体量与匹配数成正比，远小于全表）</li>
     *   <li>盘盈按 {@code pageExtra(taskId, pageSize, offset)} 分页读</li>
     * </ul>
     */
    public static void exportStreamed(
            OutputStream out,
            String taskName,
            List<String> originalHeaders,
            AssetDao assetDao,
            ScanRecordDao scanRecordDao,
            long taskId) throws IOException {

        // SXSSFWorkbook 必须包装一个 XSSFWorkbook（提供样式/共享字符串表）
        try (SXSSFWorkbook wb = new SXSSFWorkbook(new XSSFWorkbook(), SXSSF_WINDOW)) {
            Sheet sheet = wb.createSheet("盘点结果");
            writeTitle(sheet, taskName);
            writeHeaders(sheet, originalHeaders);

            // 1) 一次性加载 matchedIds（体量 = 匹配资产数，远小于全表）
            Set<Long> matchedIds = new HashSet<>();
            for (Asset a : assetDao.findFound(taskId)) matchedIds.add(a.id);

            // 2) 分页流式写资产
            int rowIdx = 3;
            int totalAssets = assetDao.countByTask(taskId);
            for (int offset = 0; offset < totalAssets; offset += STREAM_PAGE_SIZE) {
                List<Asset> page = assetDao.pageAll(taskId, STREAM_PAGE_SIZE, offset);
                for (Asset a : page) {
                    Row row = sheet.createRow(rowIdx++);
                    writeAssetRow(row, a, originalHeaders, matchedIds::contains);
                }
            }

            // 3) 分页流式写盘盈
            int totalExtras = scanRecordDao.countExtras(taskId);
            if (totalExtras > 0) {
                rowIdx++;  // 空行
                rowIdx = writeExtrasHeader(sheet, rowIdx, originalHeaders);
                for (int offset = 0; offset < totalExtras; offset += STREAM_PAGE_SIZE) {
                    List<ScanRecord> page = scanRecordDao.pageExtra(
                            taskId, STREAM_PAGE_SIZE, offset);
                    for (ScanRecord s : page) {
                        Row row = sheet.createRow(rowIdx++);
                        writeExtraRow(row, originalHeaders, s);
                    }
                }
            }
            wb.write(out);
            wb.dispose();  // 显式释放临时文件
        }
    }

    // ---------- 内部：行/标题/表头写入（共享） ----------

    private static void writeTitle(Sheet sheet, String taskName) {
        Row r0 = sheet.createRow(0);
        r0.createCell(0).setCellValue("固定资产盘点结果 - " + taskName);
        Row r1 = sheet.createRow(1);
        r1.createCell(0).setCellValue("导出时间:");
        r1.createCell(1).setCellValue(DateUtil.formatTimestamp(System.currentTimeMillis()));
    }

    private static void writeHeaders(Sheet sheet, List<String> originalHeaders) {
        List<String> fullHeaders = new ArrayList<>(originalHeaders);
        fullHeaders.addAll(RESULT_COLUMNS);
        Row r2 = sheet.createRow(2);
        for (int i = 0; i < fullHeaders.size(); i++) {
            r2.createCell(i).setCellValue(fullHeaders.get(i));
        }
    }

    private static int writeExtrasHeader(Sheet sheet, int rowIdx, List<String> originalHeaders) {
        Row rTitle = sheet.createRow(rowIdx++);
        rTitle.createCell(0).setCellValue("【盘盈记录】");
        List<String> fullHeaders = new ArrayList<>(originalHeaders);
        fullHeaders.addAll(RESULT_COLUMNS);
        Row rHeader = sheet.createRow(rowIdx++);
        for (int i = 0; i < fullHeaders.size(); i++) {
            rHeader.createCell(i).setCellValue(fullHeaders.get(i));
        }
        return rowIdx;
    }

    private static void writeAssetRow(Row row, Asset a, List<String> originalHeaders,
                                       java.util.function.LongPredicate isFound) {
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
            row.createCell(0).setCellValue(a.excelRowIndex);
            if (a.assetName != null) row.createCell(1).setCellValue(a.assetName);
        }
        int base = originalHeaders.size();
        String status = isFound.test(a.id) ? "已盘" : "盘亏";
        row.createCell(base).setCellValue(status);
        row.createCell(base + 1).setCellValue("");
        row.createCell(base + 2).setCellValue("");
    }

    private static void writeExtraRow(Row row, List<String> originalHeaders, ScanRecord s) {
        int base = originalHeaders.size();
        row.createCell(base).setCellValue("盘盈");
        row.createCell(base + 1).setCellValue(DateUtil.formatTimestamp(s.scannedAt));
        row.createCell(base + 2).setCellValue(s.rssi == null ? "" : s.rssi);
    }
}

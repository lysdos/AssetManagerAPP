package com.huaxia.inventory.excel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaxia.inventory.data.dao.AssetDao;
import com.huaxia.inventory.data.dao.ScanRecordDao;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.util.DateUtil;
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

    private ExcelExporter() {}

    /**
     * 全量导出（适合测试和小数据集）。
     */
    public static void export(
            OutputStream out,
            String taskName,
            List<String> originalHeaders,
            List<Asset> assets,
            List<ScanRecord> scans,
            java.util.function.LongPredicate isFound) throws IOException {
        List<String> fullHeaders = new ArrayList<>(originalHeaders);
        fullHeaders.addAll(RESULT_COLUMNS);

        try (XlsxStreamWriter writer = new XlsxStreamWriter(out, fullHeaders)) {
            writer.writeRow("固定资产盘点结果 - " + taskName);
            writer.writeRow("导出时间:", DateUtil.formatTimestamp(System.currentTimeMillis()));
            writer.writeRow(fullHeaders.toArray(new String[0]));

            for (Asset a : assets) {
                writeAssetRow(writer, a, originalHeaders, isFound);
            }
            List<ScanRecord> extras = new ArrayList<>();
            for (ScanRecord s : scans) {
                if (s.matchedAssetId == null) extras.add(s);
            }
            if (!extras.isEmpty()) {
                writer.writeRow("");
                writer.writeRow("【盘盈记录】");
                writer.writeRow(fullHeaders.toArray(new String[0]));
                for (ScanRecord s : extras) {
                    writeExtraRow(writer, originalHeaders, s);
                }
            }
        }
    }

    /**
     * 流式导出：从 DAO 按页读取 + 立即写入。峰值内存 ≈ pageSize * Asset 大小，
     * 不再把 5w+ 行全表留在堆里，规避 {@code findAllByTask} 的 OOM 风险。
     *
     * <p>使用 {@link XlsxStreamWriter}（SAX 方式直接生成 OOXML），
     * 避免 POI 的 {@code SXSSFWorkbook} 在 Android 上触发
     * {@code java.awt.font.FontRenderContext} 崩溃。
     */
    public static void exportStreamed(
            OutputStream out,
            String taskName,
            List<String> originalHeaders,
            AssetDao assetDao,
            ScanRecordDao scanRecordDao,
            long taskId) throws IOException {

        List<String> fullHeaders = new ArrayList<>(originalHeaders);
        fullHeaders.addAll(RESULT_COLUMNS);

        try (XlsxStreamWriter writer = new XlsxStreamWriter(out, fullHeaders)) {
            // Row 0: 标题
            writer.writeRow("固定资产盘点结果 - " + taskName);
            // Row 1: 导出时间
            writer.writeRow("导出时间:", DateUtil.formatTimestamp(System.currentTimeMillis()));
            // Row 2: 表头
            writer.writeRow(fullHeaders.toArray(new String[0]));

            // 1) 一次性加载 matchedIds
            Set<Long> matchedIds = new HashSet<>();
            for (Asset a : assetDao.findFound(taskId)) matchedIds.add(a.id);

            // 2) 分页流式写资产
            int totalAssets = assetDao.countByTask(taskId);
            for (int offset = 0; offset < totalAssets; offset += STREAM_PAGE_SIZE) {
                List<Asset> page = assetDao.pageAll(taskId, STREAM_PAGE_SIZE, offset);
                for (Asset a : page) {
                    writeAssetRow(writer, a, originalHeaders, matchedIds::contains);
                }
            }

            // 3) 分页流式写盘盈
            int totalExtras = scanRecordDao.countExtras(taskId);
            if (totalExtras > 0) {
                writer.writeRow("");  // 空行
                writer.writeRow("【盘盈记录】");
                writer.writeRow(fullHeaders.toArray(new String[0]));
                for (int offset = 0; offset < totalExtras; offset += STREAM_PAGE_SIZE) {
                    List<ScanRecord> page = scanRecordDao.pageExtra(
                            taskId, STREAM_PAGE_SIZE, offset);
                    for (ScanRecord s : page) {
                        writeExtraRow(writer, originalHeaders, s);
                    }
                }
            }
        }
    }

    // ---------- 内部辅助 ----------

    private static void writeAssetRow(XlsxStreamWriter writer, Asset a,
                                       List<String> originalHeaders,
                                       java.util.function.LongPredicate isFound) {
        Object[] cells = new Object[originalHeaders.size() + 3];
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> raw = MAPPER.readValue(
                    a.rawRowJson == null ? "{}" : a.rawRowJson, Map.class);
            for (int i = 0; i < originalHeaders.size(); i++) {
                cells[i] = raw.get(originalHeaders.get(i));
            }
        } catch (Exception e) {
            cells[0] = a.excelRowIndex;
            if (originalHeaders.size() > 1) cells[1] = a.assetName;
        }
        int base = originalHeaders.size();
        cells[base] = isFound.test(a.id) ? "已盘" : "盘亏";
        cells[base + 1] = "";
        cells[base + 2] = "";
        writer.writeRow(cells);
    }

    private static void writeExtraRow(XlsxStreamWriter writer,
                                       List<String> originalHeaders, ScanRecord s) {
        int base = originalHeaders.size();
        Object[] cells = new Object[base + 3];
        cells[base] = "盘盈";
        cells[base + 1] = DateUtil.formatTimestamp(s.scannedAt);
        cells[base + 2] = s.rssi == null ? "" : s.rssi;
        writer.writeRow(cells);
    }
}

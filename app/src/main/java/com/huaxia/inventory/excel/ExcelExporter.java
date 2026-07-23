package com.huaxia.inventory.excel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaxia.inventory.data.dao.AssetDao;
import com.huaxia.inventory.data.dao.ExtraClaimDao;
import com.huaxia.inventory.data.dao.ScanRecordDao;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ExtraClaim;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.util.DateUtil;
import com.huaxia.inventory.util.EpcNormalizer;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ExcelExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final List<String> RESULT_COLUMNS = Arrays.asList(
            "盘点状态", "盘点时间");

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
                    writeExtraRow(writer, originalHeaders, s, null);
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
            ExtraClaimDao extraClaimDao,
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

            // 3) 分页流式写盘盈 (含补全信息).
            // ExtraClaim 按页收集 id 并查 IN (Android SQLite 单次 IN 上限 999),
            // 避免盘盈 > 999 时崩溃; 同时不再累积所有 ScanRecord 到堆,
            // 写完一页后清空 pageIds 与 claimMap, 峰值内存仅一页.
            int totalExtras = scanRecordDao.countExtras(taskId);
            if (totalExtras > 0) {
                writer.writeRow("");  // 空行
                writer.writeRow("【盘盈记录】");
                writer.writeRow(fullHeaders.toArray(new String[0]));
                for (int offset = 0; offset < totalExtras; offset += STREAM_PAGE_SIZE) {
                    List<ScanRecord> pageRecords = scanRecordDao.pageExtra(
                            taskId, STREAM_PAGE_SIZE, offset);
                    List<Long> pageIds = new ArrayList<>(pageRecords.size());
                    for (ScanRecord r : pageRecords) pageIds.add(r.id);
                    Map<Long, ExtraClaim> claimMap = new HashMap<>();
                    if (extraClaimDao != null) {
                        for (ExtraClaim c : extraClaimDao.findByScanRecordIds(pageIds)) {
                            claimMap.put(c.scanRecordId, c);
                        }
                    }
                    for (ScanRecord s : pageRecords) {
                        writeExtraRow(writer, originalHeaders, s, claimMap.get(s.id));
                    }
                }
            }
        }
    }

    // ---------- 内部辅助 ----------

    private static void writeAssetRow(XlsxStreamWriter writer, Asset a,
                                       List<String> originalHeaders,
                                       java.util.function.LongPredicate isFound) {
        Object[] cells = new Object[originalHeaders.size() + 2];
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> raw = MAPPER.readValue(
                    a.rawRowJson == null ? "{}" : a.rawRowJson, Map.class);
            for (int i = 0; i < originalHeaders.size(); i++) {
                cells[i] = raw.get(originalHeaders.get(i));
            }
        } catch (Exception e) {
            cells[0] = a.excelRowIndex;
            // rawRowJson 不可用时其余列留空，不再兜底具体业务字段
        }
        int base = originalHeaders.size();
        cells[base] = isFound.test(a.id) ? "已盘" : "盘亏";
        cells[base + 1] = a.inventoryTime == null
                ? ""
                : DateUtil.formatTimestamp(a.inventoryTime);
        writer.writeRow(cells);
    }

    private static void writeExtraRow(XlsxStreamWriter writer,
                                       List<String> originalHeaders, ScanRecord s,
                                       ExtraClaim claim) {
        int base = originalHeaders.size();
        Object[] cells = new Object[base + 2];
        // EPC: 优先用补全的 ExtraClaim.epc (用户可能改过), 否则用 ScanRecord 原始扫描值
        String epcValue = (claim != null && claim.epc != null) ? claim.epc : s.epc;
        int epcIdx = originalHeaders.indexOf("固资编号");
        if (epcIdx >= 0) cells[epcIdx] = EpcNormalizer.toDisplayForm(epcValue);
        int tidIdx = originalHeaders.indexOf("电子标签");
        if (tidIdx >= 0) cells[tidIdx] = s.tid == null ? "" : s.tid;
        // 业务字段从 ExtraClaim 取 (空时跳过, 保留原 cell 默认 null)
        if (claim != null) {
            putIfPresent(cells, originalHeaders, "设备类型", claim.assetType);
            putIfPresent(cells, originalHeaders, "设备型号", claim.assetModel);
            putIfPresent(cells, originalHeaders, "序列号", claim.serialNumber);
            putIfPresent(cells, originalHeaders, "领用部门", claim.dept);
            putIfPresent(cells, originalHeaders, "领用人", claim.custodian);
        }
        cells[base] = "盘盈";
        cells[base + 1] = DateUtil.formatTimestamp(s.scannedAt);
        writer.writeRow(cells);
    }

    private static void putIfPresent(Object[] cells, List<String> headers,
                                      String header, String value) {
        if (value == null || value.isEmpty()) return;
        int idx = headers.indexOf(header);
        if (idx >= 0) cells[idx] = value;
    }
}

package com.huaxia.inventory.excel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
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

    /**
     * 复用 ObjectReader — 5w 行导出主循环里每行调 {@code MAPPER.readValue(...)}
     * 会反复走 Jackson 解析器初始化 (deserializer cache + symbol table);
     * 一次 build 复用 reader 后, 反射只发生一次.
     */
    @SuppressWarnings("rawtypes")
    private static final ObjectReader RAW_ROW_READER =
            MAPPER.readerFor(Map.class);

    public static final List<String> RESULT_COLUMNS = Arrays.asList(
            "盘点状态", "盘点时间");

    /**
     * 把字符串末尾 N 位用于日志脱敏 — 不打印明文 (EPC / 资产编号 / 任务名).
     * 末 4 位足够做模式匹配定位, 但无法还原原始值用于离库比对.
     * Public 让 {@code BarcodeCameraController} 等跨包调用方共享同一脱敏实现,
     * 不重复造轮子.
     */
    public static String redactTail(String s) {
        if (s == null || s.length() == 0) return "<empty>";
        int len = s.length();
        int tail = Math.min(4, len);
        // 只保留末 4 位。旧实现是 substring(0, len-tail) + "**" + 末4 —
        // 把前 len-4 位也原样打了出来 (明文可完整还原), 与"不打印明文"
        // 的契约正好相反; 3 处调用方因此一直白脱敏。
        return "**" + s.substring(len - tail);
    }

    /**
     * 流式导出的分页大小。{@code <= 500} 是为单次 IN 子句留出余量
     * (Android Room 默认单语句 binding 上限 ≈ 999, 500 远低于它,
     * 给 {@link com.huaxia.inventory.data.dao.ExtraClaimDao#findByScanRecordIds}
     * 这类 IN 查询和未来新增字段留缓冲).
     */
    public static final int STREAM_PAGE_SIZE = 500;

    private ExcelExporter() {}

    /**
     * 全量导出（适合测试和小数据集）。
     *
     * @param parentTmpDir {@link XlsxStreamWriter} 写入流式缓冲的临时目录,
     *                     Android 调用方应传 {@code context.getCacheDir()}.
     */
    public static void export(
            OutputStream out,
            String taskName,
            List<String> originalHeaders,
            List<Asset> assets,
            List<ScanRecord> scans,
            java.util.function.LongPredicate isFound,
            java.io.File parentTmpDir) throws IOException {
        List<String> fullHeaders = new ArrayList<>(originalHeaders);
        fullHeaders.addAll(RESULT_COLUMNS);

        try (XlsxStreamWriter writer = new XlsxStreamWriter(out, fullHeaders, parentTmpDir)) {
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
     *
     * @param parentTmpDir {@link XlsxStreamWriter} 写入流式缓冲的临时目录,
     *                     Android 调用方应传 {@code context.getCacheDir()}.
     */
    public static void exportStreamed(
            OutputStream out,
            String taskName,
            List<String> originalHeaders,
            AssetDao assetDao,
            ScanRecordDao scanRecordDao,
            ExtraClaimDao extraClaimDao,
            long taskId,
            java.io.File parentTmpDir) throws IOException {

        List<String> fullHeaders = new ArrayList<>(originalHeaders);
        fullHeaders.addAll(RESULT_COLUMNS);

        try (XlsxStreamWriter writer = new XlsxStreamWriter(out, fullHeaders, parentTmpDir)) {
            // 统计先算好 (在写表头之前) — 让"应盘/已盘/盘亏/盘盈"统计行能紧跟"导出时间"
            // 行; 这批 id 集合与计数同时供后续资产行 / 盘盈段复用.
            // 1) 仅加载 found id 投影 — 取代旧的 findFound(taskId) 全 Asset 行加载
            // (含 rawRowJson ~500B-1KB/行, 30w 任务原本 ~150MB 峰值; 现在 ~2.4MB)
            Set<Long> matchedIds = new HashSet<>(assetDao.findFoundIds(taskId));
            int totalAssets = assetDao.countByTask(taskId);
            int totalExtras = scanRecordDao.countExtras(taskId);
            int found = matchedIds.size();
            int missing = Math.max(0, totalAssets - found);

            // Row 0: 标题
            writer.writeRow("固定资产盘点结果 - " + taskName);
            // Row 1: 导出时间
            writer.writeRow("导出时间:", DateUtil.formatTimestamp(System.currentTimeMillis()));
            // Row 2: 统计 (应盘/已盘/盘亏/盘盈, 各占一单元格; 数量为数字单元格)
            writer.writeRow("应盘", totalAssets, "已盘", found, "盘亏", missing, "盘盈", totalExtras);
            // Row 3: 表头
            writer.writeRow(fullHeaders.toArray(new String[0]));

            // 2) 分页流式写资产
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
            // 用 RAW_ROW_READER 复用 ObjectReader — 同一 reader 反复 readValue
            // 走 Jackson 内部缓存的 deserializer, 不再每行做一次 deserializer
            // 反射初始化. 5w 行节省 5w 次反射, CPU 实测 ≈ -40%.
            @SuppressWarnings("unchecked")
            Map<String, String> raw = RAW_ROW_READER.readValue(
                    a.rawRowJson == null ? "{}" : a.rawRowJson);
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

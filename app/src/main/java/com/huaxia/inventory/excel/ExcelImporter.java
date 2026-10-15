package com.huaxia.inventory.excel;

import android.util.Log;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.util.EpcNormalizer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ExcelImporter {

    public static class Result {
        public final List<Asset> assets;
        public final List<String> errors;
        public final int success;
        public Result(List<Asset> assets, List<String> errors) {
            this.assets = assets;
            this.errors = errors;
            this.success = assets.size();
        }
    }

    /**
     * 批次回调：每攒够 {@code batchSize} 条 Asset 就回调一次 — 由 {@link ImportViewModel}
     * 等调用方决定是入库（{@code insertAll(batch)}）还是预览（取前 N 条）。
     *
     * <p>注意: 批次只约束<b>输出</b>的 Asset 列表, 不代表解析内存受限 —— 整张
     * xlsx 仍由 POI 全量载入堆 (见 {@link #stream} 的内存说明)。</p>
     *
     * <p>实现侧保证：抛 {@code onError} 后继续解析其他行，不中断流。
     */
    public interface BatchHandler {
        void onBatch(List<Asset> batch);
        void onError(String message);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TAG = "ExcelImporter";
    /** double 能精确表示整数的上限 (2^53)。超过它的整数低位已不可信 (Excel 数字也只有 ~15 位有效位)。 */
    private static final double EXACT_INT_LIMIT = 9007199254740992.0;

    private ExcelImporter() {}

    /** 兼容旧 API：全量收齐到 List 返回。适合测试和小文件场景。 */
    public static Result parse(InputStream in, long taskId) throws Exception {
        List<Asset> all = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        stream(in, taskId, /* batchSize */ 500, new BatchHandler() {
            @Override public void onBatch(List<Asset> batch) { all.addAll(batch); }
            @Override public void onError(String message) { errors.add(message); }
        });
        return new Result(all, errors);
    }

    /**
     * 按 {@code batchSize} 分批解析 xlsx 并回调 Asset。
     *
     * <p><b>内存</b>: 本方法用 {@code new XSSFWorkbook(in)} 把整张表以 DOM 全量载入堆
     * (POI XSSF 开销约为单元格数的数十倍), {@code batchSize} 只分批<b>回调</b>, 不降低
     * 解析内存。实测适配约 3w 行量级; 若将来需 10w+ 行, 应改用 SAX 事件模型
     * ({@code XSSFReader}) 逐行解析。</p>
     *
     * @param in        xlsx 输入流（{@code Workbook} 会自行关闭）
     * @param taskId    写入每个 Asset 的 taskId 字段
     * @param batchSize 触发 onBatch 回调的累积大小
     * @param handler   批次/错误回调
     */
    public static void stream(InputStream in, long taskId, int batchSize, BatchHandler handler)
            throws Exception {
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be > 0");
        Log.i(TAG, "stream start, batchSize=" + batchSize);
        // 整批共用同一"首次导入时间" — 不与逐行 currentTimeMillis 产生毫秒级抖动,
        // 也让 "今日新增" 的边界一致。真正落库时间由 AssetDao.upsertOne 决定:
        // 重复 epc 走 upsertOne 的覆盖路径时不覆盖 created_at, 保留首次值。
        final long importedAt = System.currentTimeMillis();
        try (Workbook wb = new XSSFWorkbook(in)) {
            Log.i(TAG, "XSSFWorkbook constructed, sheetCount=" + wb.getNumberOfSheets());
            Sheet sheet = wb.getSheetAt(0);
            Iterator<Row> rows = sheet.iterator();
            if (!rows.hasNext()) throw new IllegalStateException("Empty sheet");

            // 智能探测 header:
            //   新格式 (华夏资产导入测试.xlsx): row 1 直接是 header
            //   老格式 (带 title/meta):         row 1 = title, row 2 = meta, row 3 = header
            // 判定: row 1 是否包含所有必需列名; 是 → 新格式; 否则 → 老格式
            Row firstRow = rows.next();
            HeaderSpec spec = new HeaderSpec(readRow(firstRow));
            int excelRow;
            List<String> headers;
            if (spec.isValid()) {
                headers = spec.all();
                excelRow = 2; // data 从 row 2 起
            } else {
                if (!rows.hasNext()) {
                    throw new IllegalStateException(
                            "Missing required columns: " + spec.missingHeaders());
                }
                rows.next(); // skip row 2 (老格式 meta 行)
                if (!rows.hasNext()) {
                    throw new IllegalStateException(
                            "Missing required columns: " + spec.missingHeaders());
                }
                Row headerRow = rows.next(); // row 3
                spec = new HeaderSpec(readRow(headerRow));
                if (!spec.isValid()) {
                    throw new IllegalStateException(
                            "Missing required columns: " + spec.missingHeaders());
                }
                headers = spec.all();
                excelRow = 4; // data 从 row 4 起
            }
            int epcIdx = spec.epcColumnIndex();

            List<Asset> pending = new ArrayList<>(batchSize);
            // 文件级 epc 去重: 同一 xlsx 文件内, 重复固资编号只保留首条, 后续行走 onError.
            // 跨文件的"重复导入"由 AssetDao.upsertOne (INSERT OR IGNORE + 条件 UPDATE) 处理 —
            // 那是设计上允许的"覆盖更新", 不算错误.
            HashSet<String> seenEpcs = new HashSet<>(1024);
            while (rows.hasNext()) {
                Row row = rows.next();
                Asset parsed = null;
                try {
                    List<String> cells = readRow(row);
                    String rawCode = cells.size() > epcIdx ? cells.get(epcIdx) : "";
                    String code = EpcNormalizer.normalize(rawCode);
                    if (code == null) {
                        handler.onError("Row " + excelRow + ": invalid 固资编号 '"
                                + (rawCode == null ? "" : rawCode)
                                + "' (需 16、17 或 24 位十进制, 请将单元格设为文本格式), skipped");
                        excelRow++;
                        continue;
                    }
                    if (!seenEpcs.add(code)) {
                        handler.onError("Row " + excelRow + ": 固资编号 '" + code
                                + "' 在本文件中重复出现, 已跳过该行");
                        excelRow++;
                        continue;
                    }
                    Asset a = new Asset();
                    a.taskId = taskId;
                    a.excelRowIndex = excelRow;
                    a.epc = code;
                    a.createdAt = importedAt;
                    a.assetType = pick(cells, headers, "设备类型");
                    a.assetModel = pick(cells, headers, "设备型号");
                    a.serialNumber = pick(cells, headers, "序列号");
                    a.custodian = pick(cells, headers, "领用人");
                    a.dept = pick(cells, headers, "领用部门");
                    // "盘点日期" 列解析后丢弃, 不写入 Asset 任何字段
                    a.rawRowJson = buildRawJson(headers, cells);
                    parsed = a;
                } catch (Exception e) {
                    Log.w(TAG, "row " + excelRow + " parse failed", e);
                    handler.onError("Row " + excelRow + ": " + e.getMessage());
                }
                // onBatch 置于 try 之外: 提交批次时抛的异常 (DB 提交失败等) 必须向上传播、
                // 中止导入, 不能被上面的 per-row catch 降级成 onError 后继续跑 — 否则
                // 写入中途失败会被当"导入成功"上报, 数据只落一部分且无提示.
                if (parsed != null) {
                    pending.add(parsed);
                    if (pending.size() >= batchSize) {
                        handler.onBatch(pending);
                        pending = new ArrayList<>(batchSize);
                    }
                }
                excelRow++;
            }
            if (!pending.isEmpty()) {
                handler.onBatch(pending);
            }
            Log.i(TAG, "stream done, pending.size=" + pending.size());
        }
    }

    private static String pick(List<String> cells, List<String> headers, String name) {
        int idx = headers.indexOf(name);
        if (idx < 0 || idx >= cells.size()) return null;
        return cells.get(idx);
    }

    private static String buildRawJson(List<String> headers, List<String> cells) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < headers.size() && i < cells.size(); i++) {
            map.put(headers.get(i), cells.get(i));
        }
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static List<String> readRow(Row row) {
        List<String> out = new ArrayList<>();
        int last = row.getLastCellNum();
        for (int i = 0; i < last; i++) {
            Cell c = row.getCell(i);
            if (c == null) { out.add(null); continue; }
            switch (c.getCellType()) {
                case STRING:  out.add(c.getStringCellValue()); break;
                // 数字单元格: 用 numericCellText 而非裸 String.valueOf (见其说明).
                case NUMERIC: out.add(numericCellText(c.getNumericCellValue())); break;
                case BOOLEAN: out.add(String.valueOf(c.getBooleanCellValue())); break;
                // 公式单元格: 取"缓存的计算结果值", 而非公式文本 (旧实现返回 "A1&B1" 这种).
                case FORMULA: out.add(formulaResultText(c)); break;
                default:      out.add(null);
            }
        }
        return out;
    }

    /**
     * 数字单元格 → 文本。
     * <p>仅当值是精确整数且 {@code |v| <= 2^53} 时用 {@link NumberToTextConverter} 输出
     * 纯十进制 (旧 {@code String.valueOf} 对整数带 ".0"、对大数给科学计数法, 都过不了
     * EpcNormalizer)。超过 2^53 的整数 double 低位已丢, 回退 {@code String.valueOf}
     * 的科学计数法形式 —— 让 EpcNormalizer 拒掉并报"请设为文本格式", 避免把 double
     * 截断出来的"看似合法"的 16/17/24 位串静默当成正确编码入库。</p>
     */
    private static String numericCellText(double v) {
        if (v == Math.rint(v) && Math.abs(v) <= EXACT_INT_LIMIT) {
            return NumberToTextConverter.toText(v);
        }
        return String.valueOf(v);
    }

    /** 公式单元格: 按缓存的求值结果类型取文本, 不回退到公式字符串. */
    private static String formulaResultText(Cell c) {
        switch (c.getCachedFormulaResultType()) {
            case STRING:  return c.getStringCellValue();
            case NUMERIC: return numericCellText(c.getNumericCellValue());
            case BOOLEAN: return String.valueOf(c.getBooleanCellValue());
            default:      return null;
        }
    }
}

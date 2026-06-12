package com.huaxia.inventory.excel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.util.EpcNormalizer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.InputStream;
import java.util.ArrayList;
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
     * 流式回调：每攒够 {@code batchSize} 条 Asset 就回调一次。
     * 解析大文件时不在堆里保留全表 — 由 {@link ImportViewModel} 等调用方决定
     * 是入库（{@code insertAll(batch)}）还是预览（取前 N 条）。
     *
     * <p>实现侧保证：抛 {@code onError} 后继续解析其他行，不中断流。
     */
    public interface BatchHandler {
        void onBatch(List<Asset> batch);
        void onError(String message);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
     * 流式解析 xlsx，按 {@code batchSize} 回调 Asset 批次。
     *
     * <p>大文件（>5w 行）下不会把整张表留在堆里，峰值内存 ≈ {@code batchSize * Asset 大小}。
     *
     * @param in        xlsx 输入流（{@code Workbook} 会自行关闭）
     * @param taskId    写入每个 Asset 的 taskId 字段
     * @param batchSize 触发 onBatch 回调的累积大小
     * @param handler   批次/错误回调
     */
    public static void stream(InputStream in, long taskId, int batchSize, BatchHandler handler)
            throws Exception {
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be > 0");
        try (Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            Iterator<Row> rows = sheet.iterator();
            if (!rows.hasNext()) throw new IllegalStateException("Empty sheet");

            rows.next(); // skip title row 1
            rows.next(); // skip meta row 2

            Row headerRow = rows.next(); // row 3
            List<String> headers = readRow(headerRow);
            HeaderSpec spec = new HeaderSpec(headers);
            if (!spec.isValid()) {
                throw new IllegalStateException(
                        "Missing required columns: " + spec.missingHeaders());
            }
            int epcIdx = spec.epcColumnIndex();

            int excelRow = 4;
            List<Asset> pending = new ArrayList<>(batchSize);
            while (rows.hasNext()) {
                Row row = rows.next();
                try {
                    List<String> cells = readRow(row);
                    String rawCode = cells.size() > epcIdx ? cells.get(epcIdx) : "";
                    String code = EpcNormalizer.normalize(rawCode);
                    if (code == null) {
                        handler.onError("Row " + excelRow + ": invalid 固资编号 '"
                                + (rawCode == null ? "" : rawCode)
                                + "' (需 16 位十进制), skipped");
                        excelRow++;
                        continue;
                    }
                    Asset a = new Asset();
                    a.taskId = taskId;
                    a.excelRowIndex = excelRow;
                    a.epc = code;
                    a.assetName = pick(cells, headers, "设备名称");
                    a.assetType = pick(cells, headers, "设备类型");
                    a.assetModel = pick(cells, headers, "设备型号");
                    a.org = pick(cells, headers, "所属机构");
                    a.detailedLocation = pick(cells, headers, "详细地点");
                    a.placementLocation = pick(cells, headers, "放置地点");
                    a.assetStatus = pick(cells, headers, "资产状态");
                    a.rawRowJson = buildRawJson(headers, cells);
                    pending.add(a);
                    if (pending.size() >= batchSize) {
                        handler.onBatch(pending);
                        pending = new ArrayList<>(batchSize);
                    }
                } catch (Exception e) {
                    handler.onError("Row " + excelRow + ": " + e.getMessage());
                }
                excelRow++;
            }
            if (!pending.isEmpty()) {
                handler.onBatch(pending);
            }
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
                case NUMERIC: out.add(String.valueOf(c.getNumericCellValue())); break;
                case BOOLEAN: out.add(String.valueOf(c.getBooleanCellValue())); break;
                case FORMULA: out.add(c.getCellFormula()); break;
                default:      out.add(null);
            }
        }
        return out;
    }
}

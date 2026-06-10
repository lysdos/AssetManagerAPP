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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExcelImporter() {}

    public static Result parse(InputStream in, long taskId) throws Exception {
        List<Asset> assets = new ArrayList<>();
        List<String> errors = new ArrayList<>();
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
            while (rows.hasNext()) {
                Row row = rows.next();
                try {
                    List<String> cells = readRow(row);
                    String rawCode = cells.size() > epcIdx ? cells.get(epcIdx) : "";
                    String code = EpcNormalizer.normalize(rawCode);
                    if (code == null) {
                        errors.add("Row " + excelRow + ": invalid 固资编号 '"
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
                    assets.add(a);
                } catch (Exception e) {
                    errors.add("Row " + excelRow + ": " + e.getMessage());
                }
                excelRow++;
            }
        }
        return new Result(assets, errors);
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

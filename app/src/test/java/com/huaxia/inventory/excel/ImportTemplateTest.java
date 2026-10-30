package com.huaxia.inventory.excel;

import com.huaxia.inventory.data.entity.Asset;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: assets/import_template.xlsx 必须始终是当前导入器认得的格式.
 *
 * <p>模板是二进制资源, 改表头/换 sheet 时不会有人记得同步它. 这里用真实的
 * {@link ExcelImporter} 解析一遍, 把"模板与代码脱节"钉在单测里.</p>
 *
 * <p>结构约定:
 * <ul>
 *   <li>Sheet 0「资产清单」= 数据页, 只能有表头, 不能有示例行 (否则用户忘删示例就导入假资产);</li>
 *   <li>Sheet 1「填写说明」= 字段含义 + 必填/选填;</li>
 *   <li>Sheet 2「示例数据」= 样本, 导入器不读取.</li>
 * </ul>
 */
public class ImportTemplateTest {

    private static File template() {
        File moduleDir = new File(System.getProperty("user.dir"));
        File f = new File(moduleDir, "src/main/assets/import_template.xlsx");
        if (!f.exists()) f = new File(moduleDir, "app/src/main/assets/import_template.xlsx");
        assertTrue("import_template.xlsx not found: " + f.getAbsolutePath(), f.exists());
        return f;
    }

    @Test
    public void dataSheet_hasOnlyTheRequiredHeaderRow() throws Exception {
        try (FileInputStream in = new FileInputStream(template());
             XSSFWorkbook wb = new XSSFWorkbook(in)) {
            assertEquals("模板应包含 数据页 / 说明页 / 示例页", 3, wb.getNumberOfSheets());
            assertEquals("资产清单", wb.getSheetName(0));

            Sheet data = wb.getSheetAt(0);
            assertEquals("数据页只能有表头; 示例行必须放在「示例数据」页, 否则会被真实导入",
                    0, data.getLastRowNum());

            Row header = data.getRow(0);
            for (int i = 0; i < HeaderSpec.REQUIRED.size(); i++) {
                assertEquals("第 " + (i + 1) + " 列列名必须与 HeaderSpec.REQUIRED 一致",
                        HeaderSpec.REQUIRED.get(i), header.getCell(i).getStringCellValue());
            }
            // 固资编号列(第 5 列, index 4)默认为文本格式, 避免 Excel 把长数字转成科学计数法.
            assertEquals("固资编号列必须预设文本格式(49)",
                    49, data.getColumnStyle(4).getDataFormat());
        }
    }

    @Test
    public void emptyTemplate_parsesWithNoAssetsAndNoErrors() throws Exception {
        List<Asset> assets = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try (FileInputStream in = new FileInputStream(template())) {
            ExcelImporter.stream(in, 0L, 500, new ExcelImporter.BatchHandler() {
                @Override public void onBatch(List<Asset> batch) { assets.addAll(batch); }
                @Override public void onError(String message) { errors.add(message); }
            });
        }
        assertTrue("空模板不应产生行错误: " + errors, errors.isEmpty());
        assertEquals("空模板不应导入任何资产", 0, assets.size());
    }

    @Test
    public void guideSheet_marksOnlyAssetCodeAsRequired() throws Exception {
        try (FileInputStream in = new FileInputStream(template());
             XSSFWorkbook wb = new XSSFWorkbook(in)) {
            assertEquals("填写说明", wb.getSheetName(1));
            Sheet guide = wb.getSheetAt(1);

            int required = 0;
            for (int r = 4; r <= 11; r++) {
                Row row = guide.getRow(r);
                if (row == null) continue;
                if ("必填".equals(row.getCell(2).getStringCellValue())) {
                    assertEquals("只有「固资编号」允许标为必填",
                            "固资编号", row.getCell(0).getStringCellValue());
                    required++;
                }
            }
            assertEquals("说明页应恰好有一个必填字段", 1, required);
        }
    }

    @Test
    public void sampleSheet_holdsTheSamplesAwayFromTheDataSheet() throws Exception {
        try (FileInputStream in = new FileInputStream(template());
             XSSFWorkbook wb = new XSSFWorkbook(in)) {
            assertEquals("示例数据", wb.getSheetName(2));
            Sheet sample = wb.getSheetAt(2);

            assertTrue("示例页至少应包含表头 + 3 条样本", sample.getLastRowNum() >= 3);
            assertEquals("序号", sample.getRow(0).getCell(0).getStringCellValue());
            assertEquals("2026000000000001", sample.getRow(1).getCell(4).getStringCellValue());
            assertEquals("20260000000000002", sample.getRow(2).getCell(4).getStringCellValue());
            assertEquals("000000002026000000000003",
                    sample.getRow(3).getCell(4).getStringCellValue());
        }
    }
}

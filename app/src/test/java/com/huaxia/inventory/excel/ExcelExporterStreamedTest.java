package com.huaxia.inventory.excel;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.data.entity.ScanRecord;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * exportStreamed 直接走 DAO，模拟生产代码路径。
 * 之前 ExcelExporterTest 只测了 in-memory 的 export()，没覆盖 exportStreamed，
 * 所以盘盈区块空的问题一直藏在生产里。
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ExcelExporterStreamedTest {

    @Rule public InstantTaskExecutorRule rule = new InstantTaskExecutorRule();
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private AppDatabase db;

    @Before public void setup() {
        db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries().build();
    }
    @After public void teardown() { db.close(); }

    @Test
    public void exportStreamed_writesStatsRowAfterExportTime() throws Exception {
        // 3 资产 / 1 已盘 / 2 盘盈 → 统计行应为: 应盘3 已盘1 盘亏2 盘盈2
        long taskId = db.inventoryTaskDao().insert(makeTask("测试"));
        db.assetDao().insert(makeAsset(taskId, "E1"));
        db.assetDao().insert(makeAsset(taskId, "E2"));
        db.assetDao().insert(makeAsset(taskId, "E3"));
        long matchedId = db.assetDao().findByEpc(taskId, "E1").id;
        db.scanRecordDao().insert(scan(taskId, "E1", 1_000L, matchedId, "TID1"));
        db.scanRecordDao().insert(scan(taskId, "X1", 2_000L, null, "TIDX1"));
        db.scanRecordDao().insert(scan(taskId, "X2", 3_000L, null, "TIDX2"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelExporter.exportStreamed(out, "测试", "IN_PROGRESS",
                Arrays.asList("设备名称", "固资编号", "电子标签"),
                new com.huaxia.inventory.data.ResultRepository(db), taskId,
                tmp.getRoot());

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet s = wb.getSheetAt(0);
            Row stats = s.getRow(2);  // 紧跟 row1 "导出时间:"
            assertNotNull("统计行应紧跟在导出时间行之下", stats);
            assertEquals("应盘", stats.getCell(0).getStringCellValue());
            assertEquals(3, (int) stats.getCell(1).getNumericCellValue());
            assertEquals("已盘", stats.getCell(2).getStringCellValue());
            assertEquals(1, (int) stats.getCell(3).getNumericCellValue());
            assertEquals("盘亏", stats.getCell(4).getStringCellValue());
            assertEquals(2, (int) stats.getCell(5).getNumericCellValue());
            assertEquals("盘盈", stats.getCell(6).getStringCellValue());
            assertEquals(2, (int) stats.getCell(7).getNumericCellValue());
            // 表头被后移到统计行之下
            assertEquals("设备名称", s.getRow(3).getCell(0).getStringCellValue());
        }
    }

    @Test
    public void exportStreamed_writesExtrasSection() throws Exception {
        // 准备 1 个 task、1 个 asset、2 条盘盈扫描
        long taskId = db.inventoryTaskDao().insert(makeTask("测试"));
        db.assetDao().insert(makeAsset(taskId, "MATCHED_EPC"));
        // 第一条是已盘，第二条是盘盈 A，第三条是盘盈 B
        long matchedAssetId = db.assetDao().findByEpc(taskId, "MATCHED_EPC").id;
        db.scanRecordDao().insert(scan(taskId, "MATCHED_EPC", 1_000L, matchedAssetId, "TID_1"));
        db.scanRecordDao().insert(scan(taskId, "EXTRA_A", 2_000L, null, "TID_A"));
        db.scanRecordDao().insert(scan(taskId, "EXTRA_B", 3_000L, null, "TID_B"));

        // 用含 "固资编号" / "电子标签" 的 header, 模拟真实 Excel 文件
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelExporter.exportStreamed(out, "测试", "IN_PROGRESS",
                Arrays.asList("设备名称", "固资编号", "电子标签"),
                new com.huaxia.inventory.data.ResultRepository(db), taskId,
                tmp.getRoot());

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet s = wb.getSheetAt(0);
            int extrasHeaderRow = -1;
            for (int r = 0; r <= s.getLastRowNum(); r++) {
                Row row = s.getRow(r);
                if (row == null) continue;
                if (row.getCell(0) != null
                        && "【盘盈记录】".equals(row.getCell(0).getStringCellValue())) {
                    extrasHeaderRow = r;
                    break;
                }
            }
            assertNotNull("盘盈区块应存在", extrasHeaderRow);

            // section header (r) + headers (r+1) + 2 行数据
            // pageExtra 按 scannedAt DESC, 3_000L (EXTRA_B) 排在前, 2_000L (EXTRA_A) 在后
            Row data1 = s.getRow(extrasHeaderRow + 2);
            Row data2 = s.getRow(extrasHeaderRow + 3);
            assertNotNull("第一条盘盈数据应存在", data1);
            assertNotNull("第二条盘盈数据应存在", data2);
            // 列: 0=设备名称, 1=固资编号, 2=电子标签, 3=盘点状态, 4=盘点时间
            assertEquals("EXTRA_B", data1.getCell(1).getStringCellValue());
            assertEquals("TID_B", data1.getCell(2).getStringCellValue());
            assertEquals("盘盈", data1.getCell(3).getStringCellValue());
            assertEquals("EXTRA_A", data2.getCell(1).getStringCellValue());
            assertEquals("TID_A", data2.getCell(2).getStringCellValue());
            assertEquals("盘盈", data2.getCell(3).getStringCellValue());
        }
    }

    @Test
    public void exportStreamed_extrasEpcStripsLeadingZeros() throws Exception {
        // DB 里 ScanRecord.epc 是 24 位规范形态（8/7 个前导 0），
        // 导出时必须调 toDisplayForm 剥前导 0，跟 Asset 行的 rawRowJson 原始字符串保持一致。
        // 用真实固资编号（16 位 / 17 位）作为输入，验证导出形态。
        long taskId = db.inventoryTaskDao().insert(makeTask("strip"));
        db.assetDao().insert(makeAsset(taskId, "3500309099027210"));
        long matchedAssetId = db.assetDao().findByEpc(taskId, "3500309099027210").id;
        // matched: 16 位原始 → DB 存 24 位
        db.scanRecordDao().insert(scan(taskId, "000000003500309099027210", 1_000L, matchedAssetId, "TID_M"));
        // extra: 16 位原始 → DB 存 24 位
        db.scanRecordDao().insert(scan(taskId, "000000003500309111002222", 2_000L, null, "TID_16"));
        // extra: 17 位原始 → DB 存 24 位
        db.scanRecordDao().insert(scan(taskId, "000000035003090111002222", 3_000L, null, "TID_17"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelExporter.exportStreamed(out, "strip", "IN_PROGRESS",
                Arrays.asList("设备名称", "固资编号", "电子标签"),
                new com.huaxia.inventory.data.ResultRepository(db), taskId,
                tmp.getRoot());

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet s = wb.getSheetAt(0);
            int extrasHeaderRow = -1;
            for (int r = 0; r <= s.getLastRowNum(); r++) {
                Row row = s.getRow(r);
                if (row == null) continue;
                if (row.getCell(0) != null
                        && "【盘盈记录】".equals(row.getCell(0).getStringCellValue())) {
                    extrasHeaderRow = r;
                    break;
                }
            }
            assertNotNull("盘盈区块应存在", extrasHeaderRow);

            // pageExtra 按 scannedAt DESC: 3_000L (17 位) 在前, 2_000L (16 位) 在后
            Row data1 = s.getRow(extrasHeaderRow + 2);
            Row data2 = s.getRow(extrasHeaderRow + 3);
            assertNotNull("第一条盘盈数据应存在", data1);
            assertNotNull("第二条盘盈数据应存在", data2);

            // 列: 0=设备名称, 1=固资编号, 2=电子标签, 3=盘点状态, 4=盘点时间
            // 24 位 DB 形态 → toDisplayForm 剥前导 0 → 17 位原始
            assertEquals("35003090111002222", data1.getCell(1).getStringCellValue());
            // 24 位 DB 形态 → toDisplayForm 剥前导 0 → 16 位原始
            assertEquals("3500309111002222", data2.getCell(1).getStringCellValue());
        }
    }

    private static InventoryTask makeTask(String name) {
        InventoryTask t = new InventoryTask();
        t.sourceFileName = name + ".xlsx";
        t.importedAt = System.currentTimeMillis();
        t.expectedCount = 1;
        t.status = "IN_PROGRESS";
        return t;
    }

    private static Asset makeAsset(long taskId, String epc) {
        Asset a = new Asset();
        a.taskId = taskId;
        a.excelRowIndex = 1;
        a.epc = epc;
        a.rawRowJson = "{\"设备名称\":\"Asset\",\"固资编号\":\"" + epc + "\"}";
        return a;
    }

    private static ScanRecord scan(long taskId, String epc, long ts, Long matchedId) {
        return scan(taskId, epc, ts, matchedId, null);
    }

    private static ScanRecord scan(long taskId, String epc, long ts, Long matchedId, String tid) {
        ScanRecord r = new ScanRecord();
        r.taskId = taskId;
        r.epc = epc;
        r.tid = tid;
        r.scannedAt = ts;
        r.rssi = "-50";
        r.matchedAssetId = matchedId;
        return r;
    }
}

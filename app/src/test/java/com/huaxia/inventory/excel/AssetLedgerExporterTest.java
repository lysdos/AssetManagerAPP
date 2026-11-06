package com.huaxia.inventory.excel;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.dao.AssetDao;
import com.huaxia.inventory.data.entity.Asset;

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 资产台账导出：列顺序、展示形态、盘点日期、筛选条数.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class AssetLedgerExporterTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private AppDatabase db;
    private AssetDao dao;

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries().build();
        dao = db.assetDao();
    }

    @After
    public void tearDown() {
        db.close();
    }

    @Test
    public void exportAll_writesMockupColumnsAndDisplayEpc() throws Exception {
        insertAsset("000000003500309099027210", "电脑", "ThinkPad", "SN-1",
                "财务部", "张三", "2026-10-01");
        insertAsset("000000003500309111002222", "打印机", "HP-1020", "SN-2",
                "行政部", "李四", "2026-10-02");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int count = AssetLedgerExporter.export(out, dao, "%", null, null, tmp.getRoot());
        assertEquals(2, count);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            assertEquals("资产台账", wb.getSheetName(0));
            Sheet sheet = wb.getSheetAt(0);
            assertHeader(sheet.getRow(0));

            Row first = sheet.getRow(1);
            assertNotNull(first);
            assertEquals(1, (int) first.getCell(0).getNumericCellValue());
            assertEquals("电脑", first.getCell(1).getStringCellValue());
            assertEquals("ThinkPad", first.getCell(2).getStringCellValue());
            assertEquals("SN-1", first.getCell(3).getStringCellValue());
            assertEquals("3500309099027210", first.getCell(4).getStringCellValue());
            assertEquals("财务部", first.getCell(5).getStringCellValue());
            assertEquals("张三", first.getCell(6).getStringCellValue());
            assertEquals("2026-10-01", first.getCell(7).getStringCellValue());

            Row second = sheet.getRow(2);
            assertNotNull(second);
            assertEquals("打印机", second.getCell(1).getStringCellValue());
            assertEquals("2026-10-02", second.getCell(7).getStringCellValue());
        }
    }

    @Test
    public void exportFiltered_onlyWritesMatchingRows() throws Exception {
        insertAsset("000000003500309099027210", "电脑", "ThinkPad", "SN-1",
                "财务部", "张三", "2026-10-01");
        insertAsset("000000003500309111002222", "打印机", "HP-1020", "SN-2",
                "行政部", "李四", "2026-10-02");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int count = AssetLedgerExporter.export(out, dao, "%电脑%", null, null, tmp.getRoot());
        assertEquals(1, count);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            assertEquals(1, sheet.getLastRowNum());
            assertEquals("电脑", sheet.getRow(1).getCell(1).getStringCellValue());
        }
    }

    @Test
    public void exportEmpty_keepsHeaderOnly() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int count = AssetLedgerExporter.export(out, dao, "%", null, null, tmp.getRoot());
        assertEquals(0, count);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            assertEquals(0, sheet.getLastRowNum());
            assertHeader(sheet.getRow(0));
        }
    }

    @Test
    public void export_missingRawDate_writesBlank() throws Exception {
        insertAsset("000000003500309099027210", "电脑", "ThinkPad", "SN-1",
                "财务部", "张三", null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AssetLedgerExporter.export(out, dao, "%", null, null, tmp.getRoot());

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Row row = wb.getSheetAt(0).getRow(1);
            assertNotNull(row);
            assertTrue(row.getCell(7) == null
                    || row.getCell(7).getStringCellValue().isEmpty());
        }
    }

    private void assertHeader(Row header) {
        assertNotNull(header);
        for (int i = 0; i < AssetLedgerExporter.HEADERS.size(); i++) {
            assertEquals(AssetLedgerExporter.HEADERS.get(i),
                    header.getCell(i).getStringCellValue());
        }
    }

    private Asset insertAsset(String epc, String type, String model, String serial,
                              String dept, String custodian, String date) {
        Asset a = new Asset();
        a.taskId = 0;
        a.excelRowIndex = 1;
        a.epc = epc;
        a.assetType = type;
        a.assetModel = model;
        a.serialNumber = serial;
        a.dept = dept;
        a.custodian = custodian;
        a.rawRowJson = date == null ? "{}"
                : "{\"盘点日期\":\"" + date + "\"}";
        dao.insert(a);
        return a;
    }
}

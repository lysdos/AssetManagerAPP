package com.huaxia.inventory.data;

import static org.assertj.core.api.Assertions.assertThat;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.framework.FrameworkSQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;

@RunWith(RobolectricTestRunner.class)
public class Migration1To2Test {

    private File dbFile;
    private SQLiteDatabase raw;

    @Before
    public void setUp() {
        File dir = RuntimeEnvironment.getApplication().getCacheDir();
        dbFile = new File(dir, "migration-test.db");
        if (dbFile.exists() && !dbFile.delete()) {
            throw new IllegalStateException("cannot reset test db");
        }
        raw = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        createV1Schema();
        seedV1Data();
    }

    @After
    public void tearDown() {
        raw.close();
    }

    private void createV1Schema() {
        raw.execSQL("CREATE TABLE IF NOT EXISTS `InventoryTask` ("
                + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                + "`sourceFileName` TEXT, `importedAt` INTEGER NOT NULL,"
                + "`status` TEXT, `dept` TEXT, `expectedCount` INTEGER NOT NULL,"
                + "`scannedUniqueCount` INTEGER NOT NULL, `remark` TEXT)");
        raw.execSQL("CREATE TABLE IF NOT EXISTS `Asset` ("
                + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                + "`taskId` INTEGER NOT NULL, `excelRowIndex` INTEGER NOT NULL,"
                + "`epc` TEXT NOT NULL, `assetType` TEXT, `assetModel` TEXT,"
                + "`serialNumber` TEXT, `custodian` TEXT, `dept` TEXT,"
                + "`raw_row_json` TEXT, `inventory_time` INTEGER, `created_at` INTEGER,"
                + "`orig_epc` TEXT, `orig_asset_type` TEXT, `orig_asset_model` TEXT,"
                + "`orig_serial_number` TEXT, `orig_custodian` TEXT, `orig_dept` TEXT,"
                + "`orig_raw_row_json` TEXT)");
        raw.execSQL("CREATE TABLE IF NOT EXISTS `ScanRecord` ("
                + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                + "`taskId` INTEGER NOT NULL, `epc` TEXT NOT NULL, `tid` TEXT,"
                + "`rssi` TEXT, `scannedAt` INTEGER NOT NULL, `matchedAssetId` INTEGER)");
        raw.execSQL("CREATE TABLE IF NOT EXISTS `ExtraClaim` ("
                + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                + "`scanRecordId` INTEGER NOT NULL, `epc` TEXT NOT NULL,"
                + "`assetType` TEXT, `assetModel` TEXT, `serialNumber` TEXT,"
                + "`custodian` TEXT, `dept` TEXT, `claimedAt` INTEGER)");
    }

    private void seedV1Data() {
        // 任务 1：已完成。资产 1 已盘，资产 2 盘亏，另有一条盘盈（已补全）
        raw.execSQL("INSERT INTO InventoryTask (id, sourceFileName, importedAt, status, "
                + "expectedCount, scannedUniqueCount) "
                + "VALUES (1, '已完成任务', 100, 'COMPLETED', 2, 1)");
        raw.execSQL("INSERT INTO Asset (id, taskId, excelRowIndex, epc, dept, inventory_time) "
                + "VALUES (1, 1, 1, 'E1', '行政部', 555)");
        raw.execSQL("INSERT INTO Asset (id, taskId, excelRowIndex, epc, dept) "
                + "VALUES (2, 1, 2, 'E2', '行政部')");
        raw.execSQL("INSERT INTO ScanRecord (id, taskId, epc, scannedAt, matchedAssetId) "
                + "VALUES (1, 1, 'E1', 200, 1)");
        raw.execSQL("INSERT INTO ScanRecord (id, taskId, epc, scannedAt, matchedAssetId) "
                + "VALUES (2, 1, 'X9', 210, NULL)");
        raw.execSQL("INSERT INTO ExtraClaim (scanRecordId, epc, assetType, dept) "
                + "VALUES (2, 'X9', '打印机', '财务部')");

        // 任务 2：进行中。资产 3 必须原样留在任务下
        raw.execSQL("INSERT INTO InventoryTask (id, sourceFileName, importedAt, status, "
                + "expectedCount, scannedUniqueCount) "
                + "VALUES (2, '进行中任务', 300, 'IN_PROGRESS', 1, 0)");
        raw.execSQL("INSERT INTO Asset (id, taskId, excelRowIndex, epc, dept) "
                + "VALUES (3, 2, 1, 'E3', '技术部')");
    }

    private void migrate() {
        SupportSQLiteDatabase support = new FrameworkSQLiteDatabase(raw);
        AppDatabase.MIGRATION_1_2.migrate(support);
    }

    private int intOf(String sql) {
        try (Cursor c = raw.rawQuery(sql, null)) {
            return c.moveToFirst() ? c.getInt(0) : -1;
        }
    }

    private String strOf(String sql) {
        try (Cursor c = raw.rawQuery(sql, null)) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    @Test
    public void backfillsFoundAndMissing() {
        migrate();

        assertThat(intOf("SELECT COUNT(*) FROM TaskResult WHERE taskId = 1 AND status = 'FOUND'"))
                .isEqualTo(1);
        assertThat(intOf("SELECT COUNT(*) FROM TaskResult WHERE taskId = 1 AND status = 'MISSING'"))
                .isEqualTo(1);
        assertThat(strOf("SELECT epc FROM TaskResult WHERE taskId = 1 AND status = 'FOUND'"))
                .isEqualTo("E1");
        assertThat(intOf("SELECT inventoryTime FROM TaskResult "
                + "WHERE taskId = 1 AND status = 'FOUND'")).isEqualTo(555);
    }

    @Test
    public void backfillsExtraWithClaimFields() {
        migrate();

        assertThat(intOf("SELECT COUNT(*) FROM TaskResult WHERE taskId = 1 AND status = 'EXTRA'"))
                .isEqualTo(1);
        assertThat(strOf("SELECT assetType FROM TaskResult WHERE status = 'EXTRA'"))
                .isEqualTo("打印机");
        assertThat(strOf("SELECT dept FROM TaskResult WHERE status = 'EXTRA'"))
                .isEqualTo("财务部");
    }

    @Test
    public void releasesAssetsOfCompletedTasksOnly() {
        migrate();

        assertThat(intOf("SELECT taskId FROM Asset WHERE id = 1")).isZero();
        assertThat(intOf("SELECT taskId FROM Asset WHERE id = 2")).isZero();
        assertThat(strOf("SELECT inventory_time FROM Asset WHERE id = 1")).isNull();

        assertThat(intOf("SELECT taskId FROM Asset WHERE id = 3")).isEqualTo(2);
    }

    @Test
    public void deletesScanRecordsOfCompletedTasks() {
        migrate();

        assertThat(intOf("SELECT COUNT(*) FROM ScanRecord WHERE taskId = 1")).isZero();
        assertThat(intOf("SELECT COUNT(*) FROM ExtraClaim")).isZero();
    }
}

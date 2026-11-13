package com.huaxia.inventory.data;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.huaxia.inventory.data.dao.AssetDao;
import com.huaxia.inventory.data.dao.ExtraClaimDao;
import com.huaxia.inventory.data.dao.InventoryTaskDao;
import com.huaxia.inventory.data.dao.ScanRecordDao;
import com.huaxia.inventory.data.dao.TaskResultDao;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ExtraClaim;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.data.entity.TaskResult;

/**
 * 本地数据库。
 *
 * <p><b>{@code v1} 是首次发布基线</b> —— 交付前把开发期迭代出来的 v2..v11 迁移链
 * 整体收敛掉了: 那些版本号从未交付给任何用户, 维护它们只有成本没有收益
 * (其中 2→3→4→5 还是缺口, 只能靠 destructive fallback 兜底)。表结构本身没变,
 * 全新安装直接按实体定义建库。</p>
 *
 * <p><b>{@code v2}</b> 新增 {@link TaskResult} 表：完成任务时把该次盘点结果冻结一份，
 * 从而可以把资产释放回库存池而历史报告不变。</p>
 *
 * <p><b>发布之后</b>每次改表结构: {@code version} +1, 在 {@code AppDatabase} 里声明
 * {@code MIGRATION_(n-1)_n}, 并在 {@code InventoryApp} 的 builder 里注册 —— 两者缺一
 * {@code DatabaseMigrationChainGuardTest} 会失败。配合 {@code InventoryApp} 使用的
 * {@code fallbackToDestructiveMigrationOnDowngrade()}, "漏写迁移"会在升级时直接抛异常
 * 而不是静默清空用户数据。</p>
 */
@Database(
        entities = {InventoryTask.class, Asset.class, ScanRecord.class, ExtraClaim.class,
                TaskResult.class},
        version = 2,
        exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    public abstract InventoryTaskDao inventoryTaskDao();
    public abstract AssetDao assetDao();
    public abstract ScanRecordDao scanRecordDao();
    public abstract ExtraClaimDao extraClaimDao();
    public abstract TaskResultDao taskResultDao();

    /**
     * v1 → v2：建 {@code TaskResult} 表，并把历史已完成任务整理成新语义。
     *
     * <p>顺序不可调换：<b>先回填快照，再释放资产 / 删扫描记录</b>，
     * 否则回填时数据已经被清掉。</p>
     */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `TaskResult` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                    + "`taskId` INTEGER NOT NULL,"
                    + "`rowIndex` INTEGER NOT NULL,"
                    + "`assetId` INTEGER,"
                    + "`epc` TEXT NOT NULL,"
                    + "`assetType` TEXT,"
                    + "`assetModel` TEXT,"
                    + "`serialNumber` TEXT,"
                    + "`custodian` TEXT,"
                    + "`dept` TEXT,"
                    + "`status` TEXT NOT NULL,"
                    + "`inventoryTime` INTEGER,"
                    + "`scannedAt` INTEGER,"
                    + "`rssi` TEXT,"
                    + "`rawRowJson` TEXT)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_TaskResult_taskId` "
                    + "ON `TaskResult` (`taskId`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_TaskResult_taskId_status` "
                    + "ON `TaskResult` (`taskId`, `status`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_TaskResult_taskId_rowIndex` "
                    + "ON `TaskResult` (`taskId`, `rowIndex`)");

            // 1) 已盘 / 盘亏
            db.execSQL("INSERT INTO TaskResult (taskId, rowIndex, assetId, epc, assetType, "
                    + "assetModel, serialNumber, custodian, dept, status, inventoryTime, rawRowJson) "
                    + "SELECT a.taskId, a.excelRowIndex, a.id, a.epc, a.assetType, a.assetModel, "
                    + "a.serialNumber, a.custodian, a.dept, "
                    + "CASE WHEN EXISTS (SELECT 1 FROM ScanRecord s "
                    + "  WHERE s.taskId = a.taskId AND s.matchedAssetId = a.id) "
                    + "  THEN 'FOUND' ELSE 'MISSING' END, "
                    + "a.inventory_time, a.raw_row_json "
                    + "FROM Asset a JOIN InventoryTask t ON t.id = a.taskId "
                    + "WHERE t.status = 'COMPLETED'");

            // 2) 盘盈（业务字段取 ExtraClaim 的补全值）
            db.execSQL("INSERT INTO TaskResult (taskId, rowIndex, assetId, epc, assetType, "
                    + "assetModel, serialNumber, custodian, dept, status, scannedAt, rssi) "
                    + "SELECT s.taskId, 0, NULL, COALESCE(c.epc, s.epc), c.assetType, "
                    + "c.assetModel, c.serialNumber, c.custodian, c.dept, 'EXTRA', "
                    + "s.scannedAt, s.rssi "
                    + "FROM ScanRecord s JOIN InventoryTask t ON t.id = s.taskId "
                    + "LEFT JOIN ExtraClaim c ON c.scanRecordId = s.id "
                    + "WHERE t.status = 'COMPLETED' AND s.matchedAssetId IS NULL");

            // 3) 释放历史已完成任务的资产，清掉本次盘点的痕迹
            db.execSQL("UPDATE Asset SET taskId = 0, inventory_time = NULL, "
                    + "orig_epc = NULL, orig_asset_type = NULL, orig_asset_model = NULL, "
                    + "orig_serial_number = NULL, orig_custodian = NULL, orig_dept = NULL, "
                    + "orig_raw_row_json = NULL "
                    + "WHERE taskId IN (SELECT id FROM InventoryTask WHERE status = 'COMPLETED')");

            // 4) 历史已完成任务的扫描/补全记录作废（历史已在 TaskResult 里）
            db.execSQL("DELETE FROM ExtraClaim WHERE scanRecordId IN ("
                    + "SELECT s.id FROM ScanRecord s JOIN InventoryTask t ON t.id = s.taskId "
                    + "WHERE t.status = 'COMPLETED')");
            db.execSQL("DELETE FROM ScanRecord WHERE taskId IN "
                    + "(SELECT id FROM InventoryTask WHERE status = 'COMPLETED')");
        }
    };
}

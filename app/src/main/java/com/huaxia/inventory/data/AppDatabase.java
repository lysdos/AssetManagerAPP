package com.huaxia.inventory.data;

import android.util.Log;
import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import com.huaxia.inventory.data.dao.AssetDao;
import com.huaxia.inventory.data.dao.ExtraClaimDao;
import com.huaxia.inventory.data.dao.InventoryTaskDao;
import com.huaxia.inventory.data.dao.ScanRecordDao;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ExtraClaim;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.data.entity.ScanRecord;

@Database(
        entities = {InventoryTask.class, Asset.class, ScanRecord.class, ExtraClaim.class},
        version = 9,
        exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static final String TAG = "AppDatabase";

    public abstract InventoryTaskDao inventoryTaskDao();
    public abstract AssetDao assetDao();
    public abstract ScanRecordDao scanRecordDao();
    public abstract ExtraClaimDao extraClaimDao();

    /**
     * v1 → v2: Asset 表新增 inventory_time 列（盘点时间，毫秒），可空。
     * 现有行 inventory_time = NULL，等用户首次在结果页点开弹窗保存后再回填。
     */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE Asset ADD COLUMN inventory_time INTEGER");
        }
    };

    /**
     * v5 → v6: ScanRecord 加复合索引 {@code (taskId, matchedAssetId)},
     * 让 {@code InventoryTaskDao.recomputeScannedCount} 的
     * {@code COUNT(DISTINCT matchedAssetId) WHERE taskId=? AND matchedAssetId IS NOT NULL}
     * 走 index-only scan. 表数据 / 唯一索引均不动, 只追加新索引 — 用
     * {@code IF NOT EXISTS} 保证幂等, 重跑迁移不会失败.
     * <p>索引名沿用 Room 默认命名 {@code index_ScanRecord_taskId_matchedAssetId},
     * 由 {@code @Index(value = {"taskId", "matchedAssetId"})} 自动生成.</p>
     *
     * <p><b>历史</b>: 2026-07-24 之前, 这里还有过 v3, v4, v5 等几个版本号, 期间
     * 因 {@code @Entity} 索引变更 (ExtraClaim 表新增, epc 改全局 UNIQUE 等)
     * 需要 schema 变更, 但实际上没有迁移路径 — 因为这两个版本号范围既没有
     * 上线用户, 也没有生产数据需要保护, 升级时会走
     * {@code fallbackToDestructiveMigration()} 全表重建. 后来清理掉了
     * 占位 migration, 仅保留 v1→v2 / v5→v6 真正注册的版本.</p>
     */
    public static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            try {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ScanRecord_taskId_matchedAssetId` "
                        + "ON `ScanRecord` (`taskId`, `matchedAssetId`)");
                Log.i(TAG, "[AppDatabase] MIGRATION_5_6 applied: created index_ScanRecord_taskId_matchedAssetId");
            } catch (Exception e) {
                // 不抛异常 — IF NOT EXISTS 已挡掉重跑情况; 其它 schema 漂移让
                // fallbackToDestructiveMigration 接管, 不要二次吞错.
                Log.e(TAG, "[AppDatabase] MIGRATION_5_6 failed", e);
                throw e;
            }
        }
    };

    /**
     * v6 → v7: Asset 表新增 created_at 列（首次导入时间，毫秒），可空。
     * 历史行升级后 created_at = NULL — 语义为"未知导入时间"，不参与扫描页
     * 「+N 今日」统计（{@code countCreatedAfter} 用 {@code created_at >= :since}，
     * NULL 不满足条件）。新导入资产由 {@code ExcelImporter} 写入流起始时间戳。
     * <p>ALTER TABLE ADD COLUMN 不支持 IF NOT EXISTS，但 Room 按版本号只会跑一次，
     * 不存在重跑问题（与 MIGRATION_5_6 用 IF NOT EXISTS 防重跑的动机不同）。</p>
     */
    public static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE Asset ADD COLUMN created_at INTEGER");
            Log.i(TAG, "[AppDatabase] MIGRATION_6_7 applied: added Asset.created_at");
        }
    };

    /**
     * v7 → v8: 补两条分页/排序复合索引（纯加索引，不动数据/字段）:
     * <ul>
     *   <li>{@code Asset (taskId, excelRowIndex)} — pageAll/pageFound/pageMissing 的
     *       ORDER BY, 以及 ExcelExporter 分页导出的每一页;</li>
     *   <li>{@code ScanRecord (taskId, scannedAt)} — pageExtra/pageRecent 的 ORDER BY。</li>
     * </ul>
     * 用 {@code IF NOT EXISTS} 保证幂等; 索引名沿用 Room 默认命名 ({@code @Index} 一致)。
     */
    public static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_Asset_taskId_excelRowIndex` "
                    + "ON `Asset` (`taskId`, `excelRowIndex`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ScanRecord_taskId_scannedAt` "
                    + "ON `ScanRecord` (`taskId`, `scannedAt`)");
            Log.i(TAG, "[AppDatabase] MIGRATION_7_8 applied: added pagination indexes");
        }
    };

    /**
     * v8 → v9: Asset 加复合索引 {@code (taskId, dept)} —— 服务 {@code countPoolByDept} /
     * {@code distinctDeptPool} / {@code distinctDept} 的 {@code WHERE taskId = 0 AND dept = ?}
     * 过滤 (建任务时按部门统计池内资产)。纯加索引, 不动数据/字段; 索引名沿用 Room 默认命名
     * {@code index_Asset_taskId_dept}, 由 {@code @Index(value = {"taskId","dept"})} 生成。
     */
    public static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_Asset_taskId_dept` "
                    + "ON `Asset` (`taskId`, `dept`)");
            Log.i(TAG, "[AppDatabase] MIGRATION_8_9 applied: added index_Asset_taskId_dept");
        }
    };
}

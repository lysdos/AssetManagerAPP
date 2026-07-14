package com.huaxia.inventory.data;

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
        version = 5,
        exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
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
     * v2/v3 → v4: 新增 ExtraClaim 表。
     * 走 fallbackToDestructiveMigration()，所以这里只保留占位 — 不需要手写 CREATE TABLE。
     */
    @SuppressWarnings("unused")
    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            // 不实现 — fallbackToDestructiveMigration 会处理。
        }
    };

    /**
     * v4 → v5: Asset.epc 改为单列 UNIQUE.
     * 走 fallbackToDestructiveMigration() — 全表 DROP 后按新 @Entity 索引重建.
     * 不手写迁移 (要 DROP 旧 UNIQUE 复合索引 + CREATE 新单列 UNIQUE 索引, 走框架更稳).
     */
    @SuppressWarnings("unused")
    public static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            // 不实现 — fallbackToDestructiveMigration 会处理。
        }
    };
}

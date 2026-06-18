package com.huaxia.inventory.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import com.huaxia.inventory.data.dao.AssetDao;
import com.huaxia.inventory.data.dao.InventoryTaskDao;
import com.huaxia.inventory.data.dao.ScanRecordDao;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.data.entity.ScanRecord;

@Database(
        entities = {InventoryTask.class, Asset.class, ScanRecord.class},
        version = 2,
        exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract InventoryTaskDao inventoryTaskDao();
    public abstract AssetDao assetDao();
    public abstract ScanRecordDao scanRecordDao();

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
}

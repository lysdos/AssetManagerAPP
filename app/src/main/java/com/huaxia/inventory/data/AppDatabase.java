package com.huaxia.inventory.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import com.huaxia.inventory.data.dao.AssetDao;
import com.huaxia.inventory.data.dao.ExtraClaimDao;
import com.huaxia.inventory.data.dao.InventoryTaskDao;
import com.huaxia.inventory.data.dao.ScanRecordDao;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ExtraClaim;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.data.entity.ScanRecord;

/**
 * 本地数据库。
 *
 * <p><b>{@code version = 1} 是首次发布基线</b> —— 交付前把开发期迭代出来的 v2..v11
 * 迁移链整体收敛掉了: 那些版本号从未交付给任何用户, 维护它们只有成本没有收益
 * (其中 2→3→4→5 还是缺口, 只能靠 destructive fallback 兜底)。表结构本身没变,
 * 全新安装直接按实体定义建库。</p>
 *
 * <p><b>发布之后</b>每次改表结构: {@code version} +1, 在 {@code AppDatabase} 里声明
 * {@code MIGRATION_(n-1)_n}, 并在 {@code InventoryApp} 的 builder 里注册 —— 两者缺一
 * {@code DatabaseMigrationChainGuardTest} 会失败。配合 {@code InventoryApp} 使用的
 * {@code fallbackToDestructiveMigrationOnDowngrade()}, "漏写迁移"会在升级时直接抛异常
 * 而不是静默清空用户数据。</p>
 */
@Database(
        entities = {InventoryTask.class, Asset.class, ScanRecord.class, ExtraClaim.class},
        version = 1,
        exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    public abstract InventoryTaskDao inventoryTaskDao();
    public abstract AssetDao assetDao();
    public abstract ScanRecordDao scanRecordDao();
    public abstract ExtraClaimDao extraClaimDao();
}

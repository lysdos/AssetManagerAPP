package com.huaxia.inventory.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import com.huaxia.inventory.data.entity.InventoryTask;
import java.util.List;

@Dao
public interface InventoryTaskDao {

    @Insert
    long insert(InventoryTask task);

    @Update
    void update(InventoryTask task);

    @Query("SELECT * FROM InventoryTask WHERE id = :id")
    InventoryTask findById(long id);

    @Query("SELECT * FROM InventoryTask ORDER BY importedAt DESC")
    LiveData<List<InventoryTask>> findAllLive();

    @Query("SELECT * FROM InventoryTask WHERE status = 'COMPLETED' ORDER BY importedAt DESC")
    List<InventoryTask> findAllCompleted();

    /**
     * 重算"已盘数"= 成功匹配到 Asset 的去重资产数。
     * 旧版本用 COUNT(*) FROM ScanRecord, 把盘盈 (matchedAssetId IS NULL) 也算进去,
     * 任务卡片会显示盘盈把分母撑爆 (例如 expected=19, scanned=20, 105%)。
     * 跟 ScanRecordDao.aggregateStats 的 matchedCount 语义对齐:
     *   COUNT(DISTINCT matchedAssetId) WHERE matchedAssetId IS NOT NULL
     */
    @Query("UPDATE InventoryTask SET scannedUniqueCount = (" +
           "  SELECT COUNT(DISTINCT matchedAssetId) FROM ScanRecord " +
           "  WHERE taskId = :taskId AND matchedAssetId IS NOT NULL" +
           ") WHERE id = :taskId")
    void recomputeScannedCount(long taskId);

    @Query("DELETE FROM InventoryTask WHERE id = :taskId")
    int deleteById(long taskId);

    @Query("DELETE FROM InventoryTask WHERE status = 'COMPLETED'")
    int deleteAllCompleted();
}
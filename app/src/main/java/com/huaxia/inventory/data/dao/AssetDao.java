package com.huaxia.inventory.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import com.huaxia.inventory.data.entity.Asset;
import java.util.List;

@Dao
public interface AssetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(Asset asset);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<Asset> assets);

    @Update
    void update(Asset asset);

    @Query("SELECT * FROM Asset WHERE taskId = :taskId AND epc = :epc LIMIT 1")
    Asset findByEpc(long taskId, String epc);

    @Query("SELECT COUNT(*) FROM Asset WHERE taskId = :taskId")
    int countByTask(long taskId);

    @Query("SELECT * FROM Asset WHERE taskId = :taskId ORDER BY excelRowIndex ASC")
    List<Asset> findAllByTask(long taskId);

    @Query("SELECT * FROM Asset WHERE taskId = :taskId AND id NOT IN " +
           "(SELECT matchedAssetId FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NOT NULL)")
    List<Asset> findMissing(long taskId);

    @Query("SELECT * FROM Asset WHERE taskId = :taskId AND id IN " +
           "(SELECT matchedAssetId FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NOT NULL) " +
           "ORDER BY excelRowIndex ASC")
    List<Asset> findFound(long taskId);

    @Query("SELECT assetType as category, COUNT(*) as count FROM Asset " +
           "WHERE taskId = :taskId AND assetType IS NOT NULL " +
           "GROUP BY assetType ORDER BY count DESC")
    java.util.List<CategoryCount> countByCategory(long taskId);

    @Query("SELECT COUNT(*) FROM Asset WHERE taskId = :taskId AND id IN " +
           "(SELECT matchedAssetId FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NOT NULL)")
    int countFound(long taskId);

    @Query("SELECT COUNT(*) FROM Asset WHERE taskId = :taskId AND id NOT IN " +
           "(SELECT matchedAssetId FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NOT NULL)")
    int countMissing(long taskId);

    @Query("SELECT * FROM Asset WHERE taskId = :taskId ORDER BY excelRowIndex ASC " +
           "LIMIT :limit OFFSET :offset")
    List<Asset> pageAll(long taskId, int limit, int offset);

    @Query("SELECT * FROM Asset WHERE taskId = :taskId AND id IN " +
           "(SELECT matchedAssetId FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NOT NULL) " +
           "ORDER BY excelRowIndex ASC LIMIT :limit OFFSET :offset")
    List<Asset> pageFound(long taskId, int limit, int offset);

    @Query("SELECT * FROM Asset WHERE taskId = :taskId AND id NOT IN " +
           "(SELECT matchedAssetId FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NOT NULL) " +
           "ORDER BY excelRowIndex ASC LIMIT :limit OFFSET :offset")
    List<Asset> pageMissing(long taskId, int limit, int offset);

    @Query("DELETE FROM Asset WHERE taskId = :taskId")
    int deleteByTask(long taskId);

    /**
     * 给所有"已扫到但 inventoryTime 还是 null"的资产, 写入它的首次扫描时间.
     * 调用时机: 进入结果页时 (ResultViewModel.bindToTask), 保证导出的「盘点时间」列
     * 对每条 found 资产都有值, 而不是只有手动改过弹窗的那几条.
     * <p>幂等: 已经有值的不会被覆盖.
     */
    @Query("UPDATE Asset SET inventory_time = (" +
           "  SELECT MIN(scannedAt) FROM ScanRecord " +
           "  WHERE ScanRecord.taskId = :taskId AND ScanRecord.matchedAssetId = Asset.id" +
           ") WHERE taskId = :taskId AND inventory_time IS NULL AND id IN (" +
           "  SELECT matchedAssetId FROM ScanRecord " +
           "  WHERE taskId = :taskId AND matchedAssetId IS NOT NULL" +
           ")")
    int backfillFoundInventoryTime(long taskId);
}

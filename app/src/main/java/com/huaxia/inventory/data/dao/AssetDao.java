package com.huaxia.inventory.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.huaxia.inventory.data.entity.Asset;
import java.util.List;

@Dao
public interface AssetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(Asset asset);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<Asset> assets);

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
}

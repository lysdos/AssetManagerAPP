package com.huaxia.inventory.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.huaxia.inventory.data.entity.ScanRecord;
import java.util.List;

@Dao
public interface ScanRecordDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(ScanRecord record);

    @Query("SELECT * FROM ScanRecord WHERE taskId = :taskId")
    List<ScanRecord> findAllByTask(long taskId);

    @Query("SELECT * FROM ScanRecord WHERE taskId = :taskId AND epc = :epc LIMIT 1")
    ScanRecord findByEpc(long taskId, String epc);

    @Query("SELECT epc FROM ScanRecord WHERE taskId = :taskId")
    List<String> findAllEpcsByTask(long taskId);

    @Query("SELECT * FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NULL")
    List<ScanRecord> findExtras(long taskId);

    @Query("SELECT COUNT(*) FROM ScanRecord WHERE taskId = :taskId")
    int countByTask(long taskId);
}

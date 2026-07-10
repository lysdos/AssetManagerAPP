package com.huaxia.inventory.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import com.huaxia.inventory.data.entity.ExtraClaim;
import java.util.List;

@Dao
public interface ExtraClaimDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(ExtraClaim claim);

    @Update
    void update(ExtraClaim claim);

    /**
     * 按 scanRecordId 查补全信息（每条 ScanRecord 至多 1 条）。
     * 配合 unique index {@code scanRecordId} 使用。
     */
    @Query("SELECT * FROM ExtraClaim WHERE scanRecordId = :scanRecordId LIMIT 1")
    ExtraClaim findByScanRecordId(long scanRecordId);

    @Query("SELECT * FROM ExtraClaim WHERE scanRecordId IN (:scanRecordIds)")
    List<ExtraClaim> findByScanRecordIds(List<Long> scanRecordIds);

    @Query("DELETE FROM ExtraClaim WHERE scanRecordId = :scanRecordId")
    int deleteByScanRecordId(long scanRecordId);

    @Query("DELETE FROM ExtraClaim WHERE scanRecordId IN " +
           "(SELECT id FROM ScanRecord WHERE taskId = :taskId)")
    int deleteByTask(long taskId);
}
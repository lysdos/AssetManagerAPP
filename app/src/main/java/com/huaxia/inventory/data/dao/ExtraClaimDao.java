package com.huaxia.inventory.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.huaxia.inventory.data.entity.ExtraClaim;
import java.util.List;

@Dao
public interface ExtraClaimDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(ExtraClaim claim);

    @Query("SELECT * FROM ExtraClaim WHERE scanRecordId IN (:scanRecordIds)")
    List<ExtraClaim> findByScanRecordIds(List<Long> scanRecordIds);

    @Query("DELETE FROM ExtraClaim WHERE scanRecordId IN ("
            + "SELECT id FROM ScanRecord WHERE taskId IN (:taskIds))")
    int deleteByTasks(java.util.List<Long> taskIds);

    @Query("DELETE FROM ExtraClaim WHERE scanRecordId = :scanRecordId")
    int deleteByScanRecordId(long scanRecordId);

    @Query("DELETE FROM ExtraClaim WHERE scanRecordId IN " +
           "(SELECT id FROM ScanRecord WHERE taskId = :taskId)")
    int deleteByTask(long taskId);

    /**
     * 清孤儿: scanRecordId 指向已不存在的 ScanRecord.id 的 ExtraClaim.
     * <p>必须在 {@link ScanRecordDao#deleteOrphans()} 之后调用 — ScanRecord 先清完,
     * ExtraClaim 才有"准确的不存在集合"可参考. 调用方负责顺序.</p>
     * <p>与 ScanRecord.deleteOrphans 一致: 兜底清理, 不产生新孤儿 (deleteByTask
     * 已在删除路径事务内).</p>
     * @return 实际删除条数
     */
    @Query("DELETE FROM ExtraClaim WHERE scanRecordId NOT IN (SELECT id FROM ScanRecord)")
    int deleteOrphans();
}
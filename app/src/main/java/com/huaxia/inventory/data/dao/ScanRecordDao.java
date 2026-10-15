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

    @Query("SELECT COUNT(*) FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NULL")
    int countExtras(long taskId);

    @Query("SELECT * FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NULL " +
           "ORDER BY scannedAt DESC LIMIT :limit OFFSET :offset")
    List<ScanRecord> pageExtra(long taskId, int limit, int offset);

    @Query("SELECT * FROM ScanRecord WHERE taskId = :taskId " +
           "ORDER BY scannedAt DESC LIMIT :limit")
    List<ScanRecord> pageRecent(long taskId, int limit);

    @Query("DELETE FROM ScanRecord WHERE taskId = :taskId")
    int deleteByTask(long taskId);

    /**
     * 单条删除盘盈记录 ({@code matchedAssetId IS NULL}). 带 {@code matchedAssetId IS NULL}
     * 守卫 — 只允许删盘盈, 误传已匹配行时静默不删, 避免把已盘到的资产记录删掉.
     * @return affected rows (0 = 不是盘盈行 / 不存在)
     */
    @Query("DELETE FROM ScanRecord WHERE id = :id AND matchedAssetId IS NULL")
    int deleteExtraById(long id);

    /**
     * 单次聚合查询，返回任务下：
     * <ul>
     *   <li>matchedCount：成功匹配到 Asset 的不同资产数（COUNT DISTINCT matchedAssetId）</li>
     *   <li>extraCount：未匹配的扫描记录数（盘盈）</li>
     *   <li>lastScannedAt：最近一次扫描时间戳（毫秒），无数据时为 0</li>
     * </ul>
     * 用于替代 {@code findAllByTask + 内存去重 + findExtras + findById} 多次全表扫描，
     * 复杂度从 O(n) 降到 O(1)。
     */
    @Query("SELECT " +
           "COUNT(DISTINCT matchedAssetId) AS matchedCount, " +
           "COALESCE(SUM(CASE WHEN matchedAssetId IS NULL THEN 1 ELSE 0 END), 0) AS extraCount, " +
           "COALESCE(MAX(scannedAt), 0) AS lastScannedAt " +
           "FROM ScanRecord WHERE taskId = :taskId")
    ScanAggregate aggregateStats(long taskId);

    /**
     * 该资产最早一次成功匹配的扫描时间（毫秒）。无匹配记录时返回 null，
     * 调用方据此决定是用首次扫描时间还是当前时间作为 Asset.inventoryTime。
     */
    @Query("SELECT MIN(scannedAt) FROM ScanRecord " +
           "WHERE taskId = :taskId AND matchedAssetId = :assetId")
    Long findFirstScanTime(long taskId, long assetId);

    /**
     * 清孤儿: matchedAssetId 指向已不存在的 Asset.id 的 ScanRecord.
     * <p>正常删除路径 (TaskListViewModel.deleteTask / clearCompleted) 已包 runInTransaction,
     * 不会产生新孤儿. 此方法是兜底 — 历史版本串行调用产生的孤儿 / 外部直接改 DB 导致的孤儿,
     * 启动时统一清.</p>
     * <p>必须在 {@link ExtraClaimDao#deleteOrphans()} 之前调用,
     * 因为 ExtraClaim.scanRecordId 是 ScanRecord.id 的 FK, 不能跨表联删.</p>
     * @return 实际删除条数 (Room 返回 affected rows)
     */
    @Query("DELETE FROM ScanRecord WHERE matchedAssetId IS NOT NULL " +
           "AND matchedAssetId NOT IN (SELECT id FROM Asset)")
    int deleteOrphans();
}

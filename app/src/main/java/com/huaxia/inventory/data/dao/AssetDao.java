package com.huaxia.inventory.data.dao;

import androidx.annotation.Nullable;
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
     * 任务下所有不重复的领用部门（DISTINCT + 排序）。
     * 保留旧方法供需要按任务隔离的场景（如导出筛选）。
     */
    @Query("SELECT DISTINCT dept FROM Asset WHERE taskId = :taskId AND dept IS NOT NULL ORDER BY dept")
    List<String> distinctDept(long taskId);

    /**
     * 全表所有不重复的领用部门（DISTINCT + 排序）。
     * 供 CreateTaskDialogFragment 给 Spinner 选项 —
     * 不限 taskId，因为导入历史跨任务也共享部门字典，
     * 新建任务时不希望 Spinner 为空。
     */
    @Query("SELECT DISTINCT dept FROM Asset WHERE dept IS NOT NULL ORDER BY dept")
    List<String> distinctDeptAll();

    @Query("SELECT COUNT(*) FROM Asset WHERE dept = :dept")
    int countByDept(String dept);

    @Query("SELECT COUNT(*) FROM Asset")
    int countAll();

    /**
     * 库存池资产数（taskId = 0）。
     */
    @Query("SELECT COUNT(*) FROM Asset WHERE taskId = 0")
    int countPool();

    /**
     * 库存池中指定部门的资产数（创建任务时预览）。
     */
    @Query("SELECT COUNT(*) FROM Asset WHERE taskId = 0 AND dept = :dept")
    int countPoolByDept(String dept);

    /**
     * 库存池中所有不重复的领用部门 — Spinner 选项来源。
     * 注意: 不查整张表, 仅显示"待划拨"资产中的部门, 已分配到任务的资产不影响 Spinner.
     */
    @Query("SELECT DISTINCT dept FROM Asset WHERE taskId = 0 AND dept IS NOT NULL ORDER BY dept")
    List<String> distinctDeptPool();

    /**
     * 把库存池中匹配部门的资产划拨到指定任务。
     * 调用时机: TaskListViewModel.createTask 插入任务后立即执行。
     * dept = NULL 表示不限部门(把整个库存池划过去).
     * 返回受影响行数供 UI 显示.
     */
    @Query("UPDATE Asset SET taskId = :newTaskId WHERE taskId = 0 " +
           "AND (:dept IS NULL OR dept = :dept)")
    int assignPoolToTask(long newTaskId, @Nullable String dept);

    /**
     * 把任务下资产释放回库存池(taskId = 0)。
     * 调用时机: ⋮ 重置 A → "释放资产回库存池".
     * 同步删除该任务的扫描记录(ScanRecord 引用 taskId 不再有效).
     */
    @Query("UPDATE Asset SET taskId = 0 WHERE taskId = :taskId")
    int releaseTaskToPool(long taskId);

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

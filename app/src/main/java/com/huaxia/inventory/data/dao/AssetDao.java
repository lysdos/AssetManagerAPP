package com.huaxia.inventory.data.dao;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import com.huaxia.inventory.data.entity.Asset;
import java.util.List;

@Dao
public interface AssetDao {

    // ---- 全局资产台账 (资产 tab) 的 SQL 片段 ----
    // 空串/NULL 统一归入 '' 桶, UI 再映射为「未填写部门 / 未分类」; 这样筛选参数
    // NULL = 不筛选, "" = 筛"未填写"这个真实桶, 两者不会混淆.
    String DEPT_BUCKET =
            "CASE WHEN TRIM(COALESCE(dept, '')) = '' THEN '' ELSE TRIM(dept) END";
    String TYPE_BUCKET =
            "CASE WHEN TRIM(COALESCE(assetType, '')) = '' THEN '' ELSE TRIM(assetType) END";

    /**
     * 关键词匹配 6 个字段. {@code keywordPattern} 由 ViewModel 构造为
     * {@code %kw%}, 并把 {@code ! % _} 转义为 {@code !! !% !_} (ESCAPE '!').
     */
    String KEYWORD_WHERE =
            "(COALESCE(epc, '') LIKE :keywordPattern ESCAPE '!' " +
            "OR COALESCE(assetType, '') LIKE :keywordPattern ESCAPE '!' " +
            "OR COALESCE(assetModel, '') LIKE :keywordPattern ESCAPE '!' " +
            "OR COALESCE(serialNumber, '') LIKE :keywordPattern ESCAPE '!' " +
            "OR COALESCE(dept, '') LIKE :keywordPattern ESCAPE '!' " +
            "OR COALESCE(custodian, '') LIKE :keywordPattern ESCAPE '!')";

    String FILTER_WHERE =
            KEYWORD_WHERE +
            " AND (:deptBucket IS NULL OR " + DEPT_BUCKET + " = :deptBucket)" +
            " AND (:typeBucket IS NULL OR " + TYPE_BUCKET + " = :typeBucket)";

    /**
     * 仅测试使用 —— <b>生产代码零调用, 不要拿它当写入入口</b>。
     * {@code INSERT OR REPLACE} 走 DELETE + INSERT: 会重置 {@code id}、清空整行
     * (含 7 个 {@code orig_*} 快照列与 {@code inventory_time} / {@code created_at}),
     * 并让引用旧 id 的 {@code ScanRecord.matchedAssetId} 变孤儿。
     * 生产写入一律走 {@link #upsertOne(Asset)} / {@link #upsertBatch(List)}。
     */
    @androidx.annotation.VisibleForTesting
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(Asset asset);

    /** 同 {@link #insert(Asset)} —— 仅测试使用, 生产代码零调用。 */
    @androidx.annotation.VisibleForTesting
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<Asset> assets);

    @Update
    int update(Asset asset);

    @Query("SELECT * FROM Asset WHERE taskId = :taskId AND epc = :epc LIMIT 1")
    Asset findByEpc(long taskId, String epc);

    /**
     * 全局 epc 查询 (不限 taskId). 给导入流程用 — 重复导入时按 epc 是否已存在决定
     * "新增"还是"覆盖更新"计数.
     * 注意 taskId 不会被这条查询过滤, 因为覆盖更新时要保留原 row 的 taskId.
     */
    @Query("SELECT * FROM Asset WHERE epc = :epc LIMIT 1")
    Asset findByEpcGlobal(String epc);

    /**
     * 批量预检: 给定 epc 集合, 返回数据库中已存在的 epc 子集.
     * 用于导入流程统计 "覆盖更新 N 条".
     * 单批 500 条调用一次, IN 子句压力可控 (SQLite 建议上限 1000).
     */
    @Query("SELECT epc FROM Asset WHERE epc IN (:epcs)")
    java.util.List<String> findExistingEpcs(java.util.List<String> epcs);

    /**
     * 重复导入时按 epc "存在则覆盖业务字段, 不存在则新增"。
     *
     * <p>覆盖字段: excelRowIndex / assetType / assetModel / serialNumber / custodian /
     * dept / rawRowJson。<b>明确保留</b> id / taskId / inventory_time / created_at —
     *   - id 不动 → ScanRecord.matchedAssetId FK 持续有效, 已盘点关联不丢.
     *   - taskId 不动 → 库存池划拨到任务后再导入不会把资产踢回 pool.
     *   - inventory_time 不动 → 盘点时间不被覆盖更新抹掉 (导出 Excel "盘点时间"列保留).
     *   - created_at 不动 → "首次导入时间"保留首见值, "今日新增" 按首次导入统计.
     *
     * <p><b>为何不用 UPSERT 语法</b> ({@code ON CONFLICT(epc) DO UPDATE}): 该语法需
     * SQLite 3.24+ (Android 10 / API 29), 而 minSdk = 26 (Android 8.0 的 SQLite 是 3.18)
     * — 旧机会在 prepare 阶段直接抛 {@code near "ON": syntax error}, 导入主路径全挂。
     * 改用 {@code INSERT OR IGNORE} + 条件 {@code UPDATE} 两步, 全版本可用。
     *
     * <p>也不能用 Room {@code @Upsert} / {@code INSERT OR REPLACE}: 走 DELETE 旧行 + INSERT,
     * 导致 id 变 + inventory_time / created_at 清空。
     */
    @androidx.room.Transaction
    default void upsertOne(Asset a) {
        long rowId = insertIgnore(a);   // 新行 → rowId >= 1; 已存在 → -1 (被 IGNORE)
        if (rowId == -1L) {
            updateBusinessFieldsByEpc(a.epc, a.excelRowIndex, a.assetType,
                    a.assetModel, a.serialNumber, a.custodian, a.dept, a.rawRowJson);
        }
    }

    /**
     * 插入新行; epc 冲突时静默忽略 (返回 -1)。给 {@link #upsertOne(Asset)} 当
     * "插入或探测已存在" 用 — 返回值即冲突信号, 省一次 SELECT。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertIgnore(Asset asset);

    /**
     * 按 epc 覆盖业务字段 (不含 id / taskId / inventory_time / created_at)。
     * @return 受影响行数 (0 = 无该 epc)
     */
    @Query("UPDATE Asset SET " +
           "excelRowIndex = :excelRowIndex, " +
           "assetType = :assetType, " +
           "assetModel = :assetModel, " +
           "serialNumber = :serialNumber, " +
           "custodian = :custodian, " +
           "dept = :dept, " +
           "raw_row_json = :rawRowJson " +
           "WHERE epc = :epc")
    int updateBusinessFieldsByEpc(String epc, int excelRowIndex, String assetType,
                                  String assetModel, String serialNumber,
                                  String custodian, String dept, String rawRowJson);

    /**
     * 批量 upsert, 走单事务. 每批 500 条调用一次, 性能 ≈ 单批 INSERT 同量级。
     * @Transaction 让 Room 框架包 BEGIN/COMMIT, 中途失败整体回滚。
     */
    @androidx.room.Transaction
    default void upsertBatch(java.util.List<Asset> assets) {
        for (Asset a : assets) {
            upsertOne(a);
        }
    }

    /**
     * 仅投影 found Asset 的 id — 给 ExcelExporter 等只需要"哪些 id 算 found"
     * 的场景使用, 比 {@link #findFound(long)} 全表加载 Asset 行 (含
     * {@code rawRowJson} ~500B-1KB/行) 省内存 + IO. 30w 任务下原来这一行
     * 就 OOM (~150MB); 改用纯 id 投影后峰值 ~30w * 8B = 2.4MB.
     *
     * <p>走 {@code (taskId, matchedAssetId)} 复合索引, O(1) 子查询.</p>
     */
    @Query("SELECT Asset.id FROM Asset " +
           "WHERE Asset.taskId = :taskId AND Asset.id IN (" +
           "  SELECT matchedAssetId FROM ScanRecord " +
           "  WHERE ScanRecord.taskId = :taskId AND ScanRecord.matchedAssetId IS NOT NULL" +
           ")")
    java.util.List<Long> findFoundIds(long taskId);

    @Query("SELECT COUNT(*) FROM Asset WHERE taskId = :taskId")
    int countByTask(long taskId);

    /**
     * 本任务内首次导入时间 >= :sinceMillis 的资产数（扫描页「在库设备」副标「+N 今日」）。
     * 口径 = 今天首次进入库存池的本任务资产数。created_at 为 NULL 的历史行不计入
     * （NULL >= 值 结果为 NULL，不满足 WHERE）。
     * @param sinceMillis 自然日窗口起点，调用方传 {@code DateUtil.startOfToday()}
     */
    @Query("SELECT COUNT(*) FROM Asset WHERE taskId = :taskId AND created_at >= :sinceMillis")
    int countCreatedAfter(long taskId, long sinceMillis);

    @Query("SELECT * FROM Asset WHERE taskId = :taskId AND id IN " +
           "(SELECT matchedAssetId FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NOT NULL) " +
           "ORDER BY excelRowIndex ASC")
    List<Asset> findFound(long taskId);

    /** 任务下全部资产（按原表行序），供完成任务时写冻结快照。 */
    @Query("SELECT * FROM Asset WHERE taskId = :taskId ORDER BY excelRowIndex ASC")
    List<Asset> findAllByTask(long taskId);

    @Query("SELECT assetType as category, COUNT(*) as count FROM Asset " +
           "WHERE taskId = :taskId AND assetType IS NOT NULL " +
           "GROUP BY assetType ORDER BY count DESC")
    java.util.List<CategoryCount> countByCategory(long taskId);

    /**
     * 全量台账的部门分布（含库存池 + 已划拨），供 Web 大屏使用。
     * 复用 {@link #DEPT_BUCKET} 让空值归入 '' 桶，UI 再映射成「未填写」。
     */
    @Query("SELECT " + DEPT_BUCKET + " AS category, COUNT(*) AS count FROM Asset " +
           "GROUP BY " + DEPT_BUCKET + " ORDER BY count DESC")
    java.util.List<CategoryCount> countByDeptGrouped();

    /** 全量台账的设备类型分布（含库存池 + 已划拨），供 Web 大屏使用。 */
    @Query("SELECT " + TYPE_BUCKET + " AS category, COUNT(*) AS count FROM Asset " +
           "GROUP BY " + TYPE_BUCKET + " ORDER BY count DESC")
    java.util.List<CategoryCount> countByCategoryAll();

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
     *
     * <h4>契约</h4>
     * <ul>
     *   <li><b>幂等</b>: 已有 {@code inventory_time} 值的行不会被覆盖.
     *       {@code UPDATE ... AND inventory_time IS NULL} 守卫, 加 GROUP BY 一次性
     *       完成扫描端时间聚合.</li>
     *   <li><b>数据来源</b>: 写的是 {@code MIN(ScanRecord.scannedAt)}, 即该资产在
     *       该任务下首次被扫到的时间. NULL 扫描 (ScanRecord.matchedAssetId 落空
     *       的盘盈) 不会参与, 因为主查询限定 {@code matchedAssetId IS NOT NULL}.</li>
     *   <li><b>并发</b>: 整体走 {@code @Transaction} 单事务, 用 SQLite 行级锁 +
     *       brief UPDATE. 不会被另一线程的 {@code ResultFragment.onAssetDetailSaved}
     *       (把 inventoryTime 写成"系统当前时间") 切到错误值, 因为
     *       {@code ResultFragment.onAssetDetailSaved} 仅在 {@code inventory_time IS
     *       NULL} 行才被 backfill 之后, UI 才会"看似写" — 实际是两个路径先到先得,
     *       后到查 inventoryTime 已被对方填.</li>
     *   <li><b>边界</b>: 任务下没有 found 资产 → SELECT 0 行, UPDATE 0 行,
     *       返回 0; 任务被删除 → ScanRecord 外键已清空, 主查询返 0 行, 不影响其它任务.</li>
     *   <li><b>未覆盖的边界</b>: 极小概率下 {@code MIN(scannedAt)} 因 ScanRecord
     *       行被并发 deleteOrphans 清掉返 NULL — 当前实现 update 会被忽略
     *       (因为 inventory_time 仍为 NULL 且无源). 这是已知 trade-off, 不影响
     *       导出 — 导出"盘点时间"列对这种资产会留空, UI 会标"未填".</li>
     * </ul>
     *
     * <h4>实现</h4>
     * <p>两步: (1) {@code SELECT matchedAssetId AS assetId, MIN(scannedAt) AS
     * minScannedAt FROM ScanRecord WHERE taskId=:taskId AND matchedAssetId IS
     * NOT NULL GROUP BY matchedAssetId} 走 {@code (taskId, matchedAssetId)} 复合
     * 索引; (2) 逐条 {@code UPDATE Asset SET inventory_time=:ts WHERE id=:id AND
     * inventory_time IS NULL}. 原合约是单条 UPDATE + 相关子查询 — 1w found
     * 资产要跑 1w 次 ScanRecord 索引扫描, UPDATE 长持锁会阻塞 RFID 写入.</p>
     */
    @androidx.room.Transaction
    default int backfillFoundInventoryTime(long taskId) {
        java.util.List<AssetFoundMinScanTime> rows = findFoundMinScanTimes(taskId);
        int updated = 0;
        for (AssetFoundMinScanTime r : rows) {
            int n = setInventoryTimeIfNull(r.assetId, r.minScannedAt);
            updated += n;
        }
        return updated;
    }

    /**
     * 投影 (matchedAssetId AS assetId, MIN(scannedAt) AS minScannedAt).
     * 字段名与 SELECT 列名严格对应; 投影类型在
     * {@link AssetFoundMinScanTime} (顶级 POJO, Room 投影规范).
     */
    @Query("SELECT matchedAssetId AS assetId, MIN(scannedAt) AS minScannedAt " +
           "FROM ScanRecord " +
           "WHERE taskId = :taskId AND matchedAssetId IS NOT NULL " +
           "GROUP BY matchedAssetId")
    java.util.List<AssetFoundMinScanTime> findFoundMinScanTimes(long taskId);

    /**
     * 幂等回填单条: 已经写过 inventory_time 的行不再覆盖.
     * 走 Asset PK 短持锁 — 不再内嵌子查询, 故 1w found 不会让 UPDATE 持锁过久.
     */
    @Query("UPDATE Asset SET inventory_time = :ts " +
           "WHERE id = :id AND inventory_time IS NULL")
    int setInventoryTimeIfNull(long id, long ts);

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
     * 把库存池中匹配部门的资产划拨到指定任务, 并在同一语句里**打快照** —
     * 把当前业务字段 (epc / assetType / assetModel / serialNumber / custodian /
     * dept / raw_row_json) 存进 orig_* 列, 供"删除/重置任务"回滚。
     * 调用时机: TaskListViewModel.createTask 插入任务后立即执行。
     * dept = NULL 表示不限部门(把整个库存池划过去).
     * 返回受影响行数供 UI 显示.
     */
    @Query("UPDATE Asset SET taskId = :newTaskId, " +
           "orig_epc = epc, orig_asset_type = assetType, orig_asset_model = assetModel, " +
           "orig_serial_number = serialNumber, orig_custodian = custodian, " +
           "orig_dept = dept, orig_raw_row_json = raw_row_json " +
           "WHERE taskId = 0 " +
           "AND (:dept IS NULL OR dept = :dept)")
    int assignPoolToTask(long newTaskId, @Nullable String dept);

    /**
     * 把任务下资产释放回库存池(taskId = 0)。
     * 调用时机: ⋮ 菜单 "删除任务" (TaskListViewModel.deleteTask) / 清除已完成.
     * 同步删除该任务的扫描记录 + 补全记录 (引用 taskId 不再有效).
     */
    @Query("UPDATE Asset SET taskId = 0 WHERE taskId = :taskId")
    int releaseTaskToPool(long taskId);

    /**
     * 回滚该任务下"被编辑过"资产的业务字段 (从划拨时的快照还原)。
     * 调用时机: 删除进行中/暂停任务 (TaskListViewModel.deleteTask)、重置扫描进度
     * (ScanViewModel.resetScanProgress) —— 都是"放弃/重来本次盘点"的语义。
     *
     * <p>哨兵列 {@code orig_epc IS NOT NULL} 表示"该行有快照" (划拨时必写 epc);
     * 未划拨 / 已提交 / 历史行快照为空, 不受影响。</p>
     *
     * <p>用 {@code UPDATE OR IGNORE}: {@code epc} 有全局唯一索引, 极端情况下 (同任务内
     * 两行互换固资编号后再回滚) 普通 UPDATE 会因唯一键冲突抛 SQLiteConstraintException
     * → 整个删除/重置事务回滚 → 操作失败。OR IGNORE 跳过错冲突行 (保留其新值), 事务不中断。</p>
     *
     * <p>注意: 快照口径 = 划拨时刻的池值。某资产划拨后若在"进行中"被重新导入覆盖,
     * 回滚会把它拉回划拨时刻的值 (重导的主数据被丢弃) —— 既定口径。</p>
     */
    @Query("UPDATE OR IGNORE Asset SET " +
           "epc = orig_epc, assetType = orig_asset_type, assetModel = orig_asset_model, " +
           "serialNumber = orig_serial_number, custodian = orig_custodian, dept = orig_dept, " +
           "raw_row_json = orig_raw_row_json " +
           "WHERE taskId = :taskId AND orig_epc IS NOT NULL")
    int revertEditedFields(long taskId);

    /**
     * 清该任务下**全部**资产的盘点时间 (含未编辑但被 backfill 写过的行)。
     * 调用时机: 资产"离开任务回池" (删除任务 / 清除已完成) 或"重置扫描进度" ——
     * 盘点时间是"本次盘点"的数据, 离开/重来后即失效; 不清则会被下一任务的
     * {@link #backfillFoundInventoryTime(long)} (带 {@code inventory_time IS NULL} 守卫)
     * 挡掉, 导出抄到旧时间。完成任务(提交)不清 —— 任务还在, 时间留给导出。
     */
    @Query("UPDATE Asset SET inventory_time = NULL WHERE taskId = :taskId")
    int clearInventoryTimeForTask(long taskId);

    /**
     * 丢弃该任务的快照 (把 7 个 orig_* 置 NULL) —— "提交 / 放弃"收尾。
     * 调用时机: 完成任务 (TaskListViewModel.completeTask, = 提交) 与删除任务。
     * 必须在 {@link #releaseTaskToPool(long)} **之前**调用 —— 后者把 taskId 归零后,
     * 本方法按 taskId 过滤会匹配 0 行、快照残留。
     */
    @Query("UPDATE Asset SET orig_epc = NULL, orig_asset_type = NULL, orig_asset_model = NULL, " +
           "orig_serial_number = NULL, orig_custodian = NULL, orig_dept = NULL, " +
           "orig_raw_row_json = NULL WHERE taskId = :taskId")
    int clearSnapshotForTask(long taskId);

    /**
     * 给所有"已扫到但 inventoryTime 还是 null"的资产, 写入它的首次扫描时间.
     * 见下方 default 方法 {@link #backfillFoundInventoryTime(long)}.
     */

    // ========================================================================
    // 全局资产台账 (资产 tab) —— 不按 taskId 过滤, 包含库存池与所有任务内资产.
    // ========================================================================

    @Query("SELECT * FROM Asset WHERE id = :id LIMIT 1")
    Asset findById(long id);

    @Query("SELECT COUNT(*) FROM Asset")
    int countAll();

    /**
     * 全量台账中真实领用部门的数量：去重、去首尾空格，且不含未填写部门.
     * 用于资产 tab 部门分布标题右侧的统计提示，不受当前筛选影响.
     */
    @Query("SELECT COUNT(DISTINCT TRIM(dept)) FROM Asset " +
           "WHERE TRIM(COALESCE(dept, '')) <> ''")
    int countRealDepts();

    /**
     * Asset 表变更失效信号: Room 在任意 INSERT/UPDATE/DELETE 后重新查询并派发,
     * 用于导入、编辑、任务划拨/释放后让资产 tab 自动刷新 (ViewModel 侧再防抖).
     */
    @Query("SELECT COUNT(*) FROM Asset")
    LiveData<Integer> countAllLive();

    @Query("SELECT COUNT(*) FROM Asset WHERE " + FILTER_WHERE)
    int countFiltered(String keywordPattern,
                      @Nullable String deptBucket,
                      @Nullable String typeBucket);

    /** 全局分页: id 顺序稳定 (excelRowIndex 只在单次导入内有意义). */
    @Query("SELECT * FROM Asset WHERE " + FILTER_WHERE +
           " ORDER BY id ASC LIMIT :limit OFFSET :offset")
    List<Asset> pageFiltered(String keywordPattern,
                             @Nullable String deptBucket,
                             @Nullable String typeBucket,
                             int limit, int offset);

    /**
     * 领用部门分布. 交叉筛选: 忽略部门筛选, 但受关键词 + 类型约束,
     * 让条形图上的台数与点击后列表的条数一致.
     */
    @Query("SELECT " + DEPT_BUCKET + " AS category, COUNT(*) AS count FROM Asset " +
           "WHERE " + KEYWORD_WHERE +
           " AND (:typeBucket IS NULL OR " + TYPE_BUCKET + " = :typeBucket) " +
           "GROUP BY " + DEPT_BUCKET + " ORDER BY count DESC, category ASC")
    List<CategoryCount> deptDistribution(String keywordPattern,
                                         @Nullable String typeBucket);

    /**
     * 设备类型分布. 交叉筛选: 忽略类型筛选, 但受关键词 + 部门约束.
     */
    @Query("SELECT " + TYPE_BUCKET + " AS category, COUNT(*) AS count FROM Asset " +
           "WHERE " + KEYWORD_WHERE +
           " AND (:deptBucket IS NULL OR " + DEPT_BUCKET + " = :deptBucket) " +
           "GROUP BY " + TYPE_BUCKET + " ORDER BY count DESC, category ASC")
    List<CategoryCount> typeDistribution(String keywordPattern,
                                         @Nullable String deptBucket);

    /**
     * 台账编辑专用: 只写 6 个业务字段 + raw_row_json.
     * <p>刻意排除 epc / taskId / excelRowIndex / inventory_time / created_at /
     * orig_* —— 保证资产 tab 的编辑不会破坏 RFID 匹配、任务归属、盘点时间或回滚快照.
     * 不更新 epc 也意味着不需要再做全局唯一校验.</p>
     */
    @Query("UPDATE Asset SET assetType = :assetType, assetModel = :assetModel, " +
           "serialNumber = :serialNumber, custodian = :custodian, dept = :dept, " +
           "raw_row_json = :rawRowJson WHERE id = :id")
    int updateBusinessFieldsById(long id, String assetType, String assetModel,
                                 String serialNumber, String custodian, String dept,
                                 String rawRowJson);
}

# 任务完成后释放资产（结果冻结快照）— 设计

- **日期**: 2026-11-13
- **作者**: brainstorm 输出
- **状态**: 待实施
- **范围**: `data/entity/TaskResult`（新增）/ `data/dao/TaskResultDao`（新增）/ `data/AppDatabase` / `data/ResultRepository`（新增）/ `service/TaskService` / `ui/result/ResultViewModel` / `ui/result/ResultFragment` / `ui/scan/ScanFragment` / `excel/ExcelExporter` / `web/api/AssetApi` / `web/api/TaskApi` / `InventoryApp`（注册迁移）

---

## 1. 背景与目标

盘点任务的资产归属目前靠 `Asset.taskId` 一个字段表达：

- 建任务 → `assignPoolToTask` 把资产从库存池（`taskId = 0`）改到任务下（`taskId = N`），即"锁定"
- 完成任务 → **只把状态翻成 `COMPLETED`，资产仍挂在任务下**
- 只有"删除任务"和"清除已完成"才会 `releaseTaskToPool` 把资产退回库存池

结果：**任务完成后资产仍被占用**，新建的盘点任务选不到这批资产，必须先手动"清除已完成"。

**目标**：完成任务时自动释放资产回库存池，同时**该次盘点的结果必须完整保留**（可查看、可导出），且不随后续操作变化。

---

## 2. 为什么不能简单地"完成即释放"

结果页（全部 / 已盘 / 盘亏 / 盘盈）与导出全部按 `Asset.taskId = N` 和 `ScanRecord.taskId = N` 查询。
资产一旦退回 `taskId = 0`，这个已完成任务的结果立刻变空 —— 包括导出的 Excel。

所以要满足目标，必须把**「资产当前归属」**和**「这次盘点的结果」**拆成两件事。

### 2.1 方案选型

| 方案 | 说明 | 结论 |
|---|---|---|
| **A. 完成后冻结结果快照** | 新增 `TaskResult` 表，完成时逐行快照，然后释放资产、清扫描记录 | **采用** |
| B. 只记成员关系（`TaskAsset` 关联表） | 完成时释放资产，保留 assetId 关联，结果页 join 回 `Asset` 实时算 | 否 —— 资产被后续任务改写后旧报告会跟着变，审计场景不成立；盘盈没有 Asset 行还得另存 |
| C. 不改表，完成后资产留在任务下，建任务时允许选已完成任务的资产 | 改动最小 | 否 —— 资产被划走时旧任务结果即失效，与"必须保留结果"直接冲突 |

A 与 B 的读取路径改动量相同（都要改五处查询），A 多出来的只是"写快照"的代码，换来报告永久不变。

---

## 3. 数据模型

### 3.1 新增表 `TaskResult`

一次盘点的冻结结果，一行一个资产（或一行盘盈）。

```java
@Entity(indices = {
        @Index("taskId"),
        @Index(value = {"taskId", "status"}),
        @Index(value = {"taskId", "rowIndex"})
})
public class TaskResult {
    @PrimaryKey(autoGenerate = true) public long id;
    public long taskId;

    /** 原 Asset.excelRowIndex，保证导出顺序与原表一致；盘盈行为 0。 */
    public int rowIndex;

    /** 盘盈行为 null。仅用于追溯，不参与任何查询。 */
    @Nullable public Long assetId;

    public String epc;
    public String assetType;
    public String assetModel;
    public String serialNumber;
    public String custodian;
    public String dept;

    /** FOUND / MISSING / EXTRA */
    public String status;

    @Nullable public Long inventoryTime;   // 已盘/盘亏行
    @Nullable public Long scannedAt;       // 盘盈行
    @Nullable public String rssi;          // 盘盈行
    @Nullable public String rawRowJson;    // 导出时重建原始表头顺序
}
```

**盘盈行的业务字段来自 `ExtraClaim`**（用户补全的信息），`epc` 优先取 claim 的、退回 ScanRecord 的。

### 3.2 迁移 v1 → v2（含历史回填）

`AppDatabase.version` 由 `1` 升到 `2`，在 `InventoryApp` 注册 `MIGRATION_1_2`。

**必须回填**：设备上已存在的 `COMPLETED` 任务如果没回填，升级后结果页会全空。

```sql
-- 已盘 / 盘亏：从当前 Asset + ScanRecord 推导
INSERT INTO TaskResult (taskId, rowIndex, assetId, epc, assetType, assetModel,
                        serialNumber, custodian, dept, status, inventoryTime, rawRowJson)
SELECT a.taskId, a.excelRowIndex, a.id, a.epc, a.assetType, a.assetModel,
       a.serialNumber, a.custodian, a.dept,
       CASE WHEN EXISTS (SELECT 1 FROM ScanRecord s
                         WHERE s.taskId = a.taskId AND s.matchedAssetId = a.id)
            THEN 'FOUND' ELSE 'MISSING' END,
       a.inventory_time, a.raw_row_json
FROM Asset a JOIN InventoryTask t ON t.id = a.taskId
WHERE t.status = 'COMPLETED';

-- 盘盈：未匹配到资产的扫描记录，业务字段取 ExtraClaim 的补全值
INSERT INTO TaskResult (taskId, rowIndex, assetId, epc, assetType, assetModel,
                        serialNumber, custodian, dept, status, scannedAt, rssi)
SELECT s.taskId, 0, NULL, COALESCE(c.epc, s.epc),
       c.assetType, c.assetModel, c.serialNumber, c.custodian, c.dept,
       'EXTRA', s.scannedAt, s.rssi
FROM ScanRecord s
JOIN InventoryTask t ON t.id = s.taskId
LEFT JOIN ExtraClaim c ON c.scanRecordId = s.id
WHERE t.status = 'COMPLETED' AND s.matchedAssetId IS NULL;
```

**回填之后还要把历史已完成任务的数据整理成新语义**，否则升级后这些任务仍然占着资产 ——
正是本次要修的那个问题：

```sql
-- 3. 释放历史已完成任务的资产回库存池，并清掉本次盘点留下的痕迹
UPDATE Asset SET taskId = 0, inventory_time = NULL,
       orig_epc = NULL, orig_asset_type = NULL, orig_asset_model = NULL,
       orig_serial_number = NULL, orig_custodian = NULL, orig_dept = NULL,
       orig_raw_row_json = NULL
WHERE taskId IN (SELECT id FROM InventoryTask WHERE status = 'COMPLETED');

-- 4. 历史已完成任务的扫描/补全记录作废（历史已在 TaskResult 里）
DELETE FROM ExtraClaim WHERE scanRecordId IN (
    SELECT s.id FROM ScanRecord s JOIN InventoryTask t ON t.id = s.taskId
    WHERE t.status = 'COMPLETED');
DELETE FROM ScanRecord WHERE taskId IN (
    SELECT id FROM InventoryTask WHERE status = 'COMPLETED');
```

顺序不可调换：**先回填快照，再释放/清记录**，否则快照拿不到数据。

---

## 4. 行为变更

### 4.1 `TaskService.completeTask`（单事务，六步）

```
1. 写快照      TaskResult ← 该任务的资产行(FOUND/MISSING) + 盘盈行(EXTRA)
2. 释放资产    UPDATE Asset SET taskId = 0 WHERE taskId = N
3. 清盘点时间  inventory_time = NULL（资产回池，本次盘点时间失效）
4. 丢快照列    orig_* 清空（编辑值已成主数据，且已写进 TaskResult）
5. 删扫描记录  ScanRecord / ExtraClaim 全删（历史已在 TaskResult 里）
6. 状态翻转    status = COMPLETED；若是当前任务则 session.clear()
```

顺序不可调换：第 1 步必须早于第 2/5 步，否则快照拿不到数据。
第 5 步是额外收益 —— 完成后扫描记录不再堆积。

**幂等**：已是 `COMPLETED` 直接返回，不重复写快照。

### 4.2 `clearCompleted`

资产在完成时已经回池，这里只需：删该任务的 `TaskResult` 行 → 删任务壳。不再做 release。

### 4.3 `deleteTask`

- 已完成任务：删 `TaskResult` → 删任务壳（资产早就在池里）
- 进行中 / 暂停任务：逻辑不变（回滚编辑 → 清盘点时间 → 丢快照 → 释放 → 删记录 → 删任务壳）

---

## 5. 读取路径：新增 `ResultRepository`

已完成任务的结果只能来自 `TaskResult`，进行中任务仍走 `Asset` / `ScanRecord`。
这个状态分支如果散落在各处会重复 **6 次**，必然漂移。收敛到一个类：

```java
// data/ResultRepository.java
public class ResultRepository {
    public ResultRepository(AppDatabase db);

    /** 统一统计。内部按任务状态选数据源。 */
    public Counts counts(long taskId, String taskStatus);

    /** 统一分页。tab ∈ ALL / FOUND / MISSING / EXTRA。 */
    public List<ResultRow> page(long taskId, String taskStatus, Tab tab, int limit, int offset);
}
```

- `Counts`：`expected` / `found` / `missing` / `extra`
- `ResultRow`：扁平 DTO（epc / assetType / assetModel / serialNumber / custodian / dept /
  status / inventoryTime / scannedAt / rssi / rawRowJson），`Asset`、`ScanRecord`、`TaskResult`
  各自映射进来
- `Tab`：枚举 `ALL` / `FOUND` / `MISSING` / `EXTRA`

口径约定（两种状态必须一致，否则切换时会跳变）：

- `expected` = 该任务名下的资产数（进行中取 `Asset.taskId = N` 的行数；已完成取
  `TaskResult` 中 `status IN (FOUND, MISSING)` 的行数），**不含盘盈**
- `ALL` = FOUND + MISSING，按 `rowIndex`（= 原 `excelRowIndex`）升序
- `EXTRA` 单独一页，按 `scannedAt` 升序；盘盈行的 `rowIndex` 固定 0，不参与 ALL 排序

分支只在 `ResultRepository` 内部出现一次。

### 5.1 需要改的调用点

| 文件 | 现状 | 改为 |
|---|---|---|
| `ui/result/ResultViewModel` | `countByTask` / `countFound` / `countMissing` / `countExtras` / `pageAll` / `pageFound` / `pageMissing` / `pageExtra` | `ResultRepository.counts()` / `.page()` |
| `ui/result/ResultFragment` | `pageAll(taskId, 1, 0)` 重建表头 | `ResultRepository.page(ALL, 1, 0)` |
| `excel/ExcelExporter` | `countByTask` / `pageAll` / `countExtras` / `pageExtra` | `ResultRepository` |
| `ui/scan/ScanFragment` | `scanRecordDao.countByTask(tid)` | `ResultRepository.counts()` |
| `web/api/AssetApi.results` | `countFound` / `pageFound` … | `ResultRepository` |
| `web/api/TaskApi.summary` | `scanRecordDao.aggregateStats(t.id)` | `ResultRepository.counts()` |

### 5.2 一个必须一起修的口径问题

`InventoryTask.scannedUniqueCount` 是**存在任务行上的计数**，只在扫描过程中重算。
完成后删掉 `ScanRecord`，这个值不会自动变 —— App 任务列表（读 `t.scannedUniqueCount`）仍然正确。

但 `TaskApi.summary` 和 `ScanFragment` 是**实时查 `ScanRecord`** 的，完成后会显示 `0 / 应盘`。
这两处一并归 `ResultRepository` 管（见上表）。

### 5.3 不变的部分

- 建任务、暂停、恢复、删除进行中任务：逻辑不动
- 已完成任务仍只读（结果页不可编辑）
- 导出列与表头重建规则不变（`rawRowJson` 已快照进 `TaskResult`）
- Web 接口契约不变

---

## 6. 边界情况

| 场景 | 处理 |
|---|---|
| 重复点「完成」 | 幂等，不重复写快照 |
| 任务为空（0 资产） | 写 0 行快照，结果页空态，不报错 |
| 盘盈行 | `assetId = null`，带 `scannedAt` / `rssi`，业务字段取 `ExtraClaim` |
| 完成时正在扫描 | 单事务内先写快照再删记录；完成后 `session.clear()` 让扫描页复位 |
| 升级前已存在的已完成任务 | 迁移回填，不丢历史 |
| 导出已完成任务 | 表头顺序从快照的 `rawRowJson` 重建，与现在一致 |
| 资产回池后被新任务划走并改写 | 旧任务报告不变（快照冻结） |

---

## 7. 测试

| 测试 | 覆盖 |
|---|---|
| `TaskResultDaoTest` | 批量插入、按 tab 分页、计数 |
| `TaskServiceCompleteTest` | 完成后：快照行数正确、`Asset.taskId` 全为 0、`ScanRecord`/`ExtraClaim` 清空、`scannedUniqueCount` 未被清零 |
| `ResultRepositoryTest` | 同一任务在「进行中」与「已完成」两种状态下 `counts` / `page` 结果一致 |
| `Migration1To2Test` | 造 v1 库（已完成任务 + 扫描记录 + 盘盈补全）→ 迁移 → 断言 FOUND/MISSING/EXTRA 回填行数正确 |
| `WebServerIntegrationTest` | 新增：已完成任务的 `/api/tasks/{id}/results` 仍返回数据 |
| 回归 | `ResultViewModel` / `ExcelExporter` / `TaskListViewModel*` 现有测试全绿 |

**数据库版本**：`AppDatabase.version` 1 → 2，`DatabaseMigrationChainGuardTest` 同步更新。

---

## 8. 交付清单

| 文件 | 变更 |
|---|---|
| `data/entity/TaskResult.java` | 新增 |
| `data/dao/TaskResultDao.java` | 新增 |
| `data/AppDatabase.java` | version 2 + `MIGRATION_1_2` + `taskResultDao()` |
| `data/ResultRepository.java` | 新增（含 `Counts` / `ResultRow` / `Tab`） |
| `InventoryApp.java` | builder 注册 `MIGRATION_1_2` |
| `service/TaskService.java` | `completeTask` 六步；`clearCompleted` / `deleteTask` 调整 |
| `ui/result/ResultViewModel.java` / `ResultFragment.java` | 改走 `ResultRepository` |
| `ui/scan/ScanFragment.java` | 改走 `ResultRepository` |
| `excel/ExcelExporter.java` | 改走 `ResultRepository` |
| `web/api/AssetApi.java` / `TaskApi.java` | 改走 `ResultRepository` |
| 测试 | 见 §7 |

---

## 9. 不做的事（YAGNI）

- 不做"部分释放"（按资产勾选释放）
- 不做已完成任务的结果编辑
- 不做历史任务的多次盘点对比
- 不保留完成后的 `ScanRecord`（历史已在 `TaskResult`，留着只会让库膨胀）

# 任务完成后释放资产（结果冻结快照）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成任务时把该次盘点结果冻结进 `TaskResult` 并释放资产回库存池，使新任务能重新选择这批资产，同时历史报告永久不变。

**Architecture:** 新增 `TaskResult` 表（schema v1→v2，迁移含历史回填与释放）；`completeTask` 单事务内「写快照 → 释放 → 清记录 → 翻状态」；结果读取路径统一收敛到 `ResultRepository`，由它按任务状态在 `Asset`/`ScanRecord` 与 `TaskResult` 之间分流。

**Tech Stack:** Java 11 / Room 2.6（`Migration` + `SupportSQLiteDatabase`）/ JUnit4 + Robolectric

## Global Constraints

- 不改 `Asset` / `InventoryTask` / `ScanRecord` / `ExtraClaim` 四个既有实体的表结构。
- `AppDatabase.version` 由 `1` 升到 `2`，必须在 `InventoryApp` 注册 `MIGRATION_1_2`；
  `DatabaseMigrationChainGuardTest` 必须同步更新，否则它会红。
- `AppDatabase.exportSchema = false` 保持不变 —— 迁移测试直接对 `SupportSQLiteDatabase` 跑 SQL，不用 `MigrationTestHelper`。
- 迁移顺序不可调换：**先回填 `TaskResult`，再释放资产 / 删扫描记录**。
- `completeTask` 的六步必须在**同一个 `runInTransaction`** 内。
- 已完成任务的结果只读；导出列与表头重建规则不变。
- 禁止 `SELECT *` 出现在新的 Mapper XML 里（本计划不新增 XML，全部用 Room 注解查询）。
- 每个任务结束时 `./gradlew :app:testDebugUnitTest` 必须全绿（当前基线 **344** 个用例）。
- 提交信息用中文 `type(scope): 摘要`。

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `data/entity/TaskResult.java` | 一次盘点的冻结结果行 |
| `data/dao/TaskResultDao.java` | `TaskResult` 的读写与分页 |
| `data/AppDatabase.java` | 版本 2 + `MIGRATION_1_2` + `taskResultDao()` |
| `InventoryApp.java` | builder 注册迁移 |
| `data/ResultRepository.java` | **唯一**按任务状态分流结果读取的地方 |
| `service/TaskService.java` | `completeTask` 六步；`clearCompleted` / `deleteTask` 调整 |
| `ui/result/ResultViewModel.java` · `ResultFragment.java` | 改走 `ResultRepository` |
| `ui/scan/ScanFragment.java` | 改走 `ResultRepository` |
| `excel/ExcelExporter.java` | 改走 `ResultRepository` |
| `web/api/AssetApi.java` · `TaskApi.java` | 改走 `ResultRepository` |

---

## Task 1: TaskResult 表 + schema v2 + 迁移

**Files:**
- Create: `app/src/main/java/com/huaxia/inventory/data/entity/TaskResult.java`
- Create: `app/src/main/java/com/huaxia/inventory/data/dao/TaskResultDao.java`
- Modify: `app/src/main/java/com/huaxia/inventory/data/AppDatabase.java`
- Modify: `app/src/main/java/com/huaxia/inventory/InventoryApp.java`
- Test: `app/src/test/java/com/huaxia/inventory/data/Migration1To2Test.java`
- Test: `app/src/test/java/com/huaxia/inventory/data/TaskResultDaoTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `TaskResult` 实体（字段见下方代码）
  - `AppDatabase.taskResultDao()` → `TaskResultDao`
  - `AppDatabase.MIGRATION_1_2` → `androidx.room.migration.Migration`
  - `TaskResultDao.insertAll(List<TaskResult>)` / `countByStatus(long,String)` / `countExpected(long)` /
    `pageAll/pageFound/pageMissing/pageExtra(long,int,int)` / `findAll(long)` / `deleteByTask(long)`

- [ ] **Step 1: 写失败测试（DAO 基本读写）**

`app/src/test/java/com/huaxia/inventory/data/TaskResultDaoTest.java`：

```java
package com.huaxia.inventory.data;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.huaxia.inventory.data.entity.TaskResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class TaskResultDaoTest {

    private AppDatabase db;

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries().build();
    }

    @After
    public void tearDown() { db.close(); }

    private static TaskResult row(long taskId, int rowIndex, String status) {
        TaskResult r = new TaskResult();
        r.taskId = taskId;
        r.rowIndex = rowIndex;
        r.epc = "E" + rowIndex;
        r.status = status;
        return r;
    }

    @Test
    public void countsAndPages_byStatus() {
        List<TaskResult> rows = new ArrayList<>();
        rows.add(row(7L, 1, "FOUND"));
        rows.add(row(7L, 2, "MISSING"));
        rows.add(row(7L, 3, "MISSING"));
        rows.add(row(7L, 0, "EXTRA"));
        rows.add(row(8L, 1, "FOUND"));   // 别的任务，不该被算进来
        db.taskResultDao().insertAll(rows);

        assertThat(db.taskResultDao().countExpected(7L)).isEqualTo(3);
        assertThat(db.taskResultDao().countByStatus(7L, "FOUND")).isEqualTo(1);
        assertThat(db.taskResultDao().countByStatus(7L, "MISSING")).isEqualTo(2);
        assertThat(db.taskResultDao().countByStatus(7L, "EXTRA")).isEqualTo(1);

        // ALL 只含 FOUND/MISSING，按 rowIndex 升序，盘盈不进 ALL
        List<TaskResult> all = db.taskResultDao().pageAll(7L, 10, 0);
        assertThat(all).extracting(r -> r.rowIndex).containsExactly(1, 2, 3);
        assertThat(db.taskResultDao().pageExtra(7L, 10, 0)).hasSize(1);
    }

    @Test
    public void deleteByTask_removesOnlyThatTask() {
        List<TaskResult> rows = new ArrayList<>();
        rows.add(row(7L, 1, "FOUND"));
        rows.add(row(8L, 1, "FOUND"));
        db.taskResultDao().insertAll(rows);

        assertThat(db.taskResultDao().deleteByTask(7L)).isEqualTo(1);
        assertThat(db.taskResultDao().findAll(8L)).hasSize(1);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.data.TaskResultDaoTest"`
Expected: 编译失败 — `TaskResult` / `taskResultDao()` 不存在

- [ ] **Step 3: 创建实体**

`app/src/main/java/com/huaxia/inventory/data/entity/TaskResult.java`：

```java
package com.huaxia.inventory.data.entity;

import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 一次盘点的冻结结果 —— 完成任务时逐行写入，之后永不再改。
 *
 * <p>存在的理由：完成任务要把资产释放回库存池（让新任务能重新选），
 * 但结果页/导出仍需按任务查询。`Asset.taskId` 是单一归属，无法同时表达
 * 「资产现在在池里」和「它属于某次历史盘点」，所以用本表冻结一份。
 *
 * <p>盘盈行（{@code status = 'EXTRA'}）没有对应资产，{@code assetId} 为 null；
 * 其业务字段来自用户补全的 {@code ExtraClaim}。
 */
@Entity(indices = {
        @Index("taskId"),
        @Index(value = {"taskId", "status"}),
        @Index(value = {"taskId", "rowIndex"})
})
public class TaskResult {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long taskId;

    /** 原 {@code Asset.excelRowIndex}，保证导出顺序与原表一致；盘盈行固定 0。 */
    public int rowIndex;

    /** 盘盈行为 null。仅用于追溯，不参与任何查询。 */
    @Nullable
    public Long assetId;

    public String epc;

    @Nullable public String assetType;
    @Nullable public String assetModel;
    @Nullable public String serialNumber;
    @Nullable public String custodian;
    @Nullable public String dept;

    /** FOUND / MISSING / EXTRA */
    public String status;

    /** 已盘 / 盘亏行的盘点时间。 */
    @Nullable public Long inventoryTime;

    /** 盘盈行的扫描时间。 */
    @Nullable public Long scannedAt;

    /** 盘盈行的 RSSI。 */
    @Nullable public String rssi;

    /** 导出时重建原始表头顺序用。 */
    @Nullable public String rawRowJson;
}
```

- [ ] **Step 4: 创建 DAO**

`app/src/main/java/com/huaxia/inventory/data/dao/TaskResultDao.java`：

```java
package com.huaxia.inventory.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.huaxia.inventory.data.entity.TaskResult;

import java.util.List;

/** {@link TaskResult} 读写。只服务已完成任务的结果查询与导出。 */
@Dao
public interface TaskResultDao {

    @Insert
    void insertAll(List<TaskResult> rows);

    /** 已盘 + 盘亏 = 应盘数（不含盘盈）。 */
    @Query("SELECT COUNT(*) FROM TaskResult WHERE taskId = :taskId " +
           "AND status IN ('FOUND', 'MISSING')")
    int countExpected(long taskId);

    @Query("SELECT COUNT(*) FROM TaskResult WHERE taskId = :taskId AND status = :status")
    int countByStatus(long taskId, String status);

    @Query("SELECT * FROM TaskResult WHERE taskId = :taskId " +
           "AND status IN ('FOUND', 'MISSING') ORDER BY rowIndex ASC " +
           "LIMIT :limit OFFSET :offset")
    List<TaskResult> pageAll(long taskId, int limit, int offset);

    @Query("SELECT * FROM TaskResult WHERE taskId = :taskId AND status = 'FOUND' " +
           "ORDER BY rowIndex ASC LIMIT :limit OFFSET :offset")
    List<TaskResult> pageFound(long taskId, int limit, int offset);

    @Query("SELECT * FROM TaskResult WHERE taskId = :taskId AND status = 'MISSING' " +
           "ORDER BY rowIndex ASC LIMIT :limit OFFSET :offset")
    List<TaskResult> pageMissing(long taskId, int limit, int offset);

    @Query("SELECT * FROM TaskResult WHERE taskId = :taskId AND status = 'EXTRA' " +
           "ORDER BY scannedAt ASC LIMIT :limit OFFSET :offset")
    List<TaskResult> pageExtra(long taskId, int limit, int offset);

    @Query("SELECT * FROM TaskResult WHERE taskId = :taskId " +
           "AND status IN ('FOUND', 'MISSING') ORDER BY rowIndex ASC")
    List<TaskResult> findAll(long taskId);

    @Query("DELETE FROM TaskResult WHERE taskId = :taskId")
    int deleteByTask(long taskId);
}
```

- [ ] **Step 5: 升级数据库版本并写迁移**

`app/src/main/java/com/huaxia/inventory/data/AppDatabase.java` 改为：

```java
package com.huaxia.inventory.data;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.huaxia.inventory.data.dao.AssetDao;
import com.huaxia.inventory.data.dao.ExtraClaimDao;
import com.huaxia.inventory.data.dao.InventoryTaskDao;
import com.huaxia.inventory.data.dao.ScanRecordDao;
import com.huaxia.inventory.data.dao.TaskResultDao;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ExtraClaim;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.data.entity.TaskResult;

/**
 * 本地数据库。
 *
 * <p><b>v1</b> 是首次发布基线。**v2** 新增 {@code TaskResult} 表：完成任务时把该次
 * 盘点结果冻结一份，从而可以把资产释放回库存池而历史报告不变。</p>
 *
 * <p>发布之后每次改表结构: {@code version} +1, 声明 {@code MIGRATION_(n-1)_n},
 * 并在 {@code InventoryApp} 的 builder 里注册 —— 两者缺一
 * {@code DatabaseMigrationChainGuardTest} 会失败。</p>
 */
@Database(
        entities = {InventoryTask.class, Asset.class, ScanRecord.class, ExtraClaim.class,
                TaskResult.class},
        version = 2,
        exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    public abstract InventoryTaskDao inventoryTaskDao();
    public abstract AssetDao assetDao();
    public abstract ScanRecordDao scanRecordDao();
    public abstract ExtraClaimDao extraClaimDao();
    public abstract TaskResultDao taskResultDao();

    /**
     * v1 → v2：建 {@code TaskResult} 表，并把历史已完成任务整理成新语义。
     *
     * <p>顺序不可调换：<b>先回填快照，再释放资产 / 删扫描记录</b>，
     * 否则回填时数据已经被清掉。</p>
     */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `TaskResult` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                    + "`taskId` INTEGER NOT NULL,"
                    + "`rowIndex` INTEGER NOT NULL,"
                    + "`assetId` INTEGER,"
                    + "`epc` TEXT NOT NULL,"
                    + "`assetType` TEXT,"
                    + "`assetModel` TEXT,"
                    + "`serialNumber` TEXT,"
                    + "`custodian` TEXT,"
                    + "`dept` TEXT,"
                    + "`status` TEXT NOT NULL,"
                    + "`inventoryTime` INTEGER,"
                    + "`scannedAt` INTEGER,"
                    + "`rssi` TEXT,"
                    + "`rawRowJson` TEXT)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_TaskResult_taskId` "
                    + "ON `TaskResult` (`taskId`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_TaskResult_taskId_status` "
                    + "ON `TaskResult` (`taskId`, `status`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_TaskResult_taskId_rowIndex` "
                    + "ON `TaskResult` (`taskId`, `rowIndex`)");

            // 1) 已盘 / 盘亏
            db.execSQL("INSERT INTO TaskResult (taskId, rowIndex, assetId, epc, assetType, "
                    + "assetModel, serialNumber, custodian, dept, status, inventoryTime, rawRowJson) "
                    + "SELECT a.taskId, a.excelRowIndex, a.id, a.epc, a.assetType, a.assetModel, "
                    + "a.serialNumber, a.custodian, a.dept, "
                    + "CASE WHEN EXISTS (SELECT 1 FROM ScanRecord s "
                    + "  WHERE s.taskId = a.taskId AND s.matchedAssetId = a.id) "
                    + "  THEN 'FOUND' ELSE 'MISSING' END, "
                    + "a.inventory_time, a.raw_row_json "
                    + "FROM Asset a JOIN InventoryTask t ON t.id = a.taskId "
                    + "WHERE t.status = 'COMPLETED'");

            // 2) 盘盈（业务字段取 ExtraClaim 的补全值）
            db.execSQL("INSERT INTO TaskResult (taskId, rowIndex, assetId, epc, assetType, "
                    + "assetModel, serialNumber, custodian, dept, status, scannedAt, rssi) "
                    + "SELECT s.taskId, 0, NULL, COALESCE(c.epc, s.epc), c.assetType, "
                    + "c.assetModel, c.serialNumber, c.custodian, c.dept, 'EXTRA', "
                    + "s.scannedAt, s.rssi "
                    + "FROM ScanRecord s JOIN InventoryTask t ON t.id = s.taskId "
                    + "LEFT JOIN ExtraClaim c ON c.scanRecordId = s.id "
                    + "WHERE t.status = 'COMPLETED' AND s.matchedAssetId IS NULL");

            // 3) 释放历史已完成任务的资产，清掉本次盘点的痕迹
            db.execSQL("UPDATE Asset SET taskId = 0, inventory_time = NULL, "
                    + "orig_epc = NULL, orig_asset_type = NULL, orig_asset_model = NULL, "
                    + "orig_serial_number = NULL, orig_custodian = NULL, orig_dept = NULL, "
                    + "orig_raw_row_json = NULL "
                    + "WHERE taskId IN (SELECT id FROM InventoryTask WHERE status = 'COMPLETED')");

            // 4) 历史已完成任务的扫描/补全记录作废（历史已在 TaskResult 里）
            db.execSQL("DELETE FROM ExtraClaim WHERE scanRecordId IN ("
                    + "SELECT s.id FROM ScanRecord s JOIN InventoryTask t ON t.id = s.taskId "
                    + "WHERE t.status = 'COMPLETED')");
            db.execSQL("DELETE FROM ScanRecord WHERE taskId IN "
                    + "(SELECT id FROM InventoryTask WHERE status = 'COMPLETED')");
        }
    };
}
```

- [ ] **Step 6: 在 InventoryApp 注册迁移**

`app/src/main/java/com/huaxia/inventory/InventoryApp.java` 的 `Room.databaseBuilder(...)` 链上，
`fallbackToDestructiveMigrationOnDowngrade()` 之前插入 `.addMigrations(AppDatabase.MIGRATION_1_2)`：

```java
        db = Room.databaseBuilder(getApplicationContext(), AppDatabase.class, "huaxia-inventory.db")
                .addMigrations(AppDatabase.MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build();
```

- [ ] **Step 7: 写迁移测试**

`app/src/test/java/com/huaxia/inventory/data/Migration1To2Test.java`：

```java
package com.huaxia.inventory.data;

import static org.assertj.core.api.Assertions.assertThat;

import android.database.sqlite.SQLiteDatabase;

import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.framework.FrameworkSQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;

@RunWith(RobolectricTestRunner.class)
public class Migration1To2Test {

    private File dbFile;
    private SQLiteDatabase raw;

    @Before
    public void setUp() {
        File dir = RuntimeEnvironment.getApplication().getCacheDir();
        dbFile = new File(dir, "migration-test.db");
        if (dbFile.exists() && !dbFile.delete()) {
            throw new IllegalStateException("cannot reset test db");
        }
        raw = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        createV1Schema();
        seedV1Data();
    }

    @After
    public void tearDown() {
        raw.close();
    }

    private void createV1Schema() {
        raw.execSQL("CREATE TABLE IF NOT EXISTS `InventoryTask` ("
                + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                + "`sourceFileName` TEXT, `importedAt` INTEGER NOT NULL,"
                + "`status` TEXT, `dept` TEXT, `expectedCount` INTEGER NOT NULL,"
                + "`scannedUniqueCount` INTEGER NOT NULL, `remark` TEXT)");
        raw.execSQL("CREATE TABLE IF NOT EXISTS `Asset` ("
                + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                + "`taskId` INTEGER NOT NULL, `excelRowIndex` INTEGER NOT NULL,"
                + "`epc` TEXT NOT NULL, `assetType` TEXT, `assetModel` TEXT,"
                + "`serialNumber` TEXT, `custodian` TEXT, `dept` TEXT,"
                + "`raw_row_json` TEXT, `inventory_time` INTEGER, `created_at` INTEGER,"
                + "`orig_epc` TEXT, `orig_asset_type` TEXT, `orig_asset_model` TEXT,"
                + "`orig_serial_number` TEXT, `orig_custodian` TEXT, `orig_dept` TEXT,"
                + "`orig_raw_row_json` TEXT)");
        raw.execSQL("CREATE TABLE IF NOT EXISTS `ScanRecord` ("
                + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                + "`taskId` INTEGER NOT NULL, `epc` TEXT NOT NULL, `tid` TEXT,"
                + "`rssi` TEXT, `scannedAt` INTEGER NOT NULL, `matchedAssetId` INTEGER)");
        raw.execSQL("CREATE TABLE IF NOT EXISTS `ExtraClaim` ("
                + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                + "`scanRecordId` INTEGER NOT NULL, `epc` TEXT NOT NULL,"
                + "`assetType` TEXT, `assetModel` TEXT, `serialNumber` TEXT,"
                + "`custodian` TEXT, `dept` TEXT, `claimedAt` INTEGER)");
    }

    private void seedV1Data() {
        // 任务 1：已完成。资产 A(id=1) 已盘，资产 B(id=2) 盘亏，另有一条盘盈（已补全）
        raw.execSQL("INSERT INTO InventoryTask (id, sourceFileName, importedAt, status, "
                + "expectedCount, scannedUniqueCount) VALUES (1, '已完成任务', 100, 'COMPLETED', 2, 1)");
        raw.execSQL("INSERT INTO Asset (id, taskId, excelRowIndex, epc, dept, inventory_time) "
                + "VALUES (1, 1, 1, 'E1', '行政部', 555)");
        raw.execSQL("INSERT INTO Asset (id, taskId, excelRowIndex, epc, dept) "
                + "VALUES (2, 1, 2, 'E2', '行政部')");
        raw.execSQL("INSERT INTO ScanRecord (id, taskId, epc, scannedAt, matchedAssetId) "
                + "VALUES (1, 1, 'E1', 200, 1)");
        raw.execSQL("INSERT INTO ScanRecord (id, taskId, epc, scannedAt, matchedAssetId) "
                + "VALUES (2, 1, 'X9', 210, NULL)");
        raw.execSQL("INSERT INTO ExtraClaim (scanRecordId, epc, assetType, dept) "
                + "VALUES (2, 'X9', '打印机', '财务部')");

        // 任务 2：进行中。资产 C(id=3) 必须原样留在任务下
        raw.execSQL("INSERT INTO InventoryTask (id, sourceFileName, importedAt, status, "
                + "expectedCount, scannedUniqueCount) VALUES (2, '进行中任务', 300, 'IN_PROGRESS', 1, 0)");
        raw.execSQL("INSERT INTO Asset (id, taskId, excelRowIndex, epc, dept) "
                + "VALUES (3, 2, 1, 'E3', '技术部')");
    }

    private void migrate() {
        SupportSQLiteDatabase support = new FrameworkSQLiteDatabase(raw);
        AppDatabase.MIGRATION_1_2.migrate(support);
    }

    private int intOf(String sql) {
        try (android.database.Cursor c = raw.rawQuery(sql, null)) {
            return c.moveToFirst() ? c.getInt(0) : -1;
        }
    }

    private String strOf(String sql) {
        try (android.database.Cursor c = raw.rawQuery(sql, null)) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    @Test
    public void backfillsFoundAndMissing() {
        migrate();

        assertThat(intOf("SELECT COUNT(*) FROM TaskResult WHERE taskId = 1 AND status = 'FOUND'"))
                .isEqualTo(1);
        assertThat(intOf("SELECT COUNT(*) FROM TaskResult WHERE taskId = 1 AND status = 'MISSING'"))
                .isEqualTo(1);
        assertThat(strOf("SELECT epc FROM TaskResult WHERE taskId = 1 AND status = 'FOUND'"))
                .isEqualTo("E1");
        assertThat(intOf("SELECT inventoryTime FROM TaskResult WHERE taskId = 1 AND status = 'FOUND'"))
                .isEqualTo(555);
    }

    @Test
    public void backfillsExtraWithClaimFields() {
        migrate();

        assertThat(intOf("SELECT COUNT(*) FROM TaskResult WHERE taskId = 1 AND status = 'EXTRA'"))
                .isEqualTo(1);
        assertThat(strOf("SELECT assetType FROM TaskResult WHERE status = 'EXTRA'"))
                .isEqualTo("打印机");
        assertThat(strOf("SELECT dept FROM TaskResult WHERE status = 'EXTRA'"))
                .isEqualTo("财务部");
    }

    @Test
    public void releasesAssetsOfCompletedTasksOnly() {
        migrate();

        // 已完成任务的资产回池
        assertThat(intOf("SELECT taskId FROM Asset WHERE id = 1")).isZero();
        assertThat(intOf("SELECT taskId FROM Asset WHERE id = 2")).isZero();
        assertThat(strOf("SELECT inventory_time FROM Asset WHERE id = 1")).isNull();

        // 进行中任务的资产不动
        assertThat(intOf("SELECT taskId FROM Asset WHERE id = 3")).isEqualTo(2);
    }

    @Test
    public void deletesScanRecordsOfCompletedTasks() {
        migrate();

        assertThat(intOf("SELECT COUNT(*) FROM ScanRecord WHERE taskId = 1")).isZero();
        assertThat(intOf("SELECT COUNT(*) FROM ExtraClaim")).isZero();
    }
}
```

- [ ] **Step 8: 跑测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.data.TaskResultDaoTest" --tests "com.huaxia.inventory.data.Migration1To2Test"`
Expected: `TaskResultDaoTest` 2 个 + `Migration1To2Test` 4 个全 PASS

若 `TaskResultDaoTest` 报 Room schema 校验失败（"Migration didn't properly handle" 或列不匹配），
说明 `MIGRATION_1_2` 里的建表 DDL 与实体定义不一致 —— 按报错里的
`Expected/Found` 逐列对齐（注意可空性：`assetType` 等字段在实体上是 `@Nullable`，
DDL 里就不能写 `NOT NULL`）。

- [ ] **Step 9: 更新迁移链守卫测试**

`DatabaseMigrationChainGuardTest` 会检查 `version` 与迁移注册是否配套。运行：

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.data.DatabaseMigrationChainGuardTest"`

按失败信息把断言里的期望版本从 `1` 改成 `2`、把 `MIGRATION_1_2` 加进期望列表（不要删测试）。

- [ ] **Step 10: 跑全量测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿

- [ ] **Step 11: 提交**

```bash
git add app/src/main/java/com/huaxia/inventory/data/entity/TaskResult.java \
        app/src/main/java/com/huaxia/inventory/data/dao/TaskResultDao.java \
        app/src/main/java/com/huaxia/inventory/data/AppDatabase.java \
        app/src/main/java/com/huaxia/inventory/InventoryApp.java \
        app/src/test/java/com/huaxia/inventory/data/TaskResultDaoTest.java \
        app/src/test/java/com/huaxia/inventory/data/Migration1To2Test.java \
        app/src/test/java/com/huaxia/inventory/data/DatabaseMigrationChainGuardTest.java
git commit -m "feat(data): TaskResult 表 + schema v2 迁移（回填历史并释放已完成任务资产）"
```

---

## Task 2: ResultRepository（唯一的状态分流点）

**Files:**
- Create: `app/src/main/java/com/huaxia/inventory/data/ResultRepository.java`
- Test: `app/src/test/java/com/huaxia/inventory/data/ResultRepositoryTest.java`

**Interfaces:**
- Consumes: `TaskResultDao`（Task 1）、`AssetDao`、`ScanRecordDao`、`ExtraClaimDao`、`InventoryTaskDao.findById`
- Produces:
  - `ResultRepository(AppDatabase db)`
  - `ResultRepository.Counts counts(long taskId, String taskStatus)`，字段 `expected/found/missing/extra`
  - `List<ResultRepository.ResultRow> page(long taskId, String taskStatus, Tab tab, int limit, int offset)`
  - `enum Tab { ALL, FOUND, MISSING, EXTRA }`
  - `ResultRow` 字段：`epc / assetType / assetModel / serialNumber / custodian / dept / status / inventoryTime / scannedAt / rssi / rawRowJson`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/huaxia/inventory/data/ResultRepositoryTest.java`：

```java
package com.huaxia.inventory.data;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.data.entity.TaskResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ResultRepositoryTest {

    private AppDatabase db;
    private ResultRepository repo;
    private long taskId;

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries().build();
        repo = new ResultRepository(db);

        InventoryTask t = new InventoryTask();
        t.sourceFileName = "T";
        t.status = "IN_PROGRESS";
        t.expectedCount = 3;
        taskId = db.inventoryTaskDao().insert(t);
    }

    @After
    public void tearDown() { db.close(); }

    private Asset asset(String epc, int rowIndex) {
        Asset a = new Asset();
        a.taskId = taskId;
        a.excelRowIndex = rowIndex;
        a.epc = epc;
        a.dept = "行政部";
        db.assetDao().upsertOne(a);
        return db.assetDao().findByEpc(taskId, epc);
    }

    private void scan(String epc, Long matchedAssetId) {
        ScanRecord s = new ScanRecord();
        s.taskId = taskId;
        s.epc = epc;
        s.scannedAt = 1000L + matchedAssetId;
        s.matchedAssetId = matchedAssetId;
        db.scanRecordDao().insert(s);
    }

    private void seedInProgress() {
        Asset a1 = asset("E1", 1);
        asset("E2", 2);
        asset("E3", 3);
        scan("E1", a1.id);      // 已盘
        scan("X9", null);       // 盘盈
    }

    @Test
    public void inProgress_countsComeFromLiveTables() {
        seedInProgress();

        ResultRepository.Counts c = repo.counts(taskId, "IN_PROGRESS");

        assertThat(c.expected).isEqualTo(3);
        assertThat(c.found).isEqualTo(1);
        assertThat(c.missing).isEqualTo(2);
        assertThat(c.extra).isEqualTo(1);
    }

    @Test
    public void inProgress_allPageMarksStatusPerRow() {
        seedInProgress();

        List<ResultRepository.ResultRow> rows =
                repo.page(taskId, "IN_PROGRESS", ResultRepository.Tab.ALL, 10, 0);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).epc).isEqualTo("E1");
        assertThat(rows.get(0).status).isEqualTo("FOUND");
        assertThat(rows.get(1).status).isEqualTo("MISSING");
    }

    @Test
    public void completed_countsComeFromSnapshot() {
        // 直接把快照写进去，模拟"已经完成"的任务
        List<TaskResult> rows = new ArrayList<>();
        rows.add(snapshotRow("E1", 1, "FOUND"));
        rows.add(snapshotRow("E2", 2, "MISSING"));
        rows.add(snapshotRow("X9", 0, "EXTRA"));
        db.taskResultDao().insertAll(rows);

        ResultRepository.Counts c = repo.counts(taskId, "COMPLETED");

        assertThat(c.expected).isEqualTo(2);
        assertThat(c.found).isEqualTo(1);
        assertThat(c.missing).isEqualTo(1);
        assertThat(c.extra).isEqualTo(1);
    }

    @Test
    public void completed_allPageExcludesExtraAndKeepsOrder() {
        List<TaskResult> rows = new ArrayList<>();
        rows.add(snapshotRow("E2", 2, "MISSING"));
        rows.add(snapshotRow("E1", 1, "FOUND"));
        rows.add(snapshotRow("X9", 0, "EXTRA"));
        db.taskResultDao().insertAll(rows);

        List<ResultRepository.ResultRow> all =
                repo.page(taskId, "COMPLETED", ResultRepository.Tab.ALL, 10, 0);

        assertThat(all).extracting(r -> r.epc).containsExactly("E1", "E2");
        assertThat(repo.page(taskId, "COMPLETED", ResultRepository.Tab.EXTRA, 10, 0))
                .extracting(r -> r.epc).containsExactly("X9");
    }

    private TaskResult snapshotRow(String epc, int rowIndex, String status) {
        TaskResult r = new TaskResult();
        r.taskId = taskId;
        r.rowIndex = rowIndex;
        r.epc = epc;
        r.dept = "行政部";
        r.status = status;
        return r;
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.data.ResultRepositoryTest"`
Expected: 编译失败 — `ResultRepository` 不存在

- [ ] **Step 3: 实现 ResultRepository**

`app/src/main/java/com/huaxia/inventory/data/ResultRepository.java`：

```java
package com.huaxia.inventory.data;

import androidx.annotation.Nullable;

import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ExtraClaim;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.data.entity.TaskResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务结果读取的唯一入口。
 *
 * <p>任务状态决定数据来源：</p>
 * <ul>
 *   <li><b>进行中 / 暂停</b> —— 实时查 {@code Asset} 与 {@code ScanRecord}</li>
 *   <li><b>已完成</b> —— 查冻结快照 {@code TaskResult}（资产早已释放回库存池）</li>
 * </ul>
 *
 * <p>这个分支只应出现在本类里。散落到 ViewModel / 导出器 / Web API 各处必然漂移。</p>
 */
public class ResultRepository {

    public enum Tab { ALL, FOUND, MISSING, EXTRA }

    /** 统一口径：{@code expected} = 已盘 + 盘亏，不含盘盈。 */
    public static class Counts {
        public final int expected;
        public final int found;
        public final int missing;
        public final int extra;

        public Counts(int expected, int found, int missing, int extra) {
            this.expected = expected;
            this.found = found;
            this.missing = missing;
            this.extra = extra;
        }
    }

    /** 扁平结果行 —— Asset / ScanRecord / TaskResult 三种来源统一成这一种。 */
    public static class ResultRow {
        public String epc;
        public String assetType;
        public String assetModel;
        public String serialNumber;
        public String custodian;
        public String dept;
        public String status;
        @Nullable public Long inventoryTime;
        @Nullable public Long scannedAt;
        @Nullable public String rssi;
        @Nullable public String rawRowJson;
    }

    private final AppDatabase db;

    public ResultRepository(AppDatabase db) {
        this.db = db;
    }

    public Counts counts(long taskId, String taskStatus) {
        if (isCompleted(taskStatus)) {
            return new Counts(
                    db.taskResultDao().countExpected(taskId),
                    db.taskResultDao().countByStatus(taskId, "FOUND"),
                    db.taskResultDao().countByStatus(taskId, "MISSING"),
                    db.taskResultDao().countByStatus(taskId, "EXTRA"));
        }
        return new Counts(
                db.assetDao().countByTask(taskId),
                db.assetDao().countFound(taskId),
                db.assetDao().countMissing(taskId),
                db.scanRecordDao().countExtras(taskId));
    }

    public List<ResultRow> page(long taskId, String taskStatus, Tab tab,
                                int limit, int offset) {
        return isCompleted(taskStatus)
                ? pageFromSnapshot(taskId, tab, limit, offset)
                : pageFromLiveTables(taskId, tab, limit, offset);
    }

    private static boolean isCompleted(String taskStatus) {
        return "COMPLETED".equals(taskStatus);
    }

    private List<ResultRow> pageFromSnapshot(long taskId, Tab tab, int limit, int offset) {
        List<TaskResult> rows;
        switch (tab) {
            case FOUND:   rows = db.taskResultDao().pageFound(taskId, limit, offset); break;
            case MISSING: rows = db.taskResultDao().pageMissing(taskId, limit, offset); break;
            case EXTRA:   rows = db.taskResultDao().pageExtra(taskId, limit, offset); break;
            case ALL:
            default:      rows = db.taskResultDao().pageAll(taskId, limit, offset); break;
        }
        List<ResultRow> out = new ArrayList<>(rows.size());
        for (TaskResult r : rows) out.add(fromTaskResult(r));
        return out;
    }

    private List<ResultRow> pageFromLiveTables(long taskId, Tab tab, int limit, int offset) {
        if (tab == Tab.EXTRA) return extras(taskId, limit, offset);

        List<Asset> assets;
        Set<Long> foundIds = null;
        switch (tab) {
            case FOUND:
                assets = db.assetDao().pageFound(taskId, limit, offset);
                break;
            case MISSING:
                assets = db.assetDao().pageMissing(taskId, limit, offset);
                break;
            case ALL:
            default:
                assets = db.assetDao().pageAll(taskId, limit, offset);
                // ALL 需要逐行区分已盘/盘亏，取一次已盘 id 集合
                foundIds = new HashSet<>(db.assetDao().findFoundIds(taskId));
                break;
        }
        List<ResultRow> out = new ArrayList<>(assets.size());
        for (Asset a : assets) {
            ResultRow row = fromAsset(a);
            row.status = tab == Tab.FOUND ? "FOUND"
                    : tab == Tab.MISSING ? "MISSING"
                    : (foundIds != null && foundIds.contains(a.id) ? "FOUND" : "MISSING");
            out.add(row);
        }
        return out;
    }

    private List<ResultRow> extras(long taskId, int limit, int offset) {
        List<ScanRecord> records = db.scanRecordDao().pageExtra(taskId, limit, offset);
        if (records.isEmpty()) return new ArrayList<>();

        List<Long> ids = new ArrayList<>(records.size());
        for (ScanRecord s : records) ids.add(s.id);
        Map<Long, ExtraClaim> claims = new HashMap<>();
        for (ExtraClaim c : db.extraClaimDao().findByScanRecordIds(ids)) {
            claims.put(c.scanRecordId, c);
        }

        List<ResultRow> out = new ArrayList<>(records.size());
        for (ScanRecord s : records) {
            ExtraClaim c = claims.get(s.id);
            ResultRow row = new ResultRow();
            row.epc = c != null && c.epc != null ? c.epc : s.epc;
            row.assetType = c == null ? null : c.assetType;
            row.assetModel = c == null ? null : c.assetModel;
            row.serialNumber = c == null ? null : c.serialNumber;
            row.custodian = c == null ? null : c.custodian;
            row.dept = c == null ? null : c.dept;
            row.status = "EXTRA";
            row.scannedAt = s.scannedAt;
            row.rssi = s.rssi;
            out.add(row);
        }
        return out;
    }

    private static ResultRow fromAsset(Asset a) {
        ResultRow row = new ResultRow();
        row.epc = a.epc;
        row.assetType = a.assetType;
        row.assetModel = a.assetModel;
        row.serialNumber = a.serialNumber;
        row.custodian = a.custodian;
        row.dept = a.dept;
        row.inventoryTime = a.inventoryTime;
        row.rawRowJson = a.rawRowJson;
        return row;
    }

    private static ResultRow fromTaskResult(TaskResult r) {
        ResultRow row = new ResultRow();
        row.epc = r.epc;
        row.assetType = r.assetType;
        row.assetModel = r.assetModel;
        row.serialNumber = r.serialNumber;
        row.custodian = r.custodian;
        row.dept = r.dept;
        row.status = r.status;
        row.inventoryTime = r.inventoryTime;
        row.scannedAt = r.scannedAt;
        row.rssi = r.rssi;
        row.rawRowJson = r.rawRowJson;
        return row;
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.data.ResultRepositoryTest"`
Expected: 4 个用例 PASS

- [ ] **Step 5: 跑全量测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿（此时还没有调用方切过来）

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/huaxia/inventory/data/ResultRepository.java \
        app/src/test/java/com/huaxia/inventory/data/ResultRepositoryTest.java
git commit -m "feat(data): ResultRepository 收敛任务结果读取（按状态分流快照/实时表）"
```

---

## Task 3: completeTask 六步（写快照 → 释放 → 清记录 → 翻状态）

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/data/dao/AssetDao.java`（+`findAllByTask`）
- Modify: `app/src/main/java/com/huaxia/inventory/data/dao/ScanRecordDao.java`（+`findExtras`）
- Modify: `app/src/main/java/com/huaxia/inventory/service/TaskService.java`
- Test: `app/src/test/java/com/huaxia/inventory/service/TaskServiceCompleteTest.java`

**Interfaces:**
- Consumes: `TaskResultDao.insertAll`（Task 1）、`ExtraClaimDao.findByScanRecordIds`
- Produces:
  - `AssetDao.findAllByTask(long taskId)` → `List<Asset>`
  - `ScanRecordDao.findExtras(long taskId)` → `List<ScanRecord>`（未匹配资产的扫描记录）
  - `TaskService.completeTask(long taskId)` 行为变更（签名不变）

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/huaxia/inventory/service/TaskServiceCompleteTest.java`：

```java
package com.huaxia.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ExtraClaim;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.data.entity.TaskResult;
import com.huaxia.inventory.session.InventorySession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class TaskServiceCompleteTest {

    private AppDatabase db;
    private InventorySession session;
    private TaskService service;
    private long taskId;

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries().build();
        session = new InventorySession();
        service = new TaskService(db, session);

        seedPool("E1", "行政部");
        seedPool("E2", "行政部");
        seedPool("E3", "行政部");
        taskId = service.createTask("T", "行政部", null);
    }

    @After
    public void tearDown() { db.close(); }

    private void seedPool(String epc, String dept) {
        Asset a = new Asset();
        a.taskId = 0L;
        a.excelRowIndex = 1;
        a.epc = epc;
        a.dept = dept;
        db.assetDao().upsertOne(a);
    }

    private Asset assetOf(String epc) {
        return db.assetDao().findByEpc(taskId, epc);
    }

    private void scanFound(String epc, long assetId) {
        ScanRecord s = new ScanRecord();
        s.taskId = taskId;
        s.epc = epc;
        s.scannedAt = 1000L;
        s.matchedAssetId = assetId;
        db.scanRecordDao().insert(s);
    }

    @Test
    public void complete_writesSnapshotForEveryAsset() {
        Asset a1 = assetOf("E1");
        a1.inventoryTime = 1234L;
        db.assetDao().update(a1);
        scanFound("E1", a1.id);

        service.completeTask(taskId);

        List<TaskResult> rows = db.taskResultDao().findAll(taskId);
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.epc).containsExactlyInAnyOrder("E1", "E2", "E3");
        assertThat(db.taskResultDao().countByStatus(taskId, "FOUND")).isEqualTo(1);
        assertThat(db.taskResultDao().countByStatus(taskId, "MISSING")).isEqualTo(2);

        TaskResult found = rows.stream().filter(r -> "E1".equals(r.epc)).findFirst().get();
        assertThat(found.status).isEqualTo("FOUND");
        assertThat(found.inventoryTime).isEqualTo(1234L);
    }

    @Test
    public void complete_releasesAssetsBackToPool() {
        service.completeTask(taskId);

        assertThat(db.assetDao().countPool()).isEqualTo(3);
        assertThat(db.assetDao().countByTask(taskId)).isZero();
    }

    @Test
    public void complete_deletesScanRecordsAndClaims() {
        Asset a1 = assetOf("E1");
        scanFound("E1", a1.id);

        ScanRecord extra = new ScanRecord();
        extra.taskId = taskId;
        extra.epc = "X9";
        extra.scannedAt = 2000L;
        long extraId = db.scanRecordDao().insert(extra);

        ExtraClaim claim = new ExtraClaim();
        claim.scanRecordId = extraId;
        claim.epc = "X9";
        claim.assetType = "打印机";
        db.extraClaimDao().insert(claim);

        service.completeTask(taskId);

        assertThat(db.scanRecordDao().countExtras(taskId)).isZero();
        assertThat(db.extraClaimDao().findByScanRecordIds(
                java.util.Collections.singletonList(extraId))).isEmpty();

        // 盘盈补全信息必须已经进快照，否则报表会缺列
        List<TaskResult> extras = db.taskResultDao().pageExtra(taskId, 10, 0);
        assertThat(extras).hasSize(1);
        assertThat(extras.get(0).epc).isEqualTo("X9");
        assertThat(extras.get(0).assetType).isEqualTo("打印机");
    }

    @Test
    public void complete_clearsInventoryTimeOnReleasedAssets() {
        Asset a1 = assetOf("E1");
        a1.inventoryTime = 999L;
        db.assetDao().update(a1);

        service.completeTask(taskId);

        assertThat(db.assetDao().findByEpcGlobal("E1").inventoryTime).isNull();
    }

    @Test
    public void complete_keepsScannedUniqueCountOnTaskRow() {
        db.inventoryTaskDao().recomputeScannedCount(taskId);
        Asset a1 = assetOf("E1");
        scanFound("E1", a1.id);
        db.inventoryTaskDao().recomputeScannedCount(taskId);
        assertThat(db.inventoryTaskDao().findById(taskId).scannedUniqueCount).isEqualTo(1);

        service.completeTask(taskId);

        // 删扫描记录后不能把任务行上的计数冲成 0，否则任务列表显示 0/3
        assertThat(db.inventoryTaskDao().findById(taskId).scannedUniqueCount).isEqualTo(1);
        assertThat(db.inventoryTaskDao().findById(taskId).status).isEqualTo("COMPLETED");
    }

    @Test
    public void complete_isIdempotent() {
        service.completeTask(taskId);
        service.completeTask(taskId);

        assertThat(db.taskResultDao().findAll(taskId)).hasSize(3);
    }

    @Test
    public void complete_clearsSessionWhenCurrentTask() {
        session.setCurrentTaskId(taskId);

        service.completeTask(taskId);

        assertThat(session.getCurrentTaskId()).isNull();
    }

    @Test
    public void clearCompleted_removesSnapshotAndTaskShell() {
        service.completeTask(taskId);

        service.clearCompleted();

        assertThat(db.taskResultDao().findAll(taskId)).isEmpty();
        assertThat(db.inventoryTaskDao().findAllCompleted()).isEmpty();
        assertThat(db.assetDao().countPool()).isEqualTo(3);
    }

    @Test
    public void deleteCompletedTask_removesSnapshotButKeepsAssetsInPool() {
        service.completeTask(taskId);

        service.deleteTask(taskId);

        assertThat(db.taskResultDao().findAll(taskId)).isEmpty();
        assertThat(db.inventoryTaskDao().findById(taskId)).isNull();
        assertThat(db.assetDao().countPool()).isEqualTo(3);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.service.TaskServiceCompleteTest"`
Expected: 失败 — 快照行数为 0、资产没释放

- [ ] **Step 3: 补两个 DAO 方法**

在 `app/src/main/java/com/huaxia/inventory/data/dao/AssetDao.java` 的 `findFound(long taskId)` 之后插入：

```java
    /** 任务下全部资产（不含排序保证），供完成任务时写快照。 */
    @Query("SELECT * FROM Asset WHERE taskId = :taskId ORDER BY excelRowIndex ASC")
    List<Asset> findAllByTask(long taskId);
```

在 `app/src/main/java/com/huaxia/inventory/data/dao/ScanRecordDao.java` 的 `pageExtra(...)` 之后插入：

```java
    /** 任务下全部盘盈记录（未匹配到资产），供完成任务时写快照。 */
    @Query("SELECT * FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NULL " +
           "ORDER BY scannedAt ASC")
    List<ScanRecord> findExtras(long taskId);
```

- [ ] **Step 4: 改写 completeTask**

`app/src/main/java/com/huaxia/inventory/service/TaskService.java` 中，
把现有 `completeTask` 整个方法替换为：

```java
    /**
     * 完成任务 = 提交 + 释放资产。
     *
     * <p>单事务六步，顺序不可调换：</p>
     * <ol>
     *   <li>把该次盘点结果冻结进 {@code TaskResult}（必须先于任何清理）</li>
     *   <li>资产退回库存池</li>
     *   <li>清盘点时间（资产离开任务，时间即失效）</li>
     *   <li>丢弃划拨时的快照列（编辑值已成主数据，且已进 TaskResult）</li>
     *   <li>删扫描/补全记录（历史已在 TaskResult，留着只会让库膨胀）</li>
     *   <li>状态翻 COMPLETED</li>
     * </ol>
     *
     * <p>幂等：已是 COMPLETED 直接返回，不重复写快照。</p>
     */
    public void completeTask(long taskId) {
        db.runInTransaction(() -> {
            InventoryTask t = db.inventoryTaskDao().findById(taskId);
            if (t == null || "COMPLETED".equals(t.status)) return;

            db.taskResultDao().insertAll(buildSnapshot(taskId));

            db.assetDao().clearInventoryTimeForTask(taskId);
            db.assetDao().clearSnapshotForTask(taskId);
            db.assetDao().releaseTaskToPool(taskId);

            db.extraClaimDao().deleteByTask(taskId);
            db.scanRecordDao().deleteByTask(taskId);

            t.status = "COMPLETED";
            db.inventoryTaskDao().update(t);
        });
        Long cur = session.getCurrentTaskId();
        if (cur != null && cur == taskId) {
            session.clear();
        }
    }

    /** 把任务当前结果冻结成快照行。调用方必须在事务内、且在清理之前调用。 */
    private List<TaskResult> buildSnapshot(long taskId) {
        List<TaskResult> out = new java.util.ArrayList<>();

        java.util.Set<Long> foundIds =
                new java.util.HashSet<>(db.assetDao().findFoundIds(taskId));
        for (Asset a : db.assetDao().findAllByTask(taskId)) {
            TaskResult r = new TaskResult();
            r.taskId = taskId;
            r.rowIndex = a.excelRowIndex;
            r.assetId = a.id;
            r.epc = a.epc;
            r.assetType = a.assetType;
            r.assetModel = a.assetModel;
            r.serialNumber = a.serialNumber;
            r.custodian = a.custodian;
            r.dept = a.dept;
            r.status = foundIds.contains(a.id) ? "FOUND" : "MISSING";
            r.inventoryTime = a.inventoryTime;
            r.rawRowJson = a.rawRowJson;
            out.add(r);
        }

        List<ScanRecord> extras = db.scanRecordDao().findExtras(taskId);
        if (!extras.isEmpty()) {
            List<Long> ids = new java.util.ArrayList<>(extras.size());
            for (ScanRecord s : extras) ids.add(s.id);
            java.util.Map<Long, ExtraClaim> claims = new java.util.HashMap<>();
            for (ExtraClaim c : db.extraClaimDao().findByScanRecordIds(ids)) {
                claims.put(c.scanRecordId, c);
            }
            for (ScanRecord s : extras) {
                ExtraClaim c = claims.get(s.id);
                TaskResult r = new TaskResult();
                r.taskId = taskId;
                r.rowIndex = 0;
                r.assetId = null;
                r.epc = c != null && c.epc != null ? c.epc : s.epc;
                r.assetType = c == null ? null : c.assetType;
                r.assetModel = c == null ? null : c.assetModel;
                r.serialNumber = c == null ? null : c.serialNumber;
                r.custodian = c == null ? null : c.custodian;
                r.dept = c == null ? null : c.dept;
                r.status = "EXTRA";
                r.scannedAt = s.scannedAt;
                r.rssi = s.rssi;
                out.add(r);
            }
        }
        return out;
    }
```

补 import：

```java
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ExtraClaim;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.data.entity.TaskResult;
```

- [ ] **Step 5: 调整 clearCompleted 与 deleteTask**

`clearCompleted()` 方法体替换为：

```java
    public void clearCompleted() {
        db.runInTransaction(() -> {
            for (InventoryTask t : db.inventoryTaskDao().findAllCompleted()) {
                db.taskResultDao().deleteByTask(t.id);
                // 历史任务（v1 时代完成的）可能还留着扫描记录，一并清掉
                db.extraClaimDao().deleteByTask(t.id);
                db.scanRecordDao().deleteByTask(t.id);
            }
            db.inventoryTaskDao().deleteAllCompleted();
        });
    }
```

`deleteTask(long taskId)` 的 `db.runInTransaction(() -> { ... })` 块替换为：

```java
        db.runInTransaction(() -> {
            InventoryTask t = db.inventoryTaskDao().findById(taskId);
            boolean completed = t != null && "COMPLETED".equals(t.status);
            if (completed) {
                // 已完成：资产在完成时已经回池，只需丢弃历史快照
                db.taskResultDao().deleteByTask(taskId);
            } else {
                db.assetDao().revertEditedFields(taskId);
                db.assetDao().clearInventoryTimeForTask(taskId);
                db.assetDao().clearSnapshotForTask(taskId);
                releasedHolder[0] = db.assetDao().releaseTaskToPool(taskId);
            }
            db.extraClaimDao().deleteByTask(taskId);
            db.scanRecordDao().deleteByTask(taskId);
            db.inventoryTaskDao().deleteById(taskId);
        });
```

- [ ] **Step 6: 跑测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.service.TaskServiceCompleteTest"`
Expected: 9 个用例 PASS

- [ ] **Step 7: 跑全量测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿。若 `TaskServiceTest.completeTask_keepsEditedFieldsAndClearsSnapshot` 失败，
是因为完成后资产已回池 —— 把该用例的断言改成「资产回到池里、编辑值在快照里」，
**不要删测试**。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/huaxia/inventory/data/dao/AssetDao.java \
        app/src/main/java/com/huaxia/inventory/data/dao/ScanRecordDao.java \
        app/src/main/java/com/huaxia/inventory/service/TaskService.java \
        app/src/test/java/com/huaxia/inventory/service/TaskServiceCompleteTest.java \
        app/src/test/java/com/huaxia/inventory/service/TaskServiceTest.java
git commit -m "feat(task): 完成任务时冻结结果快照并释放资产回库存池"
```

---

## Task 4: App 侧读取路径切到 ResultRepository

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/ui/result/ResultViewModel.java`
- Modify: `app/src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java`
- Modify: `app/src/main/java/com/huaxia/inventory/ui/scan/ScanFragment.java`
- Modify: `app/src/main/java/com/huaxia/inventory/excel/ExcelExporter.java`
- Modify: `app/src/main/java/com/huaxia/inventory/web/api/ExportApi.java`（`exportStreamed` 的调用方）

**Interfaces:**
- Consumes: `ResultRepository`（Task 2）
- Produces:
  - `ExcelExporter.exportStreamed(OutputStream out, String taskName, String taskStatus, List<String> originalHeaders, ResultRepository repo, long taskId, File parentTmpDir)`
  - `ExcelExporter.writeAssetRow(XlsxStreamWriter, ResultRepository.ResultRow, List<String>)`（私有）
  - `ExcelExporter.writeExtraRow(XlsxStreamWriter, List<String>, ResultRepository.ResultRow)`（私有）

- [ ] **Step 1: 改 ExcelExporter 的签名与主循环**

把 `exportStreamed` 整个方法替换为：

```java
    public static void exportStreamed(
            OutputStream out,
            String taskName,
            String taskStatus,
            List<String> originalHeaders,
            ResultRepository repo,
            long taskId,
            java.io.File parentTmpDir) throws IOException {

        List<String> fullHeaders = new ArrayList<>(originalHeaders);
        fullHeaders.addAll(RESULT_COLUMNS);

        ResultRepository.Counts counts = repo.counts(taskId, taskStatus);

        try (XlsxStreamWriter writer = new XlsxStreamWriter(out, fullHeaders, parentTmpDir)) {
            // 统计先算好 (在写表头之前) — 让"应盘/已盘/盘亏/盘盈"统计行能紧跟"导出时间"行
            writer.writeRow("固定资产盘点结果 - " + taskName);
            writer.writeRow("导出时间:", DateUtil.formatTimestamp(System.currentTimeMillis()));
            writer.writeRow("应盘", counts.expected, "已盘", counts.found,
                    "盘亏", counts.missing, "盘盈", counts.extra);
            writer.writeRow(fullHeaders.toArray(new String[0]));

            // 分页流式写资产（已盘 + 盘亏）
            for (int offset = 0; offset < counts.expected; offset += STREAM_PAGE_SIZE) {
                for (ResultRepository.ResultRow r : repo.page(taskId, taskStatus,
                        ResultRepository.Tab.ALL, STREAM_PAGE_SIZE, offset)) {
                    writeAssetRow(writer, r, originalHeaders);
                }
            }

            // 分页流式写盘盈（补全信息已合并进 ResultRow，无需再查 ExtraClaim）
            if (counts.extra > 0) {
                writer.writeRow("");
                writer.writeRow("【盘盈记录】");
                writer.writeRow(fullHeaders.toArray(new String[0]));
                for (int offset = 0; offset < counts.extra; offset += STREAM_PAGE_SIZE) {
                    for (ResultRepository.ResultRow r : repo.page(taskId, taskStatus,
                            ResultRepository.Tab.EXTRA, STREAM_PAGE_SIZE, offset)) {
                        writeExtraRow(writer, originalHeaders, r);
                    }
                }
            }
        }
    }
```

- [ ] **Step 2: 把两个行写入器改成吃 ResultRow**

`writeAssetRow` 的签名改为：

```java
    private static void writeAssetRow(XlsxStreamWriter writer,
                                      ResultRepository.ResultRow r,
                                      List<String> originalHeaders) {
```

方法体保持原样，只把 `a.rawRowJson` 改成 `r.rawRowJson`、把原来通过
`isFound.test(a.id)` 得到的已盘/盘亏判定改成直接用 `r.status`
（`"FOUND"` → 已盘，`"MISSING"` → 盘亏），`a.inventoryTime` 改成 `r.inventoryTime`。

`writeExtraRow` 的签名改为：

```java
    private static void writeExtraRow(XlsxStreamWriter writer,
                                      List<String> originalHeaders,
                                      ResultRepository.ResultRow r) {
```

方法体保持原样，把 `s.epc` / `s.scannedAt` 改成 `r.epc` / `r.scannedAt`，
把 `claim.getAssetType()` 等改成 `r.assetType` / `r.assetModel` / `r.serialNumber` /
`r.custodian` / `r.dept`。

删掉不再使用的 import（`Asset`、`ScanRecord`、`ExtraClaim`、`AssetDao`、
`ScanRecordDao`、`ExtraClaimDao`、`HashSet`、`HashMap`、`Set`、`Map`），补：

```java
import com.huaxia.inventory.data.ResultRepository;
```

- [ ] **Step 3: 改 ResultFragment 的调用**

`app/src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java` 里：

1. 表头重建从 `pageAll(taskId, 1, 0)` 改为走 Repository：

```java
                ResultRepository repo = new ResultRepository(app.getDb());
                List<String> originalHeaders = new ArrayList<>();
                List<ResultRepository.ResultRow> firstPage = repo.page(
                        taskId, task.status, ResultRepository.Tab.ALL, 1, 0);
                if (!firstPage.isEmpty() && firstPage.get(0).rawRowJson != null) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper =
                            new com.fasterxml.jackson.databind.ObjectMapper();
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> raw = mapper.readValue(
                            firstPage.get(0).rawRowJson, java.util.Map.class);
                    // 源里的"盘点日期"是空占位列, 导出时由"盘点时间"取代, 这里过滤掉避免重复.
                    for (String k : raw.keySet()) {
                        if (!"盘点日期".equals(k)) originalHeaders.add(k);
                    }
                }
```

2. 导出调用改为：

```java
                try (java.io.OutputStream out = resolver.openOutputStream(uri)) {
                    ExcelExporter.exportStreamed(out, task.sourceFileName, task.status,
                            originalHeaders, repo, taskId, cacheDir);
                }
```

- [ ] **Step 4: 改 ResultViewModel**

`ResultViewModel` 里所有 `db.assetDao().countByTask/countFound/countMissing`、
`db.scanRecordDao().countExtras`、`pageAll/pageFound/pageMissing/pageExtra` 调用，
统一换成 Repository：

```java
    private final ResultRepository repo;
```

```java
        this.repo = new ResultRepository(db);
```

统计：

```java
            ResultRepository.Counts c = repo.counts(taskId, taskStatus);
            int total = c.expected;
            int found = c.found;
            int missing = c.missing;
            int extra = c.extra;
```

分页（把原来的 `List<Asset>` / `List<ScanRecord>` 分支改成统一的 `List<ResultRow>`）：

```java
            List<ResultRepository.ResultRow> rows = repo.page(taskId, taskStatus, tab, PAGE_SIZE, offset);
```

其中 `taskStatus` 从 `db.inventoryTaskDao().findById(taskId).status` 取（任务不存在时按
`"IN_PROGRESS"` 处理）。适配器 `AssetResultAdapter` / `ExtraResultAdapter` 的入参类型
同步从 `Asset` / `ScanRecord` 改为 `ResultRow`（字段名已对齐，改动是机械替换）。

- [ ] **Step 5: 改 ScanFragment 的统计**

`app/src/main/java/com/huaxia/inventory/ui/scan/ScanFragment.java` 第 254 行附近
`app.getDb().scanRecordDao().countByTask(tid)` 改为：

```java
            InventoryTask task = app.getDb().inventoryTaskDao().findById(tid);
            ResultRepository.Counts c = new ResultRepository(app.getDb())
                    .counts(tid, task == null ? "IN_PROGRESS" : task.status);
            int scans = c.found + c.extra;
```

- [ ] **Step 6: 改 ExportApi 的调用**

`app/src/main/java/com/huaxia/inventory/web/api/ExportApi.java` 的 `taskReport` 里：

```java
            try (OutputStream os = new FileOutputStream(out)) {
                ExcelExporter.exportStreamed(os, task.sourceFileName, task.status,
                        rebuildHeaders(taskId), new ResultRepository(db), taskId,
                        ctx.getCacheDir());
            } catch (IOException e) {
```

`rebuildHeaders` 里的 `db.assetDao().pageAll(taskId, 1, 0)` 改为：

```java
        List<ResultRepository.ResultRow> firstPage = new ResultRepository(db)
                .page(taskId, db.inventoryTaskDao().findById(taskId) == null
                        ? "IN_PROGRESS" : db.inventoryTaskDao().findById(taskId).status,
                        ResultRepository.Tab.ALL, 1, 0);
```

- [ ] **Step 7: 跑测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿。`ExcelExporter*Test` 里若有直接调旧签名的地方，按新签名改造（传
`ResultRepository` + `taskStatus`），**不要删测试**。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/huaxia/inventory/ui/result \
        app/src/main/java/com/huaxia/inventory/ui/scan/ScanFragment.java \
        app/src/main/java/com/huaxia/inventory/excel/ExcelExporter.java \
        app/src/main/java/com/huaxia/inventory/web/api/ExportApi.java \
        app/src/test/java/com/huaxia/inventory/excel
git commit -m "refactor(result): App 结果读取与导出改走 ResultRepository"
```

---

## Task 5: Web 侧读取路径切到 ResultRepository

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/web/api/AssetApi.java`
- Modify: `app/src/main/java/com/huaxia/inventory/web/api/TaskApi.java`
- Modify: `app/src/main/java/com/huaxia/inventory/web/WebApiRegistry.java`
- Test: `app/src/test/java/com/huaxia/inventory/web/WebServerIntegrationTest.java`

**Interfaces:**
- Consumes: `ResultRepository`（Task 2）
- Produces: 无新增公开接口（`AssetApi` / `TaskApi` 构造器各加一个 `ResultRepository` 参数）

- [ ] **Step 1: 写失败测试**

在 `WebServerIntegrationTest` 中追加：

```java
    /**
     * 完成任务后资产回池，但结果接口仍必须返回数据 —— 这是本次改动的核心不变量。
     */
    @Test
    public void completedTask_resultsStillAvailable() throws Exception {
        com.huaxia.inventory.data.entity.InventoryTask t =
                new com.huaxia.inventory.data.entity.InventoryTask();
        t.sourceFileName = "完成任务";
        t.status = "IN_PROGRESS";
        t.expectedCount = 2;
        long id = db.inventoryTaskDao().insert(t);

        for (String epc : new String[]{"E1", "E2"}) {
            com.huaxia.inventory.data.entity.Asset a =
                    new com.huaxia.inventory.data.entity.Asset();
            a.taskId = id;
            a.excelRowIndex = 1;
            a.epc = epc;
            db.assetDao().upsertOne(a);
        }

        HttpURLConnection complete = open("POST", "/api/tasks/" + id + "/complete");
        complete.setDoOutput(true);
        complete.setRequestProperty("Content-Type", "application/json");
        complete.setFixedLengthStreamingMode(2);
        try (OutputStream os = complete.getOutputStream()) {
            os.write("{}".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(complete.getResponseCode()).isEqualTo(200);
        readAll(complete.getInputStream());

        // 资产已回池
        assertThat(db.assetDao().countPool()).isEqualTo(2);

        // 结果仍然查得到
        HttpURLConnection results = open("GET",
                "/api/tasks/" + id + "/results?tab=ALL&page=1&pageSize=10");
        assertThat(results.getResponseCode()).isEqualTo(200);
        String body = readAll(results.getInputStream());
        assertThat(body).contains("\"total\":2");
        assertThat(body).contains("E1");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.web.WebServerIntegrationTest"`
Expected: 新用例失败 —— 完成后 `total` 为 0

- [ ] **Step 3: 改 AssetApi**

1. 构造器加参数：

```java
    private final ResultRepository repo;

    public AssetApi(AppDatabase db) {
        this.db = db;
        this.repo = new ResultRepository(db);
    }
```

2. `results(...)` 方法整体替换为：

```java
    public WebResponse results(WebRequest req) throws ApiException {
        long taskId = req.pathParamLong("id");
        com.huaxia.inventory.data.entity.InventoryTask task =
                db.inventoryTaskDao().findById(taskId);
        if (task == null) {
            throw ApiException.notFound("TASK_NOT_FOUND", "任务不存在");
        }
        String tab = req.query("tab") == null
                ? "ALL" : req.query("tab").toUpperCase(Locale.ROOT);
        ResultRepository.Tab which;
        switch (tab) {
            case "FOUND":   which = ResultRepository.Tab.FOUND; break;
            case "MISSING": which = ResultRepository.Tab.MISSING; break;
            case "EXTRA":   which = ResultRepository.Tab.EXTRA; break;
            default:        which = ResultRepository.Tab.ALL; break;
        }
        int page = Math.max(1, req.queryInt("page", 1));
        int size = clampSize(req.queryInt("pageSize", DEFAULT_PAGE_SIZE));
        int total = totalFor(task.id, task.status, which);

        List<Map<String, Object>> items = new ArrayList<>();
        for (ResultRepository.ResultRow r : repo.page(taskId, task.status, which,
                size, (page - 1) * size)) {
            items.add(toDto(r));
        }
        return WebResponse.ok(envelope(items, page, size, total));
    }

    private int totalFor(long taskId, String status, ResultRepository.Tab tab) {
        ResultRepository.Counts c = repo.counts(taskId, status);
        switch (tab) {
            case FOUND:   return c.found;
            case MISSING: return c.missing;
            case EXTRA:   return c.extra;
            case ALL:
            default:      return c.expected;
        }
    }

    private static Map<String, Object> toDto(ResultRepository.ResultRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("epc", displayEpc(r.epc));
        m.put("assetType", r.assetType);
        m.put("assetModel", r.assetModel);
        m.put("serialNumber", r.serialNumber);
        m.put("dept", r.dept);
        m.put("custodian", r.custodian);
        m.put("status", r.status);
        m.put("inventoryTime", r.inventoryTime);
        m.put("scannedAt", r.scannedAt);
        m.put("rssi", r.rssi);
        return m;
    }
```

删掉原来的 `extraDto(...)`（已并入 `toDto`）。

- [ ] **Step 4: 改 TaskApi.summary**

`summary(InventoryTask t)` 里把：

```java
        ScanAggregate agg = db.scanRecordDao().aggregateStats(t.id);
        int scanned = agg.matchedCount;
        int missing = Math.max(0, t.expectedCount - scanned);
```

改为：

```java
        ResultRepository.Counts c = repo.counts(t.id, t.status);
        int scanned = c.found;
        int missing = c.missing;
        int extra = c.extra;
```

并把后面的 `m.put("extra", agg.extraCount)` 改成 `m.put("extra", extra)`、
`m.put("lastScannedAt", agg.lastScannedAt)` 改成
`m.put("lastScannedAt", db.scanRecordDao().aggregateStats(t.id).lastScannedAt)`
（已完成任务没有扫描记录，这个值会是 0，属预期）。

构造器同样加：

```java
    private final ResultRepository repo;
```

```java
        this.repo = new ResultRepository(db);
```

- [ ] **Step 5: 更新 WebApiRegistry**

```java
        AssetApi assets = new AssetApi(db);
        TaskApi tasks = new TaskApi(db, new TaskService(db, session), session);
```

两个构造器签名没变（Repository 在内部创建），无需改动。若编译报错，按实际签名调整。

- [ ] **Step 6: 跑测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿，含新增的 `completedTask_resultsStillAvailable`

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/huaxia/inventory/web \
        app/src/test/java/com/huaxia/inventory/web/WebServerIntegrationTest.java
git commit -m "refactor(web): 结果与任务统计改走 ResultRepository"
```

---

## Task 6: 回归与文档

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 更新 README**

在 `README.md` 的「## 功能」列表中，把这条：

```
- 编辑的草稿 / 提交语义：进行中或暂停任务的字段编辑为**草稿**（不改主数据）；**完成任务 = 提交**（编辑值成为主数据）；**删除 / 重置任务 = 回滚**到导入原值，并清盘点时间
```

改为：

```
- 编辑的草稿 / 提交语义：进行中或暂停任务的字段编辑为**草稿**（不改主数据）；**完成任务 = 提交 + 释放资产**（编辑值成为主数据，同时结果冻结进 `TaskResult`、资产退回库存池，可被新任务重新选择）；**删除 / 重置任务 = 回滚**到导入原值，并清盘点时间
```

在「## 技术栈」的 Room 那行补一句版本：

```
- Room 2.6（本地数据库，schema **v2**：v1 为首次发布基线，v2 新增 `TaskResult` 冻结快照表）
```

- [ ] **Step 2: 跑全量测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿

- [ ] **Step 3: 构建**

Run: `./gradlew :app:assembleDebug :app:assembleRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 真机验收（必须，不可跳过）**

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

按顺序验证（升级安装，**不要卸载** —— 要验证迁移）：

1. 升级前先记下：任务页有几个已完成任务、某个已完成任务的结果页行数、库存池资产数
2. 升级安装并启动（迁移在首次打开数据库时执行）
3. 资产 tab：库存池资产数 **增加了**已完成任务那批（迁移把它们释放了）
4. 任务页：已完成任务的「查看详情」仍能看到原来的已盘/盘亏/盘盈数量与列表
5. 导出该已完成任务的报表：行数与升级前一致
6. 新建一个任务并选择原已完成任务所在的部门：**能划拨到这批资产**（本次改动的核心目的）
7. 扫描几张 → 点「完成」→ 资产立刻回到库存池，任务详情仍显示刚盘的结果
8. 再新建一个任务：**能再次选到这批资产**

- [ ] **Step 5: 提交**

```bash
git add README.md
git commit -m "docs: README 更新任务完成语义与 schema v2 说明"
```

---

## 自查记录

**Spec 覆盖检查**（对照 `docs/superpowers/specs/2026-11-13-task-complete-release-assets-design.md`）：

| Spec 章节 | 对应任务 |
|---|---|
| §3.1 `TaskResult` 表 | Task 1 |
| §3.2 迁移 v1→v2（回填 + 释放 + 清记录） | Task 1 |
| §4.1 `completeTask` 六步 | Task 3 |
| §4.2 `clearCompleted` | Task 3 |
| §4.3 `deleteTask` | Task 3 |
| §5 `ResultRepository` + 口径约定 | Task 2 |
| §5.1 六个调用点 | Task 4（App 四处 + ExportApi）、Task 5（Web 两处） |
| §5.2 `scannedUniqueCount` 口径 | Task 3（测试 `complete_keepsScannedUniqueCountOnTaskRow`） |
| §6 边界情况 | Task 3 测试（幂等、空任务、盘盈、session） |
| §7 测试 | Task 1/2/3/5 各自的测试步骤 |
| §8 交付清单 | Task 1–6 全覆盖 |
| §9 不做的事 | 计划中无对应任务，符合预期 |

**占位符扫描**：无 TBD / TODO / 「类似 Task N」。Task 4 的两个行写入器给出了新签名与
逐项替换说明（方法体是既有代码的类型替换，不是新写逻辑）。

**类型一致性核对**：
- `ResultRepository.Tab` / `Counts` / `ResultRow` 在 Task 2 定义，Task 4/5 一致使用。
- `ExcelExporter.exportStreamed` 新签名在 Task 4 定义，`ResultFragment` 与 `ExportApi` 同步改。
- `TaskResultDao` 方法在 Task 1 定义，Task 2/3 使用的方法名一致
  （`countExpected` / `countByStatus` / `pageAll` / `pageFound` / `pageMissing` / `pageExtra` / `findAll` / `deleteByTask` / `insertAll`）。
- `AssetDao.findAllByTask`、`ScanRecordDao.findExtras` 在 Task 3 定义并使用。


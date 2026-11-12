# 内嵌 HTTP 服务（Web 看板 / 任务管理 / 导入导出）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 PDA 内嵌 NanoHTTPD 服务，让同网段浏览器完成资产总览大屏、盘点任务管理、Excel 导入导出与报表打印。

**Architecture:** 新增 `web/` 包承载服务器与 API，`assets/web/` 承载无构建步骤的原生前端；任务写操作通过新抽出的 `service/TaskService` 与 App 端共用同一套事务逻辑；服务由前台 `WebServerService` 持有，设置页手动启停。

**Tech Stack:** Java 11 / AndroidX / Room 2.6 / NanoHTTPD 2.3.1 / Jackson 2.17 / Apache POI 5.2.5（复用现有导出器）/ JUnit4 + Robolectric + Mockito

## Global Constraints

- 语言与编译级别：Java 11，`sourceCompatibility` / `targetCompatibility` 保持 `VERSION_11`，**不引入 Kotlin**。
- 数据库：不改任何 Room 实体，`AppDatabase.version` 保持 `1`，**不新增迁移**。
- 新增依赖只允许 `org.nanohttpd:nanohttpd:2.3.1` 一个；JSON 复用现有 Jackson，Excel 复用现有 POI 导出器。
- 线程：所有 HTTP handler 跑在服务器线程池，直接调 Room 同步 DAO；**禁止**在 handler 内切主线程或访问 View。
- 日志：统一 `Log.x(TAG, ...)`，禁止 `System.out.println`；打印固资编号/EPC 必须走 `ExcelExporter.redactTail` 脱敏。
- 异常：禁止空 catch、禁止 `printStackTrace`、禁止只打日志后 `return null`。HTTP 层统一抛 `ApiException`。
- 包名与 `applicationId` 不变：`com.assetmanager.inventory` / `com.huaxia.inventory`。
- 每个任务结束时 `./gradlew :app:testDebugUnitTest` 必须全绿（基线 257 个用例）。
- 提交信息用中文 `type(scope): 摘要`，与仓库现有风格一致。

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `service/TaskService.java` | 任务六操作的事务体，App 与 Web 共用 |
| `web/WebServer.java` | NanoHTTPD 子类：线程池、请求体读取、异常兜底 |
| `web/WebRouter.java` | 路径+方法 → Handler 映射，支持 `{id}` 段 |
| `web/WebRequest.java` | 请求值对象：method/path/query/header/body/pathParam |
| `web/WebResponse.java` | 响应值对象：JSON / 字节 / 文件流 |
| `web/StaticAssets.java` | `assets/web/` 读取、MIME、路径穿越防护 |
| `web/ApiException.java` | 业务异常 → 稳定 code + 中文文案 + HTTP 状态 |
| `web/api/DashboardApi.java` | 大屏聚合 + 1 秒快照缓存 |
| `web/api/TaskApi.java` | 任务增删改查 |
| `web/api/AssetApi.java` | 资产台账 / 任务结果只读查询 |
| `web/api/ImportApi.java` | Excel 上传落库存池 |
| `web/api/ExportApi.java` | 任务报表 / 台账 / 模板下载 |
| `web/WebServerService.java` | 前台服务，持有服务器生命周期 |
| `ui/setting/WebServerDialog.java` | 开关 + IP/端口展示 |
| `util/SqlLikeEscaper.java` | LIKE 关键词转义，App 与 Web 共用 |
| `assets/web/**` | 前端静态页（无构建步骤） |

---

## Task 1: 抽出 TaskService（纯重构，行为不变）

**Files:**
- Create: `app/src/main/java/com/huaxia/inventory/service/TaskService.java`
- Modify: `app/src/main/java/com/huaxia/inventory/ui/task/TaskListViewModel.java`
- Test: `app/src/test/java/com/huaxia/inventory/service/TaskServiceTest.java`

**Interfaces:**
- Consumes: `AppDatabase`（`inventoryTaskDao()` / `assetDao()` / `extraClaimDao()` / `scanRecordDao()` / `runInTransaction`）、`InventorySession`
- Produces:
  - `TaskService(AppDatabase db, InventorySession session)`
  - `long createTask(String name, String dept, String remark)`
  - `int countPoolAssetsForDept(String dept)`
  - `List<String> distinctPoolDepts()`
  - `int deleteTask(long taskId)` → 退回库存池的资产数
  - `void clearCompleted()`
  - `boolean switchToTask(long taskId)`
  - `void pauseTask(long taskId)` / `void resumeTask(long taskId)` / `void completeTask(long taskId)`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/huaxia/inventory/service/TaskServiceTest.java`：

```java
package com.huaxia.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.session.InventorySession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class TaskServiceTest {

    private AppDatabase db;
    private InventorySession session;
    private TaskService service;

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        session = new InventorySession();
        service = new TaskService(db, session);
    }

    @After
    public void tearDown() {
        db.close();
    }

    private void seedPool(String epc, String dept) {
        Asset a = new Asset();
        a.taskId = 0L;
        a.excelRowIndex = 1;
        a.epc = epc;
        a.dept = dept;
        a.assetType = "笔记本";
        db.assetDao().upsertOne(a);
    }

    @Test
    public void createTask_assignsWholePoolWhenDeptNull() {
        seedPool("1001", "行政部");
        seedPool("1002", "财务部");

        long id = service.createTask("Q4 全量", null, "备注");

        InventoryTask t = db.inventoryTaskDao().findById(id);
        assertThat(t).isNotNull();
        assertThat(t.status).isEqualTo("IN_PROGRESS");
        assertThat(t.remark).isEqualTo("备注");
        assertThat(t.expectedCount).isEqualTo(2);
        assertThat(db.assetDao().countPool()).isZero();
    }

    @Test
    public void createTask_assignsOnlyMatchingDept() {
        seedPool("1001", "行政部");
        seedPool("1002", "财务部");

        long id = service.createTask("行政部盘点", "行政部", null);

        InventoryTask t = db.inventoryTaskDao().findById(id);
        assertThat(t.dept).isEqualTo("行政部");
        assertThat(t.expectedCount).isEqualTo(1);
        assertThat(db.assetDao().countPool()).isEqualTo(1);
    }

    @Test
    public void deleteTask_releasesAssetsAndClearsTask() {
        seedPool("1001", "行政部");
        long id = service.createTask("T", null, null);

        int released = service.deleteTask(id);

        assertThat(released).isEqualTo(1);
        assertThat(db.inventoryTaskDao().findById(id)).isNull();
        assertThat(db.assetDao().countPool()).isEqualTo(1);
    }

    @Test
    public void deleteTask_rollsBackEditedFieldsWhenNotCompleted() {
        seedPool("1001", "行政部");
        long id = service.createTask("T", null, null);

        Asset edited = db.assetDao().findByEpc(id, "1001");
        edited.custodian = "张三";
        db.assetDao().update(edited);

        service.deleteTask(id);

        Asset back = db.assetDao().findByEpcGlobal("1001");
        assertThat(back.custodian).isNull();
    }

    @Test
    public void completeTask_keepsEditedFieldsAndClearsSnapshot() {
        seedPool("1001", "行政部");
        long id = service.createTask("T", null, null);

        Asset edited = db.assetDao().findByEpc(id, "1001");
        edited.custodian = "李四";
        db.assetDao().update(edited);

        service.completeTask(id);

        Asset kept = db.assetDao().findByEpc(id, "1001");
        assertThat(kept.custodian).isEqualTo("李四");
        assertThat(kept.origCustodian).isNull();
        assertThat(db.inventoryTaskDao().findById(id).status).isEqualTo("COMPLETED");
    }

    @Test
    public void resumeTask_setsSessionCurrentTask() {
        seedPool("1001", "行政部");
        long id = service.createTask("T", null, null);
        service.pauseTask(id);
        assertThat(session.getCurrentTaskId()).isNull();

        service.resumeTask(id);

        assertThat(session.getCurrentTaskId()).isEqualTo(id);
    }

    @Test
    public void pauseTask_clearsSessionWhenPausingCurrent() {
        seedPool("1001", "行政部");
        long id = service.createTask("T", null, null);
        session.setCurrentTaskId(id);

        service.pauseTask(id);

        assertThat(session.getCurrentTaskId()).isNull();
        assertThat(db.inventoryTaskDao().findById(id).status).isEqualTo("PAUSED");
    }

    @Test
    public void clearCompleted_releasesAssetsBackToPool() {
        seedPool("1001", "行政部");
        long id = service.createTask("T", null, null);
        service.completeTask(id);

        service.clearCompleted();

        assertThat(db.inventoryTaskDao().findAllCompleted()).isEmpty();
        assertThat(db.assetDao().countPool()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.service.TaskServiceTest"`
Expected: 编译失败 — `TaskService` 不存在

- [ ] **Step 3: 创建 TaskService**

`app/src/main/java/com/huaxia/inventory/service/TaskService.java`：

```java
package com.huaxia.inventory.service;

import androidx.annotation.Nullable;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.session.InventorySession;

import java.util.List;

/**
 * 盘点任务的写操作 —— 事务体的唯一实现。
 *
 * <p>App 端 {@code TaskListViewModel} 与 Web 端 {@code web/api/TaskApi} 共用本类，
 * 避免「删除任务」这类有严格顺序语义的操作被复制成两份后漂移。
 *
 * <p>所有方法<b>同步执行、直接抛异常</b>；线程调度与异常转回调由调用方负责。
 * 调用方必须保证不在主线程调用（Room 同步查询禁止主线程）。
 */
public class TaskService {

    private final AppDatabase db;
    private final InventorySession session;

    public TaskService(AppDatabase db, InventorySession session) {
        this.db = db;
        this.session = session;
    }

    /**
     * 创建任务并把库存池资产划拨过去。
     * <p>单事务: 插入任务 + 划拨 + 修正 expectedCount —— 任一步失败整体回滚，
     * 防止进程被杀留下「任务壳 + 0 划拨」孤儿。
     *
     * @param dept null / 空串 = 不限部门（整个库存池划过去）
     * @return 新任务 id
     */
    public long createTask(String name, String dept, @Nullable String remark) {
        String effectiveDept = (dept == null || dept.isEmpty()) ? null : dept;
        InventoryTask t = new InventoryTask();
        t.sourceFileName = name;
        t.importedAt = System.currentTimeMillis();
        t.status = "IN_PROGRESS";
        t.dept = effectiveDept;
        t.remark = (remark == null || remark.isEmpty()) ? null : remark;
        t.expectedCount = countPoolAssetsForDept(effectiveDept);

        final long[] idHolder = {0L};
        final int[] assignedHolder = {0};
        db.runInTransaction(() -> {
            idHolder[0] = db.inventoryTaskDao().insert(t);
            assignedHolder[0] = db.assetDao().assignPoolToTask(idHolder[0], effectiveDept);
        });
        // 不在事务内 update 是有意的: 让划拨先 commit，再独立 UPDATE 让
        // expectedCount 与实际划拨一致。t.id 仍是 0，必须重新 findById 拿 fresh row。
        if (assignedHolder[0] != t.expectedCount) {
            InventoryTask fresh = db.inventoryTaskDao().findById(idHolder[0]);
            if (fresh != null) {
                fresh.expectedCount = assignedHolder[0];
                db.inventoryTaskDao().update(fresh);
            }
        }
        return idHolder[0];
    }

    /** 库存池(taskId=0)中资产数；dept 为空则全库存池。 */
    public int countPoolAssetsForDept(@Nullable String dept) {
        if (dept == null || dept.isEmpty()) return db.assetDao().countPool();
        return db.assetDao().countPoolByDept(dept);
    }

    /** 库存池中所有不重复的领用部门（建任务下拉选项）。 */
    public List<String> distinctPoolDepts() {
        return db.assetDao().distinctDeptPool();
    }

    /**
     * 删除任务 = 释放资产回库存池 + 清扫描记录 + 删任务壳 + 清 session。
     * <p>未提交(进行中/暂停)先回滚编辑过的字段；已提交(COMPLETED)保留编辑值。
     * 两种情况都清盘点时间并丢弃快照。
     *
     * @return 退回库存池的资产数
     */
    public int deleteTask(long taskId) {
        final int[] releasedHolder = {0};
        db.runInTransaction(() -> {
            InventoryTask t = db.inventoryTaskDao().findById(taskId);
            boolean completed = t != null && "COMPLETED".equals(t.status);
            if (!completed) {
                db.assetDao().revertEditedFields(taskId);
            }
            db.assetDao().clearInventoryTimeForTask(taskId);
            // 丢弃快照必须在 release 之前: release 把 taskId 归零后按 taskId 过滤会匹配 0 行。
            db.assetDao().clearSnapshotForTask(taskId);
            releasedHolder[0] = db.assetDao().releaseTaskToPool(taskId);
            db.extraClaimDao().deleteByTask(taskId);
            db.scanRecordDao().deleteByTask(taskId);
            db.inventoryTaskDao().deleteById(taskId);
        });
        Long cur = session.getCurrentTaskId();
        if (cur != null && cur == taskId) {
            session.clear();
        }
        return releasedHolder[0];
    }

    /** 清除已完成: 资产退回库存池(保留提交值) + 清盘点时间 + 清扫描/补全 + 删任务壳。 */
    public void clearCompleted() {
        db.runInTransaction(() -> {
            List<InventoryTask> done = db.inventoryTaskDao().findAllCompleted();
            for (InventoryTask t : done) {
                db.extraClaimDao().deleteByTask(t.id);
                db.scanRecordDao().deleteByTask(t.id);
                db.assetDao().clearInventoryTimeForTask(t.id);
                db.assetDao().releaseTaskToPool(t.id);
            }
            db.inventoryTaskDao().deleteAllCompleted();
        });
    }

    /** 切换当前任务；任务不存在返回 false。 */
    public boolean switchToTask(long taskId) {
        InventoryTask t = db.inventoryTaskDao().findById(taskId);
        if (t != null) {
            session.setCurrentTaskId(taskId);
        }
        return t != null;
    }

    /** 暂停: IN_PROGRESS -> PAUSED；命中当前任务时清 session。 */
    public void pauseTask(long taskId) {
        InventoryTask t = db.inventoryTaskDao().findById(taskId);
        if (t != null && "IN_PROGRESS".equals(t.status)) {
            t.status = "PAUSED";
            db.inventoryTaskDao().update(t);
            Long cur = session.getCurrentTaskId();
            if (cur != null && cur == taskId) {
                session.clear();
            }
        }
    }

    /** 完成 = 提交: 翻 COMPLETED + 丢弃快照（编辑值从此成为主数据）。 */
    public void completeTask(long taskId) {
        db.runInTransaction(() -> {
            InventoryTask t = db.inventoryTaskDao().findById(taskId);
            if (t != null && !"COMPLETED".equals(t.status)) {
                t.status = "COMPLETED";
                db.inventoryTaskDao().update(t);
                db.assetDao().clearSnapshotForTask(taskId);
            }
        });
        Long cur = session.getCurrentTaskId();
        if (cur != null && cur == taskId) {
            session.clear();
        }
    }

    /** 恢复: PAUSED -> IN_PROGRESS，并设为当前任务让 PDA 接着扫。 */
    public void resumeTask(long taskId) {
        InventoryTask t = db.inventoryTaskDao().findById(taskId);
        if (t != null && "PAUSED".equals(t.status)) {
            t.status = "IN_PROGRESS";
            db.inventoryTaskDao().update(t);
            session.setCurrentTaskId(taskId);
        }
    }
}
```

- [ ] **Step 4: 让 TaskListViewModel 委托**

在 `app/src/main/java/com/huaxia/inventory/ui/task/TaskListViewModel.java` 中：

1. 新增字段与构造初始化：

```java
    private final TaskService taskService;
```

```java
        this.taskService = new TaskService(app.getDb(), app.getSession());
```

2. 把 `createTask` 方法体替换为：

```java
    public void createTask(String name, String dept, String remark, CreateCallback cb) {
        diskIO.execute(() -> {
            try {
                long taskId = taskService.createTask(name, dept, remark);
                if (cb != null) cb.onCreated(taskId);
            } catch (Exception e) {
                Log.e(TAG, "createTask failed", e);
                if (cb != null) cb.onError(e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
    }
```

3. `countPoolAssetsForDept` 方法体替换为 `return taskService.countPoolAssetsForDept(dept);`
4. `loadDistinctDeptAll` 方法体替换为 `diskIO.execute(() -> cb.onLoaded(taskService.distinctPoolDepts()));`
5. `deleteTask` 方法体替换为：

```java
    public void deleteTask(long taskId, DeleteCallback cb) {
        diskIO.execute(() -> {
            try {
                int released = taskService.deleteTask(taskId);
                if (cb != null) cb.onDeleted(taskId, released);
            } catch (Exception e) {
                Log.e(TAG, "deleteTask failed", e);
                if (cb != null) cb.onError(taskId, e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
    }
```

6. `clearCompleted` 方法体替换为：

```java
    public void clearCompleted() {
        diskIO.execute(() -> {
            try {
                taskService.clearCompleted();
            } catch (Exception e) {
                Log.e(TAG, "clearCompleted failed", e);
            }
        });
    }
```

7. `switchToTask` 方法体替换为：

```java
    public void switchToTask(long taskId, SwitchCallback cb) {
        diskIO.execute(() -> {
            try {
                boolean ok = taskService.switchToTask(taskId);
                if (cb != null) cb.onSwitched(taskId, ok);
            } catch (Exception e) {
                Log.e(TAG, "switchToTask failed", e);
                if (cb != null) cb.onError(e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
    }
```

8. `pauseTask` / `completeTask` / `resumeTask` 方法体分别替换为：

```java
    public void pauseTask(long taskId, Runnable cb) {
        diskIO.execute(() -> {
            try {
                taskService.pauseTask(taskId);
            } catch (Exception e) {
                Log.e(TAG, "pauseTask failed", e);
            } finally {
                if (cb != null) cb.run();
            }
        });
    }
```

```java
    public void completeTask(long taskId, Runnable cb) {
        diskIO.execute(() -> {
            try {
                taskService.completeTask(taskId);
            } catch (Exception e) {
                Log.e(TAG, "completeTask failed", e);
            } finally {
                if (cb != null) cb.run();
            }
        });
    }
```

```java
    public void resumeTask(long taskId, Runnable cb) {
        diskIO.execute(() -> {
            try {
                taskService.resumeTask(taskId);
            } catch (Exception e) {
                Log.e(TAG, "resumeTask failed", e);
            } finally {
                if (cb != null) cb.run();
            }
        });
    }
```

9. 补 import：`import com.huaxia.inventory.service.TaskService;`，并删掉不再使用的 import（IDE 提示什么删什么，不要手改其它行）。

- [ ] **Step 5: 跑全量测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿，含新增 `TaskServiceTest` 8 个用例与原有 257 个用例

若 `DeleteTaskRollbackOrderGuardTest` / `CompleteTaskSnapshotTest` / `CreateTaskUsesFreshRowTest` 等源码守卫测试失败，
说明委托改写改变了它们断言的方法体结构 —— 按失败信息调整断言目标到 `TaskService`，**不要删测试**。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/huaxia/inventory/service/TaskService.java \
        app/src/main/java/com/huaxia/inventory/ui/task/TaskListViewModel.java \
        app/src/test/java/com/huaxia/inventory/service/TaskServiceTest.java
git commit -m "refactor(task): 抽出 TaskService 供 App 与 Web 共用任务事务"
```

---

## Task 2: HTTP 基础设施（NanoHTTPD + 路由 + 静态资源）

**Files:**
- Modify: `app/build.gradle`（dependencies 段）
- Create: `app/src/main/java/com/huaxia/inventory/web/ApiException.java`
- Create: `app/src/main/java/com/huaxia/inventory/web/WebRequest.java`
- Create: `app/src/main/java/com/huaxia/inventory/web/WebResponse.java`
- Create: `app/src/main/java/com/huaxia/inventory/web/WebRouter.java`
- Create: `app/src/main/java/com/huaxia/inventory/web/StaticAssets.java`
- Create: `app/src/main/java/com/huaxia/inventory/web/WebServer.java`
- Test: `app/src/test/java/com/huaxia/inventory/web/WebRouterTest.java`
- Test: `app/src/test/java/com/huaxia/inventory/web/StaticAssetsTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `interface WebRouter.Handler { WebResponse handle(WebRequest req) throws ApiException; }`
  - `WebRouter.get/post/delete(String pattern, Handler)`、`WebResponse dispatch(WebRequest)`
  - `WebRequest(String method, String path, Map<String,String> query, Map<String,String> headers, byte[] body)`，方法 `method() path() query(n) queryInt(n,def) header(n) body() bodyAsString() pathParam(n)`
  - `WebResponse.json(int,Object)`、`ok(Object)`、`bytes(byte[],String)`、`file(InputStream,long,String,String)`、`asset(InputStream,long,String)`
  - `StaticAssets.serve(Context, String path)`、`mimeOf(String)`、`isSafe(String)`
  - `WebServer(int port, Context, AppDatabase, InventorySession)`、`startServer()`、`stopServer()`

- [ ] **Step 1: 加依赖**

在 `app/build.gradle` 的 `dependencies { ... }` 块中，`implementation 'com.fasterxml.jackson.core:jackson-databind:2.17.0'` 之后插入：

```groovy
    // 内嵌 HTTP 服务：单 jar、零反射、R8 安全。仅用其 socket + 请求解析，
    // 路由/JSON/静态资源全部自实现（见 web/ 包）。
    implementation 'org.nanohttpd:nanohttpd:2.3.1'
```

- [ ] **Step 2: 写失败测试**

`app/src/test/java/com/huaxia/inventory/web/WebRouterTest.java`：

```java
package com.huaxia.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class WebRouterTest {

    private static WebRequest req(String method, String path) {
        return new WebRequest(method, path, Collections.emptyMap(),
                Collections.emptyMap(), new byte[0]);
    }

    @Test
    public void dispatch_matchesExactPath() throws Exception {
        WebRouter router = new WebRouter();
        router.get("/api/ping", r -> WebResponse.ok(Collections.singletonMap("pong", true)));

        WebResponse res = router.dispatch(req("GET", "/api/ping"));

        assertThat(res.status).isEqualTo(200);
        assertThat(new String(res.bytes, "UTF-8")).contains("\"pong\":true");
    }

    @Test
    public void dispatch_extractsPathParam() throws Exception {
        WebRouter router = new WebRouter();
        router.get("/api/tasks/{id}", r -> WebResponse.ok(r.pathParam("id")));

        WebResponse res = router.dispatch(req("GET", "/api/tasks/42"));

        assertThat(new String(res.bytes, "UTF-8")).isEqualTo("\"42\"");
    }

    @Test
    public void dispatch_returns405WhenMethodMismatch() {
        WebRouter router = new WebRouter();
        router.get("/api/tasks", r -> WebResponse.ok("x"));

        WebResponse res = router.dispatch(req("POST", "/api/tasks"));

        assertThat(res.status).isEqualTo(405);
    }

    @Test
    public void dispatch_returns404ForUnknownPath() {
        WebRouter router = new WebRouter();
        WebResponse res = router.dispatch(req("GET", "/api/nope"));
        assertThat(res.status).isEqualTo(404);
    }

    @Test
    public void dispatch_parsesQueryParameters() throws Exception {
        WebRouter router = new WebRouter();
        router.get("/api/assets", r -> WebResponse.ok(r.query("keyword")));

        Map<String, String> q = new HashMap<>();
        q.put("keyword", "笔记本");
        WebRequest request = new WebRequest("GET", "/api/assets", q,
                Collections.emptyMap(), new byte[0]);
        WebResponse res = router.dispatch(request);

        assertThat(new String(res.bytes, "UTF-8")).contains("笔记本");
    }
}
```

`app/src/test/java/com/huaxia/inventory/web/StaticAssetsTest.java`：

```java
package com.huaxia.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class StaticAssetsTest {

    @Test
    public void mimeOf_knownTypes() {
        assertThat(StaticAssets.mimeOf("index.html")).isEqualTo("text/html; charset=utf-8");
        assertThat(StaticAssets.mimeOf("app.css")).isEqualTo("text/css; charset=utf-8");
        assertThat(StaticAssets.mimeOf("app.js")).isEqualTo("application/javascript; charset=utf-8");
        assertThat(StaticAssets.mimeOf("logo.svg")).isEqualTo("image/svg+xml");
    }

    @Test
    public void mimeOf_unknownFallsBackToOctetStream() {
        assertThat(StaticAssets.mimeOf("data.bin")).isEqualTo("application/octet-stream");
    }

    @Test
    public void isSafe_rejectsTraversal() {
        assertThat(StaticAssets.isSafe("/js/app.js")).isTrue();
        assertThat(StaticAssets.isSafe("/../secret")).isFalse();
        assertThat(StaticAssets.isSafe("/js/../../etc/passwd")).isFalse();
        assertThat(StaticAssets.isSafe("/js/..%2Fapp.js")).isFalse();
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.web.*"`
Expected: 编译失败 — `WebRouter` / `StaticAssets` 不存在

- [ ] **Step 4: 实现 ApiException**

`app/src/main/java/com/huaxia/inventory/web/ApiException.java`：

```java
package com.huaxia.inventory.web;

/**
 * 业务异常 → 统一错误信封 {"error":{"code","message"}}。
 *
 * <p>{@code code} 是稳定枚举，前端按它分支；{@code message} 是中文用户文案，可直接展示。
 */
public class ApiException extends Exception {

    public final int status;
    public final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "BAD_REQUEST", message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(404, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    public static ApiException tooLarge(String message) {
        return new ApiException(413, "PAYLOAD_TOO_LARGE", message);
    }
}
```

- [ ] **Step 5: 实现 WebRequest**

`app/src/main/java/com/huaxia/inventory/web/WebRequest.java`：

```java
package com.huaxia.inventory.web;

import androidx.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.util.Collections;
import java.util.Map;

/** HTTP 请求值对象 —— 与 NanoHTTPD 解耦，便于单测直接构造。 */
public final class WebRequest {

    private final String method;
    private final String path;
    private final Map<String, String> query;
    private final Map<String, String> headers;
    private final byte[] body;
    private final Map<String, String> pathParams;

    public WebRequest(String method, String path, Map<String, String> query,
                      Map<String, String> headers, byte[] body) {
        this(method, path, query, headers, body, Collections.emptyMap());
    }

    private WebRequest(String method, String path, Map<String, String> query,
                       Map<String, String> headers, byte[] body,
                       Map<String, String> pathParams) {
        this.method = method;
        this.path = path;
        this.query = query == null ? Collections.emptyMap() : query;
        this.headers = headers == null ? Collections.emptyMap() : headers;
        this.body = body == null ? new byte[0] : body;
        this.pathParams = pathParams;
    }

    /** 返回带 path 参数的新实例（路由匹配时由 WebRouter 调用）。 */
    public WebRequest withPathParams(Map<String, String> params) {
        return new WebRequest(method, path, query, headers, body, params);
    }

    public String method() { return method; }

    public String path() { return path; }

    @Nullable
    public String query(String name) { return query.get(name); }

    public int queryInt(String name, int defaultValue) {
        String raw = query.get(name);
        if (raw == null || raw.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Nullable
    public String header(String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    public byte[] body() { return body; }

    public String bodyAsString() {
        try {
            return new String(body, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return new String(body);
        }
    }

    @Nullable
    public String pathParam(String name) { return pathParams.get(name); }

    /** 取 path 参数并转 long；缺失或非法抛 400。 */
    public long pathParamLong(String name) throws ApiException {
        String raw = pathParams.get(name);
        if (raw == null) throw ApiException.badRequest("缺少路径参数 " + name);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("路径参数 " + name + " 必须是数字");
        }
    }
}
```

- [ ] **Step 6: 实现 WebResponse**

`app/src/main/java/com/huaxia/inventory/web/WebResponse.java`：

```java
package com.huaxia.inventory.web;

import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

/** HTTP 响应值对象：JSON / 原始字节 / 文件流 三种。 */
public final class WebResponse {

    public final int status;
    public final String mime;
    public final byte[] bytes;       // bytes 与 stream 二选一
    public final InputStream stream;
    public final long length;
    public final Map<String, String> headers;

    private WebResponse(int status, String mime, byte[] bytes,
                        InputStream stream, long length, Map<String, String> headers) {
        this.status = status;
        this.mime = mime;
        this.bytes = bytes;
        this.stream = stream;
        this.length = length;
        this.headers = headers;
    }

    public static WebResponse json(int status, Object payload) {
        return new WebResponse(status, "application/json; charset=utf-8",
                WebJson.write(payload), null, -1, Collections.emptyMap());
    }

    public static WebResponse ok(Object payload) {
        return json(200, payload);
    }

    public static WebResponse bytes(byte[] data, String mime) {
        return new WebResponse(200, mime, data, null, -1, Collections.emptyMap());
    }

    /**
     * 文件下载。
     * @param downloadName 非 null 时写 Content-Disposition，中文按 RFC 5987 编码
     */
    public static WebResponse file(InputStream in, long length, String mime,
                                   String downloadName) {
        Map<String, String> h = new java.util.HashMap<>();
        if (downloadName != null) {
            h.put("Content-Disposition", "attachment; filename*=UTF-8''"
                    + rfc5987(downloadName));
        }
        return new WebResponse(200, mime, null, in, length, h);
    }

    /** 静态资源：带 1 小时缓存头。 */
    public static WebResponse asset(InputStream in, long length, String mime) {
        Map<String, String> h = new java.util.HashMap<>();
        h.put("Cache-Control", "public, max-age=3600");
        return new WebResponse(200, mime, null, in, length, h);
    }

    public static WebResponse error(int status, String code, String message) {
        Map<String, Object> err = new java.util.HashMap<>();
        err.put("code", code);
        err.put("message", message);
        return json(status, Collections.singletonMap("error", err));
    }

    private static String rfc5987(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return "download";
        }
    }
}
```

- [ ] **Step 7: 实现 WebJson**

`app/src/main/java/com/huaxia/inventory/web/WebJson.java`：

```java
package com.huaxia.inventory.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** 共享 ObjectMapper —— 复用已有 Jackson 依赖，不新增库。 */
public final class WebJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private WebJson() {}

    public static byte[] write(Object payload) {
        try {
            return MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            return "{\"error\":{\"code\":\"INTERNAL\",\"message\":\"序列化失败\"}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    public static <T> T read(String json, Class<T> type) throws Exception {
        return MAPPER.readValue(json, type);
    }
}
```

- [ ] **Step 8: 实现 WebRouter**

`app/src/main/java/com/huaxia/inventory/web/WebRouter.java`：

```java
package com.huaxia.inventory.web;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 极简路由：方法 + 路径模板（支持 {name} 段）。 */
public final class WebRouter {

    public interface Handler {
        WebResponse handle(WebRequest req) throws ApiException;
    }

    private static final class Route {
        final String method;
        final String[] segments;
        final Handler handler;

        Route(String method, String pattern, Handler handler) {
            this.method = method;
            this.segments = split(pattern);
            this.handler = handler;
        }
    }

    private final List<Route> routes = new ArrayList<>();

    public void get(String pattern, Handler h) { routes.add(new Route("GET", pattern, h)); }
    public void post(String pattern, Handler h) { routes.add(new Route("POST", pattern, h)); }
    public void delete(String pattern, Handler h) { routes.add(new Route("DELETE", pattern, h)); }

    /** 分发请求；未匹配方法返回 405，未匹配路径返回 404。 */
    public WebResponse dispatch(WebRequest req) {
        boolean pathMatched = false;
        for (Route r : routes) {
            Map<String, String> params = match(r.segments, split(req.path()));
            if (params == null) continue;
            pathMatched = true;
            if (!r.method.equals(req.method())) continue;
            try {
                return r.handler.handle(req.withPathParams(params));
            } catch (ApiException e) {
                return WebResponse.error(e.status, e.code, e.getMessage());
            } catch (Exception e) {
                return WebResponse.error(500, "INTERNAL",
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        if (pathMatched) {
            return WebResponse.error(405, "METHOD_NOT_ALLOWED", "不支持该请求方法");
        }
        return WebResponse.error(404, "NOT_FOUND", "接口不存在");
    }

    @Nullable
    private static Map<String, String> match(String[] pattern, String[] actual) {
        if (pattern.length != actual.length) return null;
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < pattern.length; i++) {
            String p = pattern[i];
            if (p.startsWith("{") && p.endsWith("}")) {
                params.put(p.substring(1, p.length() - 1), actual[i]);
            } else if (!p.equals(actual[i])) {
                return null;
            }
        }
        return params;
    }

    private static String[] split(String path) {
        String p = path;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? new String[0] : p.split("/");
    }
}
```

- [ ] **Step 9: 实现 StaticAssets**

`app/src/main/java/com/huaxia/inventory/web/StaticAssets.java`：

```java
package com.huaxia.inventory.web;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** 从 assets/web/ 读静态资源；debug 构建优先读外部目录便于 adb push 调试。 */
public final class StaticAssets {

    private static final String ASSET_ROOT = "web";

    private StaticAssets() {}

    public static boolean isSafe(String path) {
        if (path == null) return false;
        String decoded = path;
        try {
            decoded = java.net.URLDecoder.decode(path, "UTF-8");
        } catch (Exception ignored) {
            // 解码失败按原串继续校验
        }
        return !decoded.contains("..") && !decoded.contains("\\") && decoded.startsWith("/");
    }

    public static String mimeOf(String path) {
        String p = path.toLowerCase(java.util.Locale.ROOT);
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    /**
     * 读取静态资源。
     * @param path 以 "/" 开头的资源路径，如 "/js/app.js"；根路径传 "/" 由调用方转成 index.html
     * @return 找不到返回 null
     */
    public static WebResponse serve(Context ctx, String path) throws IOException {
        if (!isSafe(path)) return null;
        String rel = path.substring(1);

        // debug 调试口子：外部目录存在同名文件则优先（release 不启用）
        if (com.huaxia.inventory.BuildConfig.DEBUG) {
            File external = new File(ctx.getExternalFilesDir(null), ASSET_ROOT + "/" + rel);
            if (external.isFile()) {
                return WebResponse.asset(new FileInputStream(external),
                        external.length(), mimeOf(rel));
            }
        }

        try (InputStream in = ctx.getAssets().open(ASSET_ROOT + "/" + rel)) {
            byte[] data = readAll(in);
            return WebResponse.bytes(data, mimeOf(rel));
        } catch (IOException notFound) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
```

- [ ] **Step 10: 实现 WebServer**

`app/src/main/java/com/huaxia/inventory/web/WebServer.java`：

```java
package com.huaxia.inventory.web;

import android.content.Context;
import android.util.Log;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.session.InventorySession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;

/** NanoHTTPD 子类：线程池、请求体读取、异常兜底、静态资源。 */
public class WebServer extends NanoHTTPD {

    private static final String TAG = "WebServer";

    /** 上传体积上限 20MB —— 千级数据 xlsx 通常 200KB~2MB，留足余量。 */
    private static final int MAX_BODY = 20 * 1024 * 1024;

    private final Context ctx;
    private final WebRouter router;
    private final ThreadPoolExecutor executor;

    public WebServer(int port, Context ctx, AppDatabase db, InventorySession session) {
        super(port);
        this.ctx = ctx.getApplicationContext();
        this.router = new WebRouter();
        // NanoHTTPD 默认每个请求新开一个线程；换成有界线程池，防止多个 Excel 导出把 PDA 打满。
        this.executor = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.AbortPolicy());
        setAsyncRunner(new PooledRunner(executor));
        WebApiRegistry.register(router, this.ctx, db, session);
    }

    /** 让 NanoHTTPD 的请求处理跑在我们的有界线程池里。 */
    private static final class PooledRunner implements NanoHTTPD.AsyncRunner {
        private final ThreadPoolExecutor pool;

        PooledRunner(ThreadPoolExecutor pool) { this.pool = pool; }

        @Override public void closeAll() { pool.shutdownNow(); }

        @Override public void closed(NanoHTTPD.ClientHandler clientHandler) { /* 无需回收 */ }

        @Override public void exec(NanoHTTPD.ClientHandler code) { pool.execute(code); }
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri == null || uri.isEmpty()) uri = "/";

        if (!uri.startsWith("/api/")) {
            return serveStatic(uri);
        }

        WebRequest req = toWebRequest(session);
        if (req == null) {
            return toNano(WebResponse.error(413, "PAYLOAD_TOO_LARGE", "请求体超过 20MB"));
        }
        return toNano(router.dispatch(req));
    }

    private Response serveStatic(String uri) {
        String path = "/".equals(uri) ? "/index.html" : uri;
        if (!path.startsWith("/static/") && !"/index.html".equals(path)) {
            // 非 /static 前缀的未知路径回落到单页应用入口（前端用 hash 路由）
            path = "/index.html";
        } else if (path.startsWith("/static/")) {
            path = path.substring("/static".length());
        }
        try {
            WebResponse res = StaticAssets.serve(ctx, path);
            if (res == null) {
                return toNano(WebResponse.error(404, "NOT_FOUND", "资源不存在"));
            }
            return toNano(res);
        } catch (IOException e) {
            Log.e(TAG, "static serve failed: " + path, e);
            return toNano(WebResponse.error(500, "INTERNAL", "读取资源失败"));
        }
    }

    private WebRequest toWebRequest(IHTTPSession session) {
        Map<String, String> query = new HashMap<>();
        for (Map.Entry<String, java.util.List<String>> e : session.getParameters().entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                query.put(e.getKey(), e.getValue().get(0));
            }
        }
        byte[] body = new byte[0];
        String lenHeader = session.getHeaders().get("content-length");
        long declared;
        try {
            declared = lenHeader == null ? 0L : Long.parseLong(lenHeader.trim());
        } catch (NumberFormatException e) {
            declared = 0L;
        }
        if (declared > MAX_BODY) return null;
        if (declared > 0) {
            try {
                body = readBody(session.getInputStream(), (int) declared);
            } catch (IOException e) {
                Log.e(TAG, "read body failed", e);
                return null;
            }
        }
        return new WebRequest(session.getMethod().name(), session.getUri(),
                query, session.getHeaders(), body);
    }

    private static byte[] readBody(InputStream in, int length) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(length);
        byte[] buf = new byte[8192];
        int remaining = length;
        while (remaining > 0) {
            int n = in.read(buf, 0, Math.min(buf.length, remaining));
            if (n <= 0) break;
            out.write(buf, 0, n);
            remaining -= n;
        }
        return out.toByteArray();
    }

    private static Response toNano(WebResponse res) {
        Response nano;
        if (res.bytes != null) {
            nano = newFixedLengthResponse(Response.Status.lookup(res.status),
                    res.mime, new java.io.ByteArrayInputStream(res.bytes), res.bytes.length);
        } else {
            nano = newFixedLengthResponse(Response.Status.lookup(res.status),
                    res.mime, res.stream, res.length);
        }
        for (Map.Entry<String, String> e : res.headers.entrySet()) {
            nano.addHeader(e.getKey(), e.getValue());
        }
        return nano;
    }

    @Override
    public void stop() {
        executor.shutdownNow();
        super.stop();
    }
}
```

- [ ] **Step 11: 实现 WebApiRegistry（先只挂 ping）**

`app/src/main/java/com/huaxia/inventory/web/WebApiRegistry.java`：

```java
package com.huaxia.inventory.web;

import android.content.Context;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.session.InventorySession;

import java.util.Collections;

/** 把各 API 模块挂到路由上 —— 后续任务在这里逐行追加。 */
final class WebApiRegistry {

    private WebApiRegistry() {}

    static void register(WebRouter router, Context ctx, AppDatabase db,
                         InventorySession session) {
        router.get("/api/ping", r -> WebResponse.ok(Collections.singletonMap("ok", true)));
    }
}
```

- [ ] **Step 12: 跑测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.web.*"`
Expected: `WebRouterTest` 5 个 + `StaticAssetsTest` 3 个全 PASS

- [ ] **Step 13: 跑全量测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿

- [ ] **Step 14: 提交**

```bash
git add app/build.gradle app/src/main/java/com/huaxia/inventory/web \
        app/src/test/java/com/huaxia/inventory/web
git commit -m "feat(web): NanoHTTPD 服务器骨架 + 路由 + 静态资源"
```

---

## Task 3: 前台服务 + 设置入口

**Files:**
- Create: `app/src/main/java/com/huaxia/inventory/web/WebServerService.java`
- Create: `app/src/main/java/com/huaxia/inventory/ui/setting/WebServerDialog.java`
- Create: `app/src/main/res/layout/dialog_web_server.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/menu/popup_settings.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/huaxia/inventory/MainActivity.java`
- Test: `app/src/test/java/com/huaxia/inventory/web/WebServerServiceTest.java`

**Interfaces:**
- Consumes: `WebServer(int port, Context, AppDatabase, InventorySession)`（Task 2）
- Produces:
  - `WebServerService.start(Context)` / `WebServerService.stop(Context)` 静态方法
  - `WebServerService.isRunning()` 静态布尔（`volatile`）
  - `WebServerService.activePort()` 静态 int（未运行为 `-1`）

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/huaxia/inventory/web/WebServerServiceTest.java`：

```java
package com.huaxia.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class WebServerServiceTest {

    @Test
    public void start_buildsForegroundIntentWithAction() {
        Context ctx = ApplicationProvider.getApplicationContext();
        Intent intent = WebServerService.buildStartIntent(ctx);
        assertThat(intent.getComponent().getClassName())
                .isEqualTo(WebServerService.class.getName());
        assertThat(intent.getAction()).isEqualTo(WebServerService.ACTION_START);
    }

    @Test
    public void stop_buildsStopIntent() {
        Context ctx = ApplicationProvider.getApplicationContext();
        Intent intent = WebServerService.buildStopIntent(ctx);
        assertThat(intent.getAction()).isEqualTo(WebServerService.ACTION_STOP);
    }

    @Test
    public void activePort_isMinusOneWhenNotRunning() {
        assertThat(WebServerService.isRunning()).isFalse();
        assertThat(WebServerService.activePort()).isEqualTo(-1);
    }

    @Test
    public void pickPort_skipsOccupiedPorts() throws Exception {
        java.net.ServerSocket busy = new java.net.ServerSocket(8080);
        try {
            int picked = WebServerService.pickPort(8080, 8089);
            assertThat(picked).isNotEqualTo(8080).isBetween(8081, 8089);
        } finally {
            busy.close();
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.web.WebServerServiceTest"`
Expected: 编译失败 — `WebServerService` 不存在

- [ ] **Step 3: 实现 WebServerService**

`app/src/main/java/com/huaxia/inventory/web/WebServerService.java`：

```java
package com.huaxia.inventory.web;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.MainActivity;
import com.huaxia.inventory.R;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 承载 {@link WebServer} 的前台服务。
 *
 * <p>Android 8+ 的后台限制下，前台服务是唯一能保证切后台/锁屏不断的方式。
 * 服务只负责生命周期与通知，业务全部在 {@link WebServer} 与其 handler 里。
 */
public class WebServerService extends Service {

    private static final String TAG = "WebServerService";

    public static final String ACTION_START = "com.huaxia.inventory.web.START";
    public static final String ACTION_STOP = "com.huaxia.inventory.web.STOP";

    private static final String CHANNEL_ID = "web_server";
    private static final int NOTIFICATION_ID = 0x5E7B;
    private static final int PORT_START = 8080;
    private static final int PORT_END = 8089;

    private static volatile boolean running = false;
    private static volatile int port = -1;

    private WebServer server;

    public static boolean isRunning() { return running; }

    public static int activePort() { return port; }

    public static Intent buildStartIntent(Context ctx) {
        Intent i = new Intent(ctx, WebServerService.class);
        i.setAction(ACTION_START);
        return i;
    }

    public static Intent buildStopIntent(Context ctx) {
        Intent i = new Intent(ctx, WebServerService.class);
        i.setAction(ACTION_STOP);
        return i;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (server != null) {
            updateNotification();
            return START_STICKY;
        }

        InventoryApp app = (InventoryApp) getApplication();
        int picked = pickPort(PORT_START, PORT_END);
        if (picked < 0) {
            Log.e(TAG, "no free port in " + PORT_START + "-" + PORT_END);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            server = new WebServer(picked, this, app.getDb(), app.getSession());
            server.start(5000, false);
            port = picked;
            running = true;
            Log.i(TAG, "web server started on port " + picked);
        } catch (IOException e) {
            Log.e(TAG, "web server start failed", e);
            server = null;
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundCompat();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
            server = null;
        }
        running = false;
        port = -1;
        clearExportCache();
        Log.i(TAG, "web server stopped");
        super.onDestroy();
    }

    /** 从 startPort 起找第一个可绑定端口；全占用返回 -1。 */
    public static int pickPort(int startPort, int endPort) {
        for (int p = startPort; p <= endPort; p++) {
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(true);
                probe.bind(new InetSocketAddress(p));
                return p;
            } catch (IOException ignored) {
                // 端口占用，继续试下一个
            }
        }
        return -1;
    }

    /** 本机所有 IPv4 非回环地址 —— WiFi 与热点一视同仁，不需要 ACCESS_WIFI_STATE。 */
    public static List<String> localIpv4Addresses() {
        List<String> result = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        result.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "enumerate interfaces failed", e);
        }
        return result;
    }

    private void startForegroundCompat() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.web_server_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        String addr = describeAddress();
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                buildStopIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_settings)
                .setContentTitle(getString(R.string.web_server_notification_title))
                .setContentText(addr)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(0, getString(R.string.web_server_stop), stop)
                .build();
    }

    /** 给通知与对话框共用的地址文案，如 "192.168.1.50:8080"。 */
    public static String describeAddress() {
        List<String> ips = localIpv4Addresses();
        if (ips.isEmpty()) return "未连接网络";
        return ips.get(0) + ":" + (port > 0 ? port : PORT_START);
    }

    private void clearExportCache() {
        for (String dir : new String[]{"web-export", "web-upload"}) {
            java.io.File d = new java.io.File(getCacheDir(), dir);
            java.io.File[] files = d.listFiles();
            if (files == null) continue;
            for (java.io.File f : files) {
                if (!f.delete()) Log.w(TAG, "failed to delete " + f.getName());
            }
        }
    }
}
```

- [ ] **Step 4: 加 Manifest 声明**

在 `app/src/main/AndroidManifest.xml` 的 `<manifest>` 下、`<application>` 前插入：

```xml
    <!-- 内嵌 Web 服务：监听 socket 需要 INTERNET；前台服务保证切后台不断 -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

在 `<application>` 内、`<activity>` 之后插入：

```xml
        <service
            android:name=".web.WebServerService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
```

- [ ] **Step 5: 加字符串**

在 `app/src/main/res/values/strings.xml` 的 `</resources>` 前插入：

```xml
    <!-- Web 服务 -->
    <string name="menu_action_web_server">Web 服务</string>
    <string name="web_server_channel_name">Web 服务</string>
    <string name="web_server_notification_title">资产管家 Web 服务运行中</string>
    <string name="web_server_stop">停止</string>
    <string name="web_server_dialog_title">Web 服务</string>
    <string name="web_server_toggle_on">开启</string>
    <string name="web_server_toggle_off">关闭</string>
    <string name="web_server_address_fmt">http://%1$s</string>
    <string name="web_server_hint">同一 WiFi 或连接本机热点后，在浏览器打开上面的地址。数据仅保存在本机，不经过任何服务器。</string>
    <string name="web_server_no_network">未连接网络，请先连接 WiFi 或开启热点</string>
    <string name="web_server_start_failed">启动失败，请检查网络状态后重试</string>
    <string name="web_server_running">运行中 · 端口 %1$d</string>
    <string name="web_server_stopped">已停止</string>
```

- [ ] **Step 6: 加菜单项**

在 `app/src/main/res/menu/popup_settings.xml` 的 `</menu>` 前插入：

```xml
    <item
        android:id="@+id/menu_action_web_server"
        android:title="@string/menu_action_web_server"
        android:icon="@drawable/ic_settings" />
```

- [ ] **Step 7: 实现对话框布局**

`app/src/main/res/layout/dialog_web_server.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:background="@drawable/bg_dialog_rounded"
    android:padding="20dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/web_server_dialog_title"
        android:textSize="18sp"
        android:textStyle="bold"
        android:textColor="@color/textPrimary" />

    <com.google.android.material.switchmaterial.SwitchMaterial
        android:id="@+id/switchWebServer"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="@string/web_server_toggle_on" />

    <TextView
        android:id="@+id/tvWebServerAddress"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:textSize="16sp"
        android:textStyle="bold"
        android:textColor="@color/primary"
        android:textIsSelectable="true" />

    <TextView
        android:id="@+id/tvWebServerHint"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:textSize="12sp"
        android:textColor="@color/textSecondary"
        android:text="@string/web_server_hint" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/btnWebServerClose"
        style="@style/Widget.MaterialComponents.Button.TextButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:text="@string/action_close" />
</LinearLayout>
```

若 `@color/textPrimary` / `@color/textSecondary` / `@color/primary` 在 `colors.xml` 中不存在，
改用文件里已有的等价颜色（先 `grep -n "textPrimary\|textSecondary\|primary" app/src/main/res/values/colors.xml` 确认）。

- [ ] **Step 8: 实现 WebServerDialog**

`app/src/main/java/com/huaxia/inventory/ui/setting/WebServerDialog.java`：

```java
package com.huaxia.inventory.ui.setting;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.huaxia.inventory.R;
import com.huaxia.inventory.web.WebServerService;

import java.util.List;

/** Web 服务开关 + 地址展示。 */
public class WebServerDialog extends DialogFragment {

    private SwitchMaterial switchServer;
    private TextView tvAddress;

    public static WebServerDialog newInstance() {
        return new WebServerDialog();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_web_server, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        switchServer = view.findViewById(R.id.switchWebServer);
        tvAddress = view.findViewById(R.id.tvWebServerAddress);
        MaterialButton close = view.findViewById(R.id.btnWebServerClose);

        switchServer.setChecked(WebServerService.isRunning());
        render();

        switchServer.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                requireContext().startForegroundService(
                        WebServerService.buildStartIntent(requireContext()));
            } else {
                requireContext().startService(
                        WebServerService.buildStopIntent(requireContext()));
            }
            // 服务启停是异步的，稍后刷新一次状态
            btn.postDelayed(this::render, 600);
        });

        close.setOnClickListener(v -> dismiss());
    }

    private void render() {
        boolean on = WebServerService.isRunning();
        switchServer.setChecked(on);
        switchServer.setText(on ? R.string.web_server_toggle_on : R.string.web_server_toggle_off);

        List<String> ips = WebServerService.localIpv4Addresses();
        if (ips.isEmpty()) {
            tvAddress.setText(R.string.web_server_no_network);
            return;
        }
        int port = WebServerService.activePort();
        String addr = ips.get(0) + ":" + (port > 0 ? port : 8080);
        tvAddress.setText(getString(R.string.web_server_address_fmt, addr));
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                window.setGravity(android.view.Gravity.CENTER);
                window.setBackgroundDrawableResource(android.R.color.transparent);
            }
        }
    }
}
```

- [ ] **Step 9: 挂到设置菜单**

在 `app/src/main/java/com/huaxia/inventory/MainActivity.java` 的 popup 点击分发里
（现有 `if (id == R.id.menu_action_export_assets) { ... }` 分支之后）追加：

```java
            if (id == R.id.menu_action_web_server) {
                WebServerDialog.newInstance()
                        .show(getSupportFragmentManager(), "web_server");
                return true;
            }
```

并补 import：`import com.huaxia.inventory.ui.setting.WebServerDialog;`

- [ ] **Step 10: 跑测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.web.WebServerServiceTest"`
Expected: 4 个用例 PASS

- [ ] **Step 11: 跑全量测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿

- [ ] **Step 12: 真机冒烟**

```bash
./gradlew :app:installDebug
adb shell am start -n com.assetmanager.inventory/com.huaxia.inventory.MainActivity
# 打开齿轮菜单 → Web 服务 → 开启，记下对话框里的地址
curl http://<PDA-IP>:8080/api/ping
```
Expected: `{"ok":true}`

- [ ] **Step 13: 提交**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/menu/popup_settings.xml \
        app/src/main/res/values/strings.xml app/src/main/res/layout/dialog_web_server.xml \
        app/src/main/java/com/huaxia/inventory/web/WebServerService.java \
        app/src/main/java/com/huaxia/inventory/ui/setting/WebServerDialog.java \
        app/src/main/java/com/huaxia/inventory/MainActivity.java \
        app/src/test/java/com/huaxia/inventory/web/WebServerServiceTest.java
git commit -m "feat(web): 前台服务 + 设置页开关与地址展示"
```

---

## Task 4: 大屏 API（含两个 DAO 聚合方法）

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/data/dao/AssetDao.java`
- Modify: `app/src/main/java/com/huaxia/inventory/data/dao/InventoryTaskDao.java`
- Create: `app/src/main/java/com/huaxia/inventory/web/api/DashboardApi.java`
- Modify: `app/src/main/java/com/huaxia/inventory/web/WebApiRegistry.java`
- Test: `app/src/test/java/com/huaxia/inventory/web/api/DashboardApiTest.java`

**Interfaces:**
- Consumes: `WebRouter` / `WebResponse` / `WebRequest`（Task 2）、`TaskService`（Task 1）
- Produces:
  - `AssetDao.countByDeptGrouped()` → `List<CategoryCount>`
  - `AssetDao.countByCategoryAll()` → `List<CategoryCount>`
  - `InventoryTaskDao.findAll()` → `List<InventoryTask>`
  - `DashboardApi(AppDatabase db, InventorySession session)`、`WebResponse handle(WebRequest)`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/huaxia/inventory/web/api/DashboardApiTest.java`：

```java
package com.huaxia.inventory.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.session.InventorySession;
import com.huaxia.inventory.web.WebRequest;
import com.huaxia.inventory.web.WebResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class DashboardApiTest {

    private AppDatabase db;
    private DashboardApi api;
    private final ObjectMapper mapper = new ObjectMapper();

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries().build();
        api = new DashboardApi(db, new InventorySession());
    }

    @After
    public void tearDown() { db.close(); }

    private void seed(String epc, String dept, String type, long taskId) {
        Asset a = new Asset();
        a.taskId = taskId;
        a.excelRowIndex = 1;
        a.epc = epc;
        a.dept = dept;
        a.assetType = type;
        db.assetDao().upsertOne(a);
    }

    private JsonNode get() throws Exception {
        WebResponse res = api.handle(new WebRequest("GET", "/api/dashboard",
                Collections.emptyMap(), Collections.emptyMap(), new byte[0]));
        assertThat(res.status).isEqualTo(200);
        return mapper.readTree(res.bytes);
    }

    @Test
    public void emptyDatabase_returnsZerosAndNullActiveTask() throws Exception {
        JsonNode root = get();
        assertThat(root.at("/ledger/total").asInt()).isZero();
        assertThat(root.at("/deptDistribution").size()).isZero();
        assertThat(root.at("/activeTask").isNull()).isTrue();
    }

    @Test
    public void ledgerCountsIncludeAssignedAssets() throws Exception {
        seed("1", "行政部", "笔记本", 0L);
        seed("2", "财务部", "台式机", 7L);   // 已划拨到任务 7

        JsonNode root = get();

        assertThat(root.at("/ledger/total").asInt()).isEqualTo(2);
        assertThat(root.at("/ledger/deptCount").asInt()).isEqualTo(2);
        assertThat(root.at("/ledger/typeCount").asInt()).isEqualTo(2);
    }

    @Test
    public void distribution_mergesTailIntoOther() throws Exception {
        for (int i = 0; i < 12; i++) {
            seed("e" + i, "部门" + i, "类型" + i, 0L);
        }

        JsonNode root = get();
        JsonNode dept = root.at("/deptDistribution");

        assertThat(dept.size()).isEqualTo(11);   // Top10 + 「其他」
        assertThat(dept.get(10).get("name").asText()).isEqualTo("其他");
        assertThat(dept.get(10).get("count").asInt()).isEqualTo(2);
    }

    @Test
    public void blankDeptBecomesExplicitBucket() throws Exception {
        seed("1", "", "笔记本", 0L);
        seed("2", null, "笔记本", 0L);

        JsonNode dept = get().at("/deptDistribution");

        assertThat(dept.size()).isEqualTo(1);
        assertThat(dept.get(0).get("name").asText()).isEqualTo("未填写");
        assertThat(dept.get(0).get("count").asInt()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.web.api.DashboardApiTest"`
Expected: 编译失败 — `DashboardApi` 不存在

- [ ] **Step 3: 加 DAO 方法**

在 `app/src/main/java/com/huaxia/inventory/data/dao/AssetDao.java` 的 `countByCategory(long taskId)` 之后插入：

```java
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
```

在 `app/src/main/java/com/huaxia/inventory/data/dao/InventoryTaskDao.java` 的 `findAllLive()` 之后插入：

```java
    /** 同步版任务列表（Web 层用；UI 走 {@link #findAllLive()}）。 */
    @Query("SELECT * FROM InventoryTask ORDER BY importedAt DESC")
    List<InventoryTask> findAll();
```

- [ ] **Step 4: 实现 DashboardApi**

`app/src/main/java/com/huaxia/inventory/web/api/DashboardApi.java`：

```java
package com.huaxia.inventory.web.api;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.dao.CategoryCount;
import com.huaxia.inventory.data.dao.ScanAggregate;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.session.InventorySession;
import com.huaxia.inventory.web.WebRequest;
import com.huaxia.inventory.web.WebResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大屏看板聚合接口。
 *
 * <p>一次请求返回全部数据；服务端做 1 秒快照合并 —— 多个浏览器标签同时 5 秒轮询时
 * 不会把 PDA 打成 N 倍负载。
 */
public class DashboardApi {

    private static final int TOP_N = 10;
    private static final long CACHE_TTL_MS = 1000L;

    private final AppDatabase db;
    private final InventorySession session;

    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    public DashboardApi(AppDatabase db, InventorySession session) {
        this.db = db;
        this.session = session;
    }

    public WebResponse handle(WebRequest req) {
        long now = System.currentTimeMillis();
        Map<String, Object> snapshot = cached;
        if (snapshot != null && now - cachedAt < CACHE_TTL_MS) {
            return WebResponse.ok(snapshot);
        }
        synchronized (this) {
            if (cached != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
                return WebResponse.ok(cached);
            }
            Map<String, Object> fresh = build();
            cached = fresh;
            cachedAt = System.currentTimeMillis();
            return WebResponse.ok(fresh);
        }
    }

    private Map<String, Object> build() {
        List<CategoryCount> deptGroups = db.assetDao().countByDeptGrouped();
        List<CategoryCount> typeGroups = db.assetDao().countByCategoryAll();

        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("total", db.assetDao().countAll());
        ledger.put("deptCount", deptGroups.size());
        ledger.put("typeCount", typeGroups.size());

        List<InventoryTask> tasks = db.inventoryTaskDao().findAll();
        Map<String, Object> taskCounts = new LinkedHashMap<>();
        taskCounts.put("inProgress", countByStatus(tasks, "IN_PROGRESS"));
        taskCounts.put("paused", countByStatus(tasks, "PAUSED"));
        taskCounts.put("completed", countByStatus(tasks, "COMPLETED"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ledger", ledger);
        result.put("taskCounts", taskCounts);
        result.put("activeTask", activeTask(tasks));
        result.put("deptDistribution", topN(deptGroups, "未填写"));
        result.put("typeDistribution", topN(typeGroups, "未分类"));
        result.put("updatedAt", System.currentTimeMillis());
        return result;
    }

    private Map<String, Object> activeTask(List<InventoryTask> tasks) {
        Long currentId = session.getCurrentTaskId();
        if (currentId == null) return null;
        for (InventoryTask t : tasks) {
            if (t.id != currentId) continue;
            ScanAggregate agg = db.scanRecordDao().aggregateStats(t.id);
            int scanned = agg.matchedCount;
            int extra = agg.extraCount;
            int missing = Math.max(0, t.expectedCount - scanned);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id);
            m.put("name", t.sourceFileName);
            m.put("dept", t.dept);
            m.put("expected", t.expectedCount);
            m.put("scanned", scanned);
            m.put("missing", missing);
            m.put("extra", extra);
            m.put("progress", t.expectedCount == 0 ? 0.0
                    : Math.min(1.0, (double) scanned / t.expectedCount));
            m.put("lastScannedAt", agg.lastScannedAt);
            return m;
        }
        return null;
    }

    /**
     * Top N + 「其他」合并。空字符串桶映射成调用方给的中文名。
     */
    private static List<Map<String, Object>> topN(List<CategoryCount> groups, String blankLabel) {
        List<Map<String, Object>> out = new ArrayList<>();
        long otherCount = 0;
        for (int i = 0; i < groups.size(); i++) {
            CategoryCount g = groups.get(i);
            if (i < TOP_N) {
                Map<String, Object> m = new LinkedHashMap<>();
                String name = g.category == null || g.category.isEmpty() ? blankLabel : g.category;
                m.put("name", name);
                m.put("count", g.count);
                out.add(m);
            } else {
                otherCount += g.count;
            }
        }
        if (otherCount > 0) {
            Map<String, Object> other = new LinkedHashMap<>();
            other.put("name", "其他");
            other.put("count", otherCount);
            out.add(other);
        }
        return out;
    }

    private static int countByStatus(List<InventoryTask> tasks, String status) {
        int n = 0;
        for (InventoryTask t : tasks) {
            if (status.equals(t.status)) n++;
        }
        return n;
    }
}
```

- [ ] **Step 5: 挂路由**

`WebApiRegistry.register` 内追加：

```java
        DashboardApi dashboard = new DashboardApi(db, session);
        router.get("/api/dashboard", dashboard::handle);
```

并补 import：`import com.huaxia.inventory.web.api.DashboardApi;`

- [ ] **Step 6: 跑测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.web.api.DashboardApiTest"`
Expected: 4 个用例 PASS

- [ ] **Step 7: 跑全量测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/huaxia/inventory/data/dao/AssetDao.java \
        app/src/main/java/com/huaxia/inventory/data/dao/InventoryTaskDao.java \
        app/src/main/java/com/huaxia/inventory/web/api/DashboardApi.java \
        app/src/main/java/com/huaxia/inventory/web/WebApiRegistry.java \
        app/src/test/java/com/huaxia/inventory/web/api/DashboardApiTest.java
git commit -m "feat(web): 大屏聚合接口 + 全量台账分组查询"
```

---

## Task 5: 任务读写 API

**Files:**
- Create: `app/src/main/java/com/huaxia/inventory/web/api/TaskApi.java`
- Modify: `app/src/main/java/com/huaxia/inventory/web/WebApiRegistry.java`
- Test: `app/src/test/java/com/huaxia/inventory/web/api/TaskApiTest.java`

**Interfaces:**
- Consumes: `TaskService`（Task 1）、`WebJson`（Task 2）、`InventoryTaskDao.findAll()`（Task 4）
- Produces: `TaskApi(AppDatabase db, TaskService svc, InventorySession session)`，方法
  `list/detail/depts/create/pause/resume/complete/delete/clearCompleted`，签名均为 `WebResponse xxx(WebRequest) throws ApiException`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/huaxia/inventory/web/api/TaskApiTest.java`：

```java
package com.huaxia.inventory.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.service.TaskService;
import com.huaxia.inventory.session.InventorySession;
import com.huaxia.inventory.web.ApiException;
import com.huaxia.inventory.web.WebRequest;
import com.huaxia.inventory.web.WebResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class TaskApiTest {

    private AppDatabase db;
    private InventorySession session;
    private TaskApi api;
    private final ObjectMapper mapper = new ObjectMapper();

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries().build();
        session = new InventorySession();
        api = new TaskApi(db, new TaskService(db, session), session);
    }

    @After
    public void tearDown() { db.close(); }

    private static WebRequest req(String method, String path, String json) {
        return new WebRequest(method, path, Collections.emptyMap(),
                Collections.emptyMap(), json.getBytes(StandardCharsets.UTF_8));
    }

    private static WebRequest withId(WebRequest base, long id) {
        return base.withPathParams(Collections.singletonMap("id", String.valueOf(id)));
    }

    private void seed(String epc, String dept) {
        Asset a = new Asset();
        a.taskId = 0L;
        a.excelRowIndex = 1;
        a.epc = epc;
        a.dept = dept;
        db.assetDao().upsertOne(a);
    }

    private long createTask(String json) throws Exception {
        return mapper.readTree(api.create(req("POST", "/api/tasks", json)).bytes)
                .get("id").asLong();
    }

    @Test
    public void create_returnsTaskIdAndAssignsAssets() throws Exception {
        seed("1001", "行政部");

        WebResponse res = api.create(req("POST", "/api/tasks",
                "{\"name\":\"Q4 盘点\",\"dept\":\"行政部\",\"remark\":\"备注\"}"));

        assertThat(res.status).isEqualTo(200);
        JsonNode root = mapper.readTree(res.bytes);
        long id = root.get("id").asLong();
        assertThat(id).isPositive();
        assertThat(root.get("assigned").asInt()).isEqualTo(1);
        assertThat(db.inventoryTaskDao().findById(id).remark).isEqualTo("备注");
    }

    @Test
    public void create_rejectsBlankName() throws Exception {
        try {
            api.create(req("POST", "/api/tasks", "{\"name\":\"  \"}"));
            org.junit.Assert.fail("expected ApiException");
        } catch (ApiException e) {
            assertThat(e.code).isEqualTo("BAD_REQUEST");
        }
    }

    @Test
    public void resume_setsSessionAndReportsChange() throws Exception {
        seed("1001", "行政部");
        long id = createTask("{\"name\":\"T\"}");
        api.pause(withId(req("POST", "/api/tasks/x/pause", ""), id));

        JsonNode root = mapper.readTree(
                api.resume(withId(req("POST", "/api/tasks/x/resume", ""), id)).bytes);

        assertThat(root.get("sessionChanged").asBoolean()).isTrue();
        assertThat(session.getCurrentTaskId()).isEqualTo(id);
    }

    @Test
    public void delete_clearsSessionWhenDeletingCurrentTask() throws Exception {
        seed("1001", "行政部");
        long id = createTask("{\"name\":\"T\"}");
        session.setCurrentTaskId(id);

        api.delete(withId(req("DELETE", "/api/tasks/x", ""), id));

        assertThat(session.getCurrentTaskId()).isNull();
        assertThat(db.inventoryTaskDao().findById(id)).isNull();
        assertThat(db.assetDao().countPool()).isEqualTo(1);
    }

    @Test
    public void list_includesProgressFields() throws Exception {
        seed("1001", "行政部");
        createTask("{\"name\":\"T\"}");

        JsonNode items = mapper.readTree(
                api.list(req("GET", "/api/tasks", "")).bytes).get("items");

        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).get("expected").asInt()).isEqualTo(1);
        assertThat(items.get(0).get("scanned").asInt()).isZero();
        assertThat(items.get(0).get("status").asText()).isEqualTo("IN_PROGRESS");
    }

    @Test
    public void detail_returnsNotFoundForMissingTask() throws Exception {
        try {
            api.detail(withId(req("GET", "/api/tasks/x", ""), 999L));
            org.junit.Assert.fail("expected ApiException");
        } catch (ApiException e) {
            assertThat(e.status).isEqualTo(404);
            assertThat(e.code).isEqualTo("TASK_NOT_FOUND");
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.web.api.TaskApiTest"`
Expected: 编译失败 — `TaskApi` 不存在

- [ ] **Step 3: 实现 TaskApi**

`app/src/main/java/com/huaxia/inventory/web/api/TaskApi.java`：

```java
package com.huaxia.inventory.web.api;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.dao.ScanAggregate;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.service.TaskService;
import com.huaxia.inventory.session.InventorySession;
import com.huaxia.inventory.web.ApiException;
import com.huaxia.inventory.web.WebJson;
import com.huaxia.inventory.web.WebRequest;
import com.huaxia.inventory.web.WebResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 盘点任务读写接口 —— 全部委托 {@link TaskService}，不复制事务逻辑。 */
public class TaskApi {

    /** 请求体 DTO。字段均可为 null。 */
    public static class CreateTaskBody {
        public String name;
        public String dept;
        public String remark;
    }

    private final AppDatabase db;
    private final TaskService taskService;
    private final InventorySession session;

    public TaskApi(AppDatabase db, TaskService taskService, InventorySession session) {
        this.db = db;
        this.taskService = taskService;
        this.session = session;
    }

    public WebResponse list(WebRequest req) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (InventoryTask t : db.inventoryTaskDao().findAll()) {
            items.add(summary(t));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", items.size());
        return WebResponse.ok(body);
    }

    public WebResponse detail(WebRequest req) throws ApiException {
        InventoryTask t = requireTask(req);
        Map<String, Object> body = summary(t);
        body.put("remark", t.remark);
        return WebResponse.ok(body);
    }

    public WebResponse depts(WebRequest req) {
        return WebResponse.ok(taskService.distinctPoolDepts());
    }

    public WebResponse create(WebRequest req) throws ApiException {
        CreateTaskBody body = parseBody(req);
        if (body.name == null || body.name.trim().isEmpty()) {
            throw ApiException.badRequest("任务名称不能为空");
        }
        Long previous = session.getCurrentTaskId();
        long id = taskService.createTask(body.name.trim(), body.dept, body.remark);
        InventoryTask created = db.inventoryTaskDao().findById(id);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("assigned", created == null ? 0 : created.expectedCount);
        out.put("sessionChanged", !Objects.equals(previous, session.getCurrentTaskId()));
        out.put("previousTaskId", previous);
        return WebResponse.ok(out);
    }

    public WebResponse pause(WebRequest req) throws ApiException {
        InventoryTask t = requireTask(req);
        Long previous = session.getCurrentTaskId();
        taskService.pauseTask(t.id);
        return WebResponse.ok(changeResultBody(previous));
    }

    public WebResponse resume(WebRequest req) throws ApiException {
        InventoryTask t = requireTask(req);
        Long previous = session.getCurrentTaskId();
        taskService.resumeTask(t.id);
        return WebResponse.ok(changeResultBody(previous));
    }

    public WebResponse complete(WebRequest req) throws ApiException {
        InventoryTask t = requireTask(req);
        Long previous = session.getCurrentTaskId();
        taskService.completeTask(t.id);
        return WebResponse.ok(changeResultBody(previous));
    }

    public WebResponse delete(WebRequest req) throws ApiException {
        InventoryTask t = requireTask(req);
        Long previous = session.getCurrentTaskId();
        int released = taskService.deleteTask(t.id);
        Map<String, Object> out = changeResultBody(previous);
        out.put("released", released);
        return WebResponse.ok(out);
    }

    public WebResponse clearCompleted(WebRequest req) {
        Long previous = session.getCurrentTaskId();
        taskService.clearCompleted();
        return WebResponse.ok(changeResultBody(previous));
    }

    private Map<String, Object> changeResultBody(Long previous) {
        Long now = session.getCurrentTaskId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sessionChanged", !Objects.equals(previous, now));
        out.put("previousTaskId", previous);
        out.put("currentTaskId", now);
        return out;
    }

    private InventoryTask requireTask(WebRequest req) throws ApiException {
        long id = req.pathParamLong("id");
        InventoryTask t = db.inventoryTaskDao().findById(id);
        if (t == null) throw ApiException.notFound("TASK_NOT_FOUND", "任务不存在");
        return t;
    }

    private Map<String, Object> summary(InventoryTask t) {
        ScanAggregate agg = db.scanRecordDao().aggregateStats(t.id);
        int scanned = agg.matchedCount;
        int missing = Math.max(0, t.expectedCount - scanned);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id);
        m.put("name", t.sourceFileName);
        m.put("dept", t.dept);
        m.put("status", t.status);
        m.put("expected", t.expectedCount);
        m.put("scanned", scanned);
        m.put("missing", missing);
        m.put("extra", agg.extraCount);
        m.put("progress", t.expectedCount == 0 ? 0.0
                : Math.min(1.0, (double) scanned / t.expectedCount));
        m.put("lastScannedAt", agg.lastScannedAt);
        m.put("importedAt", t.importedAt);
        return m;
    }

    private static CreateTaskBody parseBody(WebRequest req) throws ApiException {
        String raw = req.bodyAsString();
        if (raw == null || raw.trim().isEmpty()) {
            throw ApiException.badRequest("请求体不能为空");
        }
        try {
            return WebJson.read(raw, CreateTaskBody.class);
        } catch (Exception e) {
            throw ApiException.badRequest("请求体不是合法 JSON");
        }
    }
}
```

- [ ] **Step 4: 挂路由**

`WebApiRegistry.register` 内追加：

```java
        TaskApi tasks = new TaskApi(db, new TaskService(db, session), session);
        router.get("/api/tasks/depts", tasks::depts);
        router.delete("/api/tasks/completed", tasks::clearCompleted);
        router.get("/api/tasks", tasks::list);
        router.post("/api/tasks", tasks::create);
        router.get("/api/tasks/{id}", tasks::detail);
        router.post("/api/tasks/{id}/pause", tasks::pause);
        router.post("/api/tasks/{id}/resume", tasks::resume);
        router.post("/api/tasks/{id}/complete", tasks::complete);
        router.delete("/api/tasks/{id}", tasks::delete);
```

**注册顺序**：`/api/tasks/depts` 与 `/api/tasks/completed` 必须排在 `/api/tasks/{id}` 之前 ——
`{id}` 会先匹配到 `depts` 并在 `Long.parseLong` 失败后返回 400。

并补 import：`import com.huaxia.inventory.service.TaskService;`、`import com.huaxia.inventory.web.api.TaskApi;`

- [ ] **Step 5: 跑测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.web.api.TaskApiTest"`
Expected: 6 个用例 PASS

- [ ] **Step 6: 跑全量测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/huaxia/inventory/web/api/TaskApi.java \
        app/src/main/java/com/huaxia/inventory/web/WebApiRegistry.java \
        app/src/test/java/com/huaxia/inventory/web/api/TaskApiTest.java
git commit -m "feat(web): 盘点任务读写接口"
```

---

## Task 6: 资产与结果只读 API + 关键词转义提取

**Files:**
- Create: `app/src/main/java/com/huaxia/inventory/util/SqlLikeEscaper.java`
- Create: `app/src/main/java/com/huaxia/inventory/web/api/AssetApi.java`
- Modify: `app/src/main/java/com/huaxia/inventory/ui/asset/AssetOverviewViewModel.java`
- Modify: `app/src/main/java/com/huaxia/inventory/web/WebApiRegistry.java`
- Test: `app/src/test/java/com/huaxia/inventory/util/SqlLikeEscaperTest.java`
- Test: `app/src/test/java/com/huaxia/inventory/web/api/AssetApiTest.java`

**Interfaces:**
- Consumes: `AssetDao.pageFiltered/countFiltered/pageAll/pageFound/pageMissing/countByTask/countFound/countMissing`、`ScanRecordDao.pageExtra/countExtras`、`AssetDao.countByDeptGrouped/countByCategoryAll`（Task 4）
- Produces:
  - `SqlLikeEscaper.likePattern(String keyword)` → `%kw%`（空关键词返回 `"%"`）
  - `AssetApi(AppDatabase db)`，方法 `list/filters/results`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/huaxia/inventory/util/SqlLikeEscaperTest.java`：

```java
package com.huaxia.inventory.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class SqlLikeEscaperTest {

    @Test
    public void emptyKeywordBecomesMatchAll() {
        assertThat(SqlLikeEscaper.likePattern(null)).isEqualTo("%");
        assertThat(SqlLikeEscaper.likePattern("")).isEqualTo("%");
    }

    @Test
    public void plainKeywordIsWrapped() {
        assertThat(SqlLikeEscaper.likePattern("笔记本")).isEqualTo("%笔记本%");
    }

    @Test
    public void wildcardsAreEscaped() {
        assertThat(SqlLikeEscaper.likePattern("50%")).isEqualTo("%50!%%");
        assertThat(SqlLikeEscaper.likePattern("a_b")).isEqualTo("%a!_b%");
        assertThat(SqlLikeEscaper.likePattern("!x")).isEqualTo("%!!x%");
    }
}
```

`app/src/test/java/com/huaxia/inventory/web/api/AssetApiTest.java`：

```java
package com.huaxia.inventory.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.web.WebRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public class AssetApiTest {

    private AppDatabase db;
    private AssetApi api;
    private final ObjectMapper mapper = new ObjectMapper();

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries().build();
        api = new AssetApi(db);
    }

    @After
    public void tearDown() { db.close(); }

    private void seed(String epc, String dept, String type, long taskId) {
        Asset a = new Asset();
        a.taskId = taskId;
        a.excelRowIndex = 1;
        a.epc = epc;
        a.dept = dept;
        a.assetType = type;
        a.custodian = "张三";
        db.assetDao().upsertOne(a);
    }

    private static WebRequest req(String path, Map<String, String> query) {
        return new WebRequest("GET", path, query, Collections.emptyMap(), new byte[0]);
    }

    @Test
    public void list_returnsPagedEnvelope() throws Exception {
        for (int i = 0; i < 25; i++) seed("e" + i, "行政部", "笔记本", 0L);

        Map<String, String> q = new HashMap<>();
        q.put("page", "2");
        q.put("pageSize", "10");
        JsonNode root = mapper.readTree(api.list(req("/api/assets", q)).bytes);

        assertThat(root.get("total").asInt()).isEqualTo(25);
        assertThat(root.get("totalPages").asInt()).isEqualTo(3);
        assertThat(root.get("items").size()).isEqualTo(10);
    }

    @Test
    public void list_filtersByKeyword() throws Exception {
        seed("1001", "行政部", "笔记本", 0L);
        seed("2002", "财务部", "台式机", 0L);

        Map<String, String> q = new HashMap<>();
        q.put("keyword", "财务");
        JsonNode root = mapper.readTree(api.list(req("/api/assets", q)).bytes);

        assertThat(root.get("total").asInt()).isEqualTo(1);
        assertThat(root.at("/items/0/dept").asText()).isEqualTo("财务部");
    }

    @Test
    public void filters_exposeDeptAndTypeOptions() throws Exception {
        seed("1001", "行政部", "笔记本", 0L);
        seed("2002", "行政部", "台式机", 0L);

        JsonNode root = mapper.readTree(
                api.filters(req("/api/assets/filters", Collections.emptyMap())).bytes);

        assertThat(root.get("depts").size()).isEqualTo(1);
        assertThat(root.at("/depts/0/name").asText()).isEqualTo("行政部");
        assertThat(root.at("/depts/0/count").asInt()).isEqualTo(2);
    }

    @Test
    public void results_returnsTaskScopedPage() throws Exception {
        seed("1001", "行政部", "笔记本", 5L);
        seed("2002", "行政部", "笔记本", 5L);

        Map<String, String> q = new HashMap<>();
        q.put("tab", "MISSING");
        WebRequest request = req("/api/tasks/5/results", q)
                .withPathParams(Collections.singletonMap("id", "5"));
        JsonNode root = mapper.readTree(api.results(request).bytes);

        assertThat(root.get("total").asInt()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.util.SqlLikeEscaperTest" --tests "com.huaxia.inventory.web.api.AssetApiTest"`
Expected: 编译失败

- [ ] **Step 3: 实现 SqlLikeEscaper**

`app/src/main/java/com/huaxia/inventory/util/SqlLikeEscaper.java`：

```java
package com.huaxia.inventory.util;

import androidx.annotation.Nullable;

/**
 * SQL LIKE 关键词转义 —— App 与 Web 共用，避免两套转义漂移。
 *
 * <p>{@code AssetDao.KEYWORD_WHERE} 使用 {@code ESCAPE '!'}，因此 {@code ! % _}
 * 三个字符必须转义成 {@code !! !% !_}。
 */
public final class SqlLikeEscaper {

    private SqlLikeEscaper() {}

    /** 空关键词返回 "%"（匹配全部），否则返回 "%转义后关键词%"。 */
    public static String likePattern(@Nullable String keyword) {
        if (keyword == null || keyword.isEmpty()) return "%";
        String escaped = keyword.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }
}
```

- [ ] **Step 4: 让 AssetOverviewViewModel 复用它**

在 `app/src/main/java/com/huaxia/inventory/ui/asset/AssetOverviewViewModel.java` 中把
`private static String likePattern(...)` 的方法体替换为一行委托：

```java
    private static String likePattern(@Nullable String keyword) {
        return com.huaxia.inventory.util.SqlLikeEscaper.likePattern(keyword);
    }
```

保留方法签名与全部调用点不动。

- [ ] **Step 5: 实现 AssetApi**

`app/src/main/java/com/huaxia/inventory/web/api/AssetApi.java`：

```java
package com.huaxia.inventory.web.api;

import androidx.annotation.Nullable;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.dao.CategoryCount;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.util.EpcNormalizer;
import com.huaxia.inventory.util.SqlLikeEscaper;
import com.huaxia.inventory.web.ApiException;
import com.huaxia.inventory.web.WebRequest;
import com.huaxia.inventory.web.WebResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 资产台账与任务结果的只读查询。 */
public class AssetApi {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final AppDatabase db;

    public AssetApi(AppDatabase db) {
        this.db = db;
    }

    public WebResponse list(WebRequest req) {
        String pattern = SqlLikeEscaper.likePattern(req.query("keyword"));
        String dept = emptyToNull(req.query("dept"));
        String type = emptyToNull(req.query("type"));
        int page = Math.max(1, req.queryInt("page", 1));
        int size = clampSize(req.queryInt("pageSize", DEFAULT_PAGE_SIZE));

        int total = db.assetDao().countFiltered(pattern, dept, type);
        List<Asset> rows = db.assetDao().pageFiltered(pattern, dept, type,
                size, (page - 1) * size);

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (Asset a : rows) items.add(toDto(a));
        return WebResponse.ok(envelope(items, page, size, total));
    }

    public WebResponse filters(WebRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("depts", groups(db.assetDao().countByDeptGrouped(), "未填写"));
        body.put("types", groups(db.assetDao().countByCategoryAll(), "未分类"));
        return WebResponse.ok(body);
    }

    public WebResponse results(WebRequest req) throws ApiException {
        long taskId = req.pathParamLong("id");
        if (db.inventoryTaskDao().findById(taskId) == null) {
            throw ApiException.notFound("TASK_NOT_FOUND", "任务不存在");
        }
        String tab = req.query("tab") == null
                ? "ALL" : req.query("tab").toUpperCase(Locale.ROOT);
        int page = Math.max(1, req.queryInt("page", 1));
        int size = clampSize(req.queryInt("pageSize", DEFAULT_PAGE_SIZE));
        int offset = (page - 1) * size;

        List<Map<String, Object>> items = new ArrayList<>();
        int total;
        switch (tab) {
            case "FOUND":
                total = db.assetDao().countFound(taskId);
                for (Asset a : db.assetDao().pageFound(taskId, size, offset)) {
                    items.add(toDto(a));
                }
                break;
            case "MISSING":
                total = db.assetDao().countMissing(taskId);
                for (Asset a : db.assetDao().pageMissing(taskId, size, offset)) {
                    items.add(toDto(a));
                }
                break;
            case "EXTRA":
                total = db.scanRecordDao().countExtras(taskId);
                for (ScanRecord s : db.scanRecordDao().pageExtra(taskId, size, offset)) {
                    items.add(extraDto(s));
                }
                break;
            case "ALL":
            default:
                total = db.assetDao().countByTask(taskId);
                for (Asset a : db.assetDao().pageAll(taskId, size, offset)) {
                    items.add(toDto(a));
                }
                break;
        }
        return WebResponse.ok(envelope(items, page, size, total));
    }

    private static Map<String, Object> envelope(List<Map<String, Object>> items,
                                                int page, int size, int total) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", page);
        body.put("pageSize", size);
        body.put("total", total);
        body.put("totalPages", size == 0 ? 0 : (total + size - 1) / size);
        return body;
    }

    private static Map<String, Object> toDto(Asset a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id);
        m.put("epc", displayEpc(a.epc));
        m.put("assetType", a.assetType);
        m.put("assetModel", a.assetModel);
        m.put("serialNumber", a.serialNumber);
        m.put("dept", a.dept);
        m.put("custodian", a.custodian);
        m.put("inventoryTime", a.inventoryTime);
        return m;
    }

    private static Map<String, Object> extraDto(ScanRecord s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id);
        m.put("epc", displayEpc(s.epc));
        m.put("scannedAt", s.scannedAt);
        m.put("rssi", s.rssi);
        return m;
    }

    private static List<Map<String, Object>> groups(List<CategoryCount> src, String blankLabel) {
        List<Map<String, Object>> out = new ArrayList<>(src.size());
        for (CategoryCount g : src) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", g.category == null || g.category.isEmpty() ? blankLabel : g.category);
            m.put("count", g.count);
            out.add(m);
        }
        return out;
    }

    private static String displayEpc(@Nullable String epc) {
        String display = EpcNormalizer.toDisplayForm(epc);
        return display == null ? "" : display;
    }

    @Nullable
    private static String emptyToNull(@Nullable String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static int clampSize(int size) {
        if (size <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
```

- [ ] **Step 6: 挂路由**

`WebApiRegistry.register` 内追加（**必须在 `/api/tasks/{id}` 之前**）：

```java
        AssetApi assets = new AssetApi(db);
        router.get("/api/assets", assets::list);
        router.get("/api/assets/filters", assets::filters);
        router.get("/api/tasks/{id}/results", assets::results);
```

并补 import：`import com.huaxia.inventory.web.api.AssetApi;`

- [ ] **Step 7: 跑测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.util.SqlLikeEscaperTest" --tests "com.huaxia.inventory.web.api.AssetApiTest"`
Expected: `SqlLikeEscaperTest` 3 个 + `AssetApiTest` 4 个 PASS

- [ ] **Step 8: 跑全量测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿（`AssetOverviewViewModelTest` 必须仍通过）

- [ ] **Step 9: 提交**

```bash
git add app/src/main/java/com/huaxia/inventory/util/SqlLikeEscaper.java \
        app/src/main/java/com/huaxia/inventory/web/api/AssetApi.java \
        app/src/main/java/com/huaxia/inventory/ui/asset/AssetOverviewViewModel.java \
        app/src/main/java/com/huaxia/inventory/web/WebApiRegistry.java \
        app/src/test/java/com/huaxia/inventory/util/SqlLikeEscaperTest.java \
        app/src/test/java/com/huaxia/inventory/web/api/AssetApiTest.java
git commit -m "feat(web): 资产台账与任务结果只读接口 + 关键词转义提取共用"
```

---

## Task 7: Excel 导入 API

**Files:**
- Create: `app/src/main/java/com/huaxia/inventory/web/api/ImportApi.java`
- Modify: `app/src/main/java/com/huaxia/inventory/web/WebApiRegistry.java`
- Test: `app/src/test/java/com/huaxia/inventory/web/api/ImportApiTest.java`

**Interfaces:**
- Consumes: `ExcelImporter.stream(InputStream, long, int, BatchHandler)`、`AssetDao.findExistingEpcs/upsertBatch`、`AppDatabase.runInTransaction`
- Produces: `ImportApi(AppDatabase db, File cacheDir)`，方法 `WebResponse upload(WebRequest) throws ApiException`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/huaxia/inventory/web/api/ImportApiTest.java`：

```java
package com.huaxia.inventory.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.web.ApiException;
import com.huaxia.inventory.web.WebRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public class ImportApiTest {

    private AppDatabase db;
    private ImportApi api;
    private final ObjectMapper mapper = new ObjectMapper();

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries().build();
        File cache = new File(ApplicationProvider.getApplicationContext().getCacheDir(), "web-upload");
        api = new ImportApi(db, cache);
    }

    @After
    public void tearDown() { db.close(); }

    private byte[] templateBytes() throws Exception {
        try (InputStream in = ApplicationProvider.getApplicationContext()
                .getAssets().open("import_template.xlsx")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private WebRequest upload(byte[] body) {
        Map<String, String> q = new HashMap<>();
        q.put("filename", "assets.xlsx");
        return new WebRequest("POST", "/api/import", q, Collections.emptyMap(), body);
    }

    @Test
    public void upload_rejectsEmptyBody() throws Exception {
        try {
            api.upload(upload(new byte[0]));
            org.junit.Assert.fail("expected ApiException");
        } catch (ApiException e) {
            assertThat(e.code).isEqualTo("BAD_REQUEST");
        }
    }

    @Test
    public void upload_rejectsNonXlsxBytes() throws Exception {
        try {
            api.upload(upload("not an excel file".getBytes("UTF-8")));
            org.junit.Assert.fail("expected ApiException");
        } catch (ApiException e) {
            assertThat(e.code).isEqualTo("IMPORT_PARSE_FAILED");
        }
    }

    @Test
    public void upload_cleansUpTempFile() throws Exception {
        File dir = new File(ApplicationProvider.getApplicationContext().getCacheDir(), "web-upload");
        try {
            api.upload(upload(templateBytes()));
        } catch (ApiException ignored) {
            // 模板文件可能没有数据行，失败也可接受 —— 本用例只校验临时文件清理
        }
        File[] left = dir.listFiles();
        assertThat(left == null ? 0 : left.length).isZero();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.web.api.ImportApiTest"`
Expected: 编译失败 — `ImportApi` 不存在

- [ ] **Step 3: 实现 ImportApi**

`app/src/main/java/com/huaxia/inventory/web/api/ImportApi.java`：

```java
package com.huaxia.inventory.web.api;

import android.util.Log;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.excel.ExcelImporter;
import com.huaxia.inventory.web.ApiException;
import com.huaxia.inventory.web.WebRequest;
import com.huaxia.inventory.web.WebResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Excel 上传导入库存池（taskId = 0）。
 *
 * <p>与 {@code ImportViewModel.commitToTask} 同构：每批 500 条用单事务包住
 * 「查重 + 写入」，防止并发插入让 updated 计数漂移。
 *
 * <p>并发控制：同一时刻只允许一个导入 —— 两个导入并发 upsert 同一 epc 会互相覆盖。
 */
public class ImportApi {

    private static final String TAG = "ImportApi";
    private static final int BATCH = 500;
    private static final int ERROR_SAMPLE_MAX = 50;

    private final AppDatabase db;
    private final File uploadDir;
    private final AtomicBoolean importing = new AtomicBoolean(false);

    public ImportApi(AppDatabase db, File uploadDir) {
        this.db = db;
        this.uploadDir = uploadDir;
    }

    public WebResponse upload(WebRequest req) throws ApiException {
        byte[] body = req.body();
        if (body.length == 0) {
            throw ApiException.badRequest("上传内容为空");
        }
        if (!importing.compareAndSet(false, true)) {
            throw ApiException.conflict("IMPORT_IN_PROGRESS", "已有导入正在进行，请稍后重试");
        }
        File tmp = null;
        try {
            tmp = writeTemp(body);
            return WebResponse.ok(parseAndCommit(tmp));
        } finally {
            importing.set(false);
            if (tmp != null && tmp.exists() && !tmp.delete()) {
                Log.w(TAG, "failed to delete temp upload " + tmp.getName());
            }
        }
    }

    private File writeTemp(byte[] body) throws ApiException {
        if (!uploadDir.exists() && !uploadDir.mkdirs()) {
            throw ApiException.badRequest("无法创建上传临时目录");
        }
        File tmp = new File(uploadDir, UUID.randomUUID() + ".xlsx");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(body);
        } catch (IOException e) {
            throw ApiException.badRequest("写入临时文件失败");
        }
        return tmp;
    }

    private Map<String, Object> parseAndCommit(File file) throws ApiException {
        int[] counters = {0, 0, 0};   // success, failed, updated
        List<String> errors = new ArrayList<>();

        try (InputStream in = new FileInputStream(file)) {
            ExcelImporter.stream(in, 0L, BATCH, new ExcelImporter.BatchHandler() {
                @Override public void onBatch(List<Asset> batch) {
                    final int[] updatedInBatch = {0};
                    db.runInTransaction(() -> {
                        List<String> epcs = new ArrayList<>(batch.size());
                        for (Asset a : batch) epcs.add(a.epc);
                        updatedInBatch[0] = db.assetDao().findExistingEpcs(epcs).size();
                        db.assetDao().upsertBatch(batch);
                    });
                    counters[0] += batch.size();
                    counters[2] += updatedInBatch[0];
                }

                @Override public void onError(String message) {
                    counters[1]++;
                    if (errors.size() < ERROR_SAMPLE_MAX) errors.add(message);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "import failed", e);
            throw new ApiException(422, "IMPORT_PARSE_FAILED",
                    "文件解析失败：" + (e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", counters[0]);
        out.put("updated", counters[2]);
        out.put("added", counters[0] - counters[2]);
        out.put("failed", counters[1]);
        out.put("errors", errors);
        out.put("truncated", counters[1] > errors.size());
        return out;
    }
}
```

- [ ] **Step 4: 挂路由**

`WebApiRegistry.register` 内追加：

```java
        ImportApi importer = new ImportApi(db, new java.io.File(ctx.getCacheDir(), "web-upload"));
        router.post("/api/import", importer::upload);
```

并补 import：`import com.huaxia.inventory.web.api.ImportApi;`

- [ ] **Step 5: 跑测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.web.api.ImportApiTest"`
Expected: 3 个用例 PASS

- [ ] **Step 6: 跑全量测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/huaxia/inventory/web/api/ImportApi.java \
        app/src/main/java/com/huaxia/inventory/web/WebApiRegistry.java \
        app/src/test/java/com/huaxia/inventory/web/api/ImportApiTest.java
git commit -m "feat(web): Excel 上传导入库存池接口"
```

---

## Task 8: 导出 API（任务报表 / 台账 / 模板）

**Files:**
- Create: `app/src/main/java/com/huaxia/inventory/web/api/ExportApi.java`
- Modify: `app/src/main/java/com/huaxia/inventory/web/WebApiRegistry.java`
- Test: `app/src/test/java/com/huaxia/inventory/web/api/ExportApiTest.java`

**Interfaces:**
- Consumes: `ExcelExporter.exportStreamed(...)`、`AssetLedgerExporter.export(...)`、`SqlLikeEscaper`（Task 6）
- Produces: `ExportApi(AppDatabase db, android.content.Context ctx, File exportDir)`，
  方法 `taskReport/ledger/template`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/huaxia/inventory/web/api/ExportApiTest.java`：

```java
package com.huaxia.inventory.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.web.ApiException;
import com.huaxia.inventory.web.WebRequest;
import com.huaxia.inventory.web.WebResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class ExportApiTest {

    private AppDatabase db;
    private ExportApi api;
    private File exportDir;
    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase.class)
                .allowMainThreadQueries().build();
        exportDir = new File(ctx.getCacheDir(), "web-export");
        api = new ExportApi(db, ctx, exportDir);
    }

    @After
    public void tearDown() { db.close(); }

    private long seedTask() {
        InventoryTask t = new InventoryTask();
        t.sourceFileName = "行政部盘点";
        t.importedAt = System.currentTimeMillis();
        t.status = "IN_PROGRESS";
        t.expectedCount = 1;
        long id = db.inventoryTaskDao().insert(t);

        Asset a = new Asset();
        a.taskId = id;
        a.excelRowIndex = 1;
        a.epc = "1001";
        a.dept = "行政部";
        a.assetType = "笔记本";
        a.rawRowJson = "{\"序号\":\"1\",\"设备类型\":\"笔记本\",\"设备型号\":\"X1\","
                + "\"序列号\":\"SN1\",\"固资编号\":\"1001\",\"领用部门\":\"行政部\","
                + "\"领用人\":\"张三\",\"盘点日期\":\"\"}";
        db.assetDao().upsertOne(a);
        return id;
    }

    private static WebRequest req(String path, long id) {
        return new WebRequest("GET", path, Collections.emptyMap(), Collections.emptyMap(),
                new byte[0]).withPathParams(Collections.singletonMap("id", String.valueOf(id)));
    }

    @Test
    public void taskReport_setsXlsxMimeAndEncodedFilename() throws Exception {
        long id = seedTask();

        WebResponse res = api.taskReport(req("/api/export/task/x.xlsx", id));

        assertThat(res.mime).contains("spreadsheetml");
        assertThat(res.headers.get("Content-Disposition")).contains("filename*=UTF-8''");
        assertThat(res.length).isPositive();
        // 流被消费后临时文件必须被清理
        assertThat(res.stream).isNotNull();
        res.stream.close();
    }

    @Test
    public void taskReport_returns404ForMissingTask() throws Exception {
        try {
            api.taskReport(req("/api/export/task/x.xlsx", 999L));
            org.junit.Assert.fail("expected ApiException");
        } catch (ApiException e) {
            assertThat(e.code).isEqualTo("TASK_NOT_FOUND");
        }
    }

    @Test
    public void ledger_returnsXlsxForEmptyDatabase() throws Exception {
        WebResponse res = api.ledger(new WebRequest("GET", "/api/export/ledger.xlsx",
                Collections.emptyMap(), Collections.emptyMap(), new byte[0]));

        assertThat(res.mime).contains("spreadsheetml");
        assertThat(res.length).isPositive();
        res.stream.close();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.web.api.ExportApiTest"`
Expected: 编译失败 — `ExportApi` 不存在

- [ ] **Step 3: 实现 ExportApi**

`app/src/main/java/com/huaxia/inventory/web/api/ExportApi.java`：

```java
package com.huaxia.inventory.web.api;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.excel.AssetLedgerExporter;
import com.huaxia.inventory.excel.ExcelExporter;
import com.huaxia.inventory.util.SqlLikeEscaper;
import com.huaxia.inventory.web.ApiException;
import com.huaxia.inventory.web.WebRequest;
import com.huaxia.inventory.web.WebResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Excel 导出。
 *
 * <p>先写临时文件再以文件流下发 —— 客户端中途取消下载时，写临时文件不会像写管道那样
 * 抛 broken pipe；临时文件在流关闭时删除。
 */
public class ExportApi {

    private static final String TAG = "ExportApi";
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final AppDatabase db;
    private final Context ctx;
    private final File exportDir;
    private final ConcurrentHashMap<Long, Boolean> inFlight = new ConcurrentHashMap<>();

    public ExportApi(AppDatabase db, Context ctx, File exportDir) {
        this.db = db;
        this.ctx = ctx.getApplicationContext();
        this.exportDir = exportDir;
    }

    public WebResponse taskReport(WebRequest req) throws ApiException {
        long taskId = req.pathParamLong("id");
        InventoryTask task = db.inventoryTaskDao().findById(taskId);
        if (task == null) throw ApiException.notFound("TASK_NOT_FOUND", "任务不存在");

        if (inFlight.putIfAbsent(taskId, Boolean.TRUE) != null) {
            throw ApiException.conflict("EXPORT_IN_PROGRESS", "该任务正在导出，请稍后重试");
        }
        try {
            File out = newTempFile();
            try (OutputStream os = new FileOutputStream(out)) {
                ExcelExporter.exportStreamed(os, task.sourceFileName, rebuildHeaders(taskId),
                        db.assetDao(), db.scanRecordDao(), db.extraClaimDao(), taskId,
                        ctx.getCacheDir());
            } catch (IOException e) {
                deleteQuietly(out);
                throw new ApiException(500, "INTERNAL", "导出失败：" + e.getMessage());
            }
            return fileResponse(out, task.sourceFileName + "_盘点结果.xlsx");
        } finally {
            inFlight.remove(taskId);
        }
    }

    public WebResponse ledger(WebRequest req) throws ApiException {
        String pattern = SqlLikeEscaper.likePattern(req.query("keyword"));
        String dept = emptyToNull(req.query("dept"));
        String type = emptyToNull(req.query("type"));

        File out = newTempFile();
        try (OutputStream os = new FileOutputStream(out)) {
            AssetLedgerExporter.export(os, db.assetDao(), pattern, dept, type,
                    ctx.getCacheDir());
        } catch (IOException e) {
            deleteQuietly(out);
            throw new ApiException(500, "INTERNAL", "导出失败：" + e.getMessage());
        }
        return fileResponse(out, "资产台账.xlsx");
    }

    public WebResponse template(WebRequest req) throws ApiException {
        try (InputStream in = ctx.getAssets().open("import_template.xlsx")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return WebResponse.bytes(out.toByteArray(), XLSX_MIME);
        } catch (IOException e) {
            Log.e(TAG, "read import template failed", e);
            throw new ApiException(500, "INTERNAL", "读取模板失败");
        }
    }

    /**
     * 从该任务首个 asset 的 rawRowJson 重建原始表头顺序，并丢弃「盘点日期」列
     * （由「盘点时间」取代）。与 App 端 ResultFragment 的导出逻辑一致。
     */
    private List<String> rebuildHeaders(long taskId) {
        List<String> headers = new ArrayList<>();
        List<Asset> firstPage = db.assetDao().pageAll(taskId, 1, 0);
        if (firstPage.isEmpty() || firstPage.get(0).rawRowJson == null) return headers;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = new ObjectMapper()
                    .readValue(firstPage.get(0).rawRowJson, Map.class);
            for (String k : raw.keySet()) {
                if (!"盘点日期".equals(k)) headers.add(k);
            }
        } catch (Exception e) {
            Log.w(TAG, "rebuildHeaders failed, falling back to required headers", e);
            headers.clear();
            headers.addAll(com.huaxia.inventory.excel.HeaderSpec.REQUIRED);
            headers.remove("盘点日期");
        }
        return headers;
    }

    private WebResponse fileResponse(File file, String downloadName) throws ApiException {
        try {
            InputStream in = new SelfDeletingInputStream(new FileInputStream(file), file);
            return WebResponse.file(in, file.length(), XLSX_MIME, downloadName);
        } catch (IOException e) {
            deleteQuietly(file);
            throw new ApiException(500, "INTERNAL", "读取导出文件失败");
        }
    }

    private File newTempFile() throws ApiException {
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw new ApiException(500, "INTERNAL", "无法创建导出临时目录");
        }
        return new File(exportDir, UUID.randomUUID() + ".xlsx");
    }

    private static void deleteQuietly(File f) {
        if (f.exists() && !f.delete()) Log.w(TAG, "failed to delete " + f.getName());
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** 流关闭时删除底层临时文件。 */
    private static final class SelfDeletingInputStream extends java.io.FilterInputStream {
        private final File file;

        SelfDeletingInputStream(InputStream in, File file) {
            super(in);
            this.file = file;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "failed to delete export " + file.getName());
                }
            }
        }
    }
}
```

- [ ] **Step 4: 挂路由**

`WebApiRegistry.register` 内追加：

```java
        ExportApi exporter = new ExportApi(db, ctx,
                new java.io.File(ctx.getCacheDir(), "web-export"));
        router.get("/api/export/task/{id}.xlsx", exporter::taskReport);
        router.get("/api/export/ledger.xlsx", exporter::ledger);
        router.get("/api/template.xlsx", exporter::template);
```

**注意**：`{id}.xlsx` 这种「参数段带后缀」的写法，`WebRouter.match` 会把整段 `"3.xlsx"` 当作 `id`，
`Long.parseLong("3.xlsx")` 会失败。因此 `ExportApi.taskReport` 需要先剥后缀：

```java
        String raw = req.pathParam("id");
        if (raw != null && raw.endsWith(".xlsx")) raw = raw.substring(0, raw.length() - 5);
        long taskId;
        try {
            taskId = Long.parseLong(raw);
        } catch (Exception e) {
            throw ApiException.badRequest("任务 id 必须是数字");
        }
```

把上面 6 行放在 `taskReport` 方法开头，替换掉原来的 `long taskId = req.pathParamLong("id");`。
测试里的 `req("/api/export/task/x.xlsx", id)` 传的是 `id` 的 pathParam，所以测试仍能通过。

并补 import：`import com.huaxia.inventory.web.api.ExportApi;`

- [ ] **Step 5: 跑测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.web.api.ExportApiTest"`
Expected: 3 个用例 PASS

- [ ] **Step 6: 跑全量测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/huaxia/inventory/web/api/ExportApi.java \
        app/src/main/java/com/huaxia/inventory/web/WebApiRegistry.java \
        app/src/test/java/com/huaxia/inventory/web/api/ExportApiTest.java
git commit -m "feat(web): 任务报表 / 资产台账 / 导入模板导出接口"
```

---

## Task 9: 前端骨架 + 大屏看板页

**Files:**
- Create: `app/src/main/assets/web/index.html`
- Create: `app/src/main/assets/web/css/tokens.css`
- Create: `app/src/main/assets/web/css/base.css`
- Create: `app/src/main/assets/web/css/app.css`
- Create: `app/src/main/assets/web/css/dashboard.css`
- Create: `app/src/main/assets/web/js/api.js`
- Create: `app/src/main/assets/web/js/ui.js`
- Create: `app/src/main/assets/web/js/router.js`
- Create: `app/src/main/assets/web/js/dashboard.js`
- Create: `app/src/main/assets/web/js/app.js`
- Test: `app/src/test/java/com/huaxia/inventory/web/WebAssetsTest.java`

**Interfaces:**
- Consumes: `GET /api/dashboard`（Task 4）
- Produces:
  - `api.js` 导出 `get(path, params)` / `post(path, body)` / `del(path)` / `download(url)`
  - `ui.js` 导出 `toast(msg)` / `confirmDialog(msg)` / `paginator(el, state, onPage)` / `empty(el, text)` / `fmtTime(ms)`
  - `router.js` 导出 `register(pattern, render)` / `start(container)`
  - `dashboard.js` 导出 `render(el)`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/huaxia/inventory/web/WebAssetsTest.java`：

```java
package com.huaxia.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import android.content.Context;
import android.content.res.AssetManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RunWith(RobolectricTestRunner.class)
public class WebAssetsTest {

    private static String readAsset(String path) throws IOException {
        Context ctx = ApplicationProvider.getApplicationContext();
        try (InputStream in = ctx.getAssets().open(path)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        }
    }

    @Test
    public void indexHtml_exists() throws Exception {
        assertThat(readAsset("web/index.html")).contains("资产管家");
    }

    @Test
    public void indexHtml_referencesOnlyExistingFiles() throws Exception {
        String html = readAsset("web/index.html");
        List<String> missing = new ArrayList<>();
        Matcher m = Pattern.compile("/static/([A-Za-z0-9_./-]+)").matcher(html);
        while (m.find()) {
            try (InputStream ignored = ApplicationProvider.getApplicationContext()
                    .getAssets().open("web/" + m.group(1))) {
                // 存在即可
            } catch (IOException e) {
                missing.add(m.group(1));
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    public void dashboardJs_definesRender() throws Exception {
        assertThat(readAsset("web/js/dashboard.js")).contains("export function render");
    }

    @Test
    public void tokensCss_declaresDarkThemeOverride() throws Exception {
        String css = readAsset("web/css/tokens.css");
        assertThat(css).contains("[data-theme=\"dark\"]");
        assertThat(css).contains("--color-accent");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.web.WebAssetsTest"`
Expected: FAIL — `web/index.html` 不存在

- [ ] **Step 3: 写 index.html**

`app/src/main/assets/web/index.html`：

```html
<!DOCTYPE html>
<html lang="zh-CN" data-theme="light">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>资产管家</title>
<link rel="stylesheet" href="/static/css/tokens.css">
<link rel="stylesheet" href="/static/css/base.css">
<link rel="stylesheet" href="/static/css/app.css">
<link rel="stylesheet" href="/static/css/dashboard.css">
<link rel="stylesheet" href="/static/css/print.css" media="print">
</head>
<body>
<header class="topbar">
  <div class="brand">📦 <b>资产管家</b><span class="sub">盘点看板</span></div>
  <nav class="nav">
    <a href="#/dashboard" data-nav="dashboard">看板</a>
    <a href="#/tasks" data-nav="tasks">任务</a>
    <a href="#/assets" data-nav="assets">资产</a>
    <a href="#/import" data-nav="import">导入</a>
  </nav>
  <button id="themeToggle" class="ghost" title="切换主题">🌙</button>
</header>
<main id="view"></main>
<div id="toast" class="toast" hidden></div>
<script type="module" src="/static/js/app.js"></script>
</body>
</html>
```

- [ ] **Step 4: 写 tokens.css**

`app/src/main/assets/web/css/tokens.css`：

```css
:root {
  --color-bg: #f7fafd;
  --color-bg-grad: linear-gradient(180deg, #f7fafd 0%, #eef3f9 100%);
  --color-surface: #ffffff;
  --color-border: #e3eaf3;
  --color-text: #1e293b;
  --color-text-sub: #7b8ca6;
  --color-text-dim: #9aaac0;
  --color-accent: #1565c0;
  --color-accent-soft: #eaf3fe;
  --color-ok: #15803d;
  --color-warn: #c2740a;
  --color-bad: #dc2626;
  --color-track: #e4ebf4;
  --shadow-card: 0 2px 8px rgba(30, 58, 95, .06);
  --radius: 10px;
  --gap: 16px;
  --font-num: ui-monospace, "SF Mono", Consolas, monospace;
}

[data-theme="dark"] {
  --color-bg: #080d18;
  --color-bg-grad: radial-gradient(ellipse at 50% -20%, #12203a 0%, #080d18 60%);
  --color-surface: #101a2b;
  --color-border: #1e293b;
  --color-text: #e2e8f0;
  --color-text-sub: #94a3b8;
  --color-text-dim: #475569;
  --color-accent: #38bdf8;
  --color-accent-soft: #0c2437;
  --color-ok: #34d399;
  --color-warn: #fbbf24;
  --color-bad: #f87171;
  --color-track: #1b2739;
  --shadow-card: none;
}
```

- [ ] **Step 5: 写 base.css**

`app/src/main/assets/web/css/base.css`：

```css
* { margin: 0; padding: 0; box-sizing: border-box; }
html, body { height: 100%; }
body {
  background: var(--color-bg-grad);
  color: var(--color-text);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif;
  font-size: 15px;
  display: flex;
  flex-direction: column;
}
a { color: inherit; text-decoration: none; }
button { font: inherit; cursor: pointer; }
h2 { font-size: 20px; margin-bottom: 4px; }
.subtitle { color: var(--color-text-sub); font-size: 13px; }
.num { font-family: var(--font-num); font-variant-numeric: tabular-nums; }
```

- [ ] **Step 6: 写 app.css**

`app/src/main/assets/web/css/app.css`：

```css
.topbar {
  display: flex; align-items: center; gap: 24px;
  padding: 12px 24px;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
}
.brand { font-size: 17px; }
.brand .sub { color: var(--color-text-dim); font-size: 12px; margin-left: 8px; }
.nav { display: flex; gap: 4px; flex: 1; }
.nav a {
  padding: 6px 14px; border-radius: 999px; color: var(--color-text-sub); font-size: 14px;
}
.nav a.active { background: var(--color-accent-soft); color: var(--color-accent); font-weight: 600; }
.ghost {
  border: 1px solid var(--color-border); background: transparent; color: var(--color-text-sub);
  border-radius: 8px; padding: 6px 10px;
}
main { flex: 1; padding: 20px 24px; overflow: auto; }

.card {
  background: var(--color-surface); border: 1px solid var(--color-border);
  border-radius: var(--radius); box-shadow: var(--shadow-card); padding: 16px;
}
.row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.spacer { flex: 1; }

.btn {
  border: 1px solid var(--color-border); background: var(--color-surface);
  color: var(--color-text); border-radius: 8px; padding: 7px 14px; font-size: 14px;
}
.btn.primary { background: var(--color-accent); border-color: var(--color-accent); color: #fff; }
.btn.danger { color: var(--color-bad); border-color: var(--color-bad); }
.btn:disabled { opacity: .5; cursor: not-allowed; }

.tag { display: inline-block; padding: 2px 8px; border-radius: 999px; font-size: 12px; }
.tag.running { background: #e8f5ee; color: var(--color-ok); }
.tag.paused { background: #fdf3e3; color: var(--color-warn); }
.tag.done { background: var(--color-track); color: var(--color-text-sub); }

input[type="text"], input[type="search"], select {
  border: 1px solid var(--color-border); border-radius: 8px; padding: 7px 12px;
  background: var(--color-surface); color: var(--color-text); font: inherit; font-size: 14px;
}

table { width: 100%; border-collapse: collapse; font-size: 14px; }
th, td { padding: 10px 12px; text-align: left; border-bottom: 1px solid var(--color-border); }
th { color: var(--color-text-sub); font-weight: 600; font-size: 13px; }
tbody tr:hover { background: var(--color-accent-soft); }

.pager { display: flex; gap: 8px; align-items: center; justify-content: flex-end; padding-top: 12px; }

.toast {
  position: fixed; left: 50%; bottom: 32px; transform: translateX(-50%);
  background: rgba(20, 30, 48, .92); color: #fff; padding: 10px 20px;
  border-radius: 999px; font-size: 14px; z-index: 999;
}
.empty { text-align: center; color: var(--color-text-dim); padding: 48px 0; }

.drop {
  border: 2px dashed var(--color-border); border-radius: var(--radius);
  padding: 48px; text-align: center; color: var(--color-text-sub); cursor: pointer;
}
.drop.over { border-color: var(--color-accent); background: var(--color-accent-soft); }
```

- [ ] **Step 7: 写 dashboard.css**

`app/src/main/assets/web/css/dashboard.css`：

```css
.dash { display: grid; gap: var(--gap); grid-template-rows: auto 1fr auto; height: 100%; }
.kpis { display: grid; grid-template-columns: repeat(5, 1fr); gap: var(--gap); }
.kpi {
  background: var(--color-surface); border: 1px solid var(--color-border);
  border-radius: var(--radius); box-shadow: var(--shadow-card); padding: 16px 18px;
}
.kpi .v { font-size: 34px; font-weight: 800; line-height: 1.1; font-family: var(--font-num); }
.kpi .l { font-size: 13px; color: var(--color-text-sub); margin-top: 2px; }
.kpi.hi { background: var(--color-accent-soft); border-color: var(--color-accent); }
.kpi.hi .v { color: var(--color-accent); }
.kpi .v.ok { color: var(--color-ok); }
.kpi .v.warn { color: var(--color-warn); }

.dash-main { display: grid; grid-template-columns: 27% 1fr; gap: var(--gap); min-height: 0; }
.dash-col { display: grid; gap: var(--gap); grid-template-rows: auto 1fr; min-height: 0; }
.panel {
  background: var(--color-surface); border: 1px solid var(--color-border);
  border-radius: var(--radius); box-shadow: var(--shadow-card); padding: 16px;
  display: flex; flex-direction: column; min-height: 0;
}
.panel > h3 { font-size: 14px; color: var(--color-text-sub); font-weight: 600; margin-bottom: 14px;
  display: flex; justify-content: space-between; }
.panel > h3 span { font-weight: 400; color: var(--color-text-dim); font-size: 12px; }

.ring-row { display: flex; align-items: center; gap: 18px; }
.ring {
  width: 104px; height: 104px; border-radius: 50%; flex: none;
  display: flex; align-items: center; justify-content: center;
  background: conic-gradient(var(--color-ok) 0 var(--pct, 0%), var(--color-track) var(--pct, 0%) 100%);
}
.ring i {
  width: 80px; height: 80px; border-radius: 50%; background: var(--color-surface);
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  font-style: normal;
}
.ring .p { font-size: 20px; font-weight: 800; color: var(--color-ok); }
.ring .s { font-size: 11px; color: var(--color-text-dim); }
.stats { display: flex; flex-direction: column; gap: 8px; flex: 1; }
.stats .r { display: flex; justify-content: space-between; font-size: 14px; }
.stats .r span { color: var(--color-text-sub); }
.stats .r b { font-family: var(--font-num); }

.task-line { margin-bottom: 14px; }
.task-line:last-child { margin-bottom: 0; }
.task-line .top { display: flex; justify-content: space-between; font-size: 13px; margin-bottom: 5px; }
.task-line .nm { color: var(--color-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.task-line .pc { color: var(--color-text-sub); font-family: var(--font-num); }
.task-line .trk { height: 6px; background: var(--color-track); border-radius: 3px; overflow: hidden; }
.task-line .trk i { display: block; height: 100%; border-radius: 3px; background: var(--color-ok); }
.task-line .trk i.paused { background: var(--color-warn); }
.task-line .trk i.done { background: var(--color-text-dim); }

.barchart { flex: 1; display: flex; flex-direction: column; justify-content: space-around; }
.brow { display: flex; align-items: center; gap: 12px; }
.brow .n { width: 120px; text-align: right; font-size: 13px; color: var(--color-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: none; }
.brow .trk { flex: 1; height: 14px; background: var(--color-track); border-radius: 7px; overflow: hidden; }
.brow .trk i { display: block; height: 100%; border-radius: 7px;
  background: var(--color-accent); opacity: .85; }
.brow .c { width: 52px; text-align: right; font-size: 13px; color: var(--color-text-sub);
  font-family: var(--font-num); flex: none; }

.dash-foot { display: flex; justify-content: space-between; font-size: 12px; color: var(--color-text-dim); }

@media (max-width: 900px) {
  .kpis { grid-template-columns: repeat(2, 1fr); }
  .dash-main { grid-template-columns: 1fr; }
}
```

- [ ] **Step 8: 写 api.js**

`app/src/main/assets/web/js/api.js`：

```js
const BASE = '/api';

async function request(path, { method = 'GET', body = null, headers = {} } = {}) {
  const res = await fetch(BASE + path, { method, body, headers });
  const type = res.headers.get('content-type') || '';
  if (!res.ok) {
    let payload = null;
    if (type.includes('application/json')) {
      try { payload = await res.json(); } catch (_) { payload = null; }
    }
    const info = payload && payload.error ? payload.error : null;
    const err = new Error(info ? info.message : `HTTP ${res.status}`);
    err.code = info ? info.code : `HTTP_${res.status}`;
    err.status = res.status;
    throw err;
  }
  return type.includes('application/json') ? res.json() : res;
}

function qs(params) {
  if (!params) return '';
  const parts = [];
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue;
    parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(v)}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

export function get(path, params) {
  return request(path + qs(params));
}

export function post(path, payload) {
  return request(path, {
    method: 'POST',
    body: JSON.stringify(payload || {}),
    headers: { 'Content-Type': 'application/json' },
  });
}

export function del(path) {
  return request(path, { method: 'DELETE' });
}

export function uploadXlsx(path, filename, arrayBuffer) {
  return request(`${path}?filename=${encodeURIComponent(filename)}`, {
    method: 'POST',
    body: arrayBuffer,
    headers: { 'Content-Type': 'application/octet-stream' },
  });
}

export function download(url) {
  window.location.href = url;
}
```

- [ ] **Step 9: 写 ui.js**

`app/src/main/assets/web/js/ui.js`：

```js
export function toast(message, ms = 2200) {
  const el = document.getElementById('toast');
  if (!el) return;
  el.textContent = message;
  el.hidden = false;
  clearTimeout(el._timer);
  el._timer = setTimeout(() => { el.hidden = true; }, ms);
}

export function confirmDialog(message) {
  return Promise.resolve(window.confirm(message));
}

export function empty(container, text = '暂无数据') {
  const el = document.createElement('div');
  el.className = 'empty';
  el.textContent = text;
  container.appendChild(el);
}

export function fmtTime(ms) {
  if (!ms) return '—';
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

export function pct(v) {
  return `${Math.round((v || 0) * 100)}%`;
}

/**
 * 渲染分页器。
 * @param {HTMLElement} el 容器
 * @param {{page:number,totalPages:number}} state
 * @param {(page:number)=>void} onPage
 */
export function paginator(el, state, onPage) {
  el.innerHTML = '';
  el.className = 'pager';
  if (!state.totalPages || state.totalPages <= 1) return;

  const mk = (label, page, disabled) => {
    const b = document.createElement('button');
    b.className = 'btn';
    b.textContent = label;
    b.disabled = disabled;
    b.addEventListener('click', () => onPage(page));
    el.appendChild(b);
  };

  mk('上一页', state.page - 1, state.page <= 1);
  const info = document.createElement('span');
  info.className = 'subtitle';
  info.textContent = `${state.page} / ${state.totalPages}`;
  el.appendChild(info);
  mk('下一页', state.page + 1, state.page >= state.totalPages);
}

export function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}
```

- [ ] **Step 10: 写 router.js**

`app/src/main/assets/web/js/router.js`：

```js
const routes = [];

export function register(pattern, render) {
  routes.push({ segments: split(pattern), render });
}

function split(hash) {
  const clean = (hash || '').replace(/^#\/?/, '').replace(/\/$/, '');
  return clean ? clean.split('/') : [];
}

function match(pattern, actual) {
  if (pattern.length !== actual.length) return null;
  const params = {};
  for (let i = 0; i < pattern.length; i++) {
    if (pattern[i].startsWith(':')) params[pattern[i].slice(1)] = decodeURIComponent(actual[i]);
    else if (pattern[i] !== actual[i]) return null;
  }
  return params;
}

let container = null;

async function dispatch() {
  const actual = split(window.location.hash);
  for (const r of routes) {
    const params = match(r.segments, actual);
    if (!params) continue;
    document.querySelectorAll('.nav a').forEach((a) => {
      const target = a.getAttribute('href').replace(/^#\//, '');
      a.classList.toggle('active', target === actual[0]);
    });
    container.innerHTML = '';
    try {
      await r.render(container, params);
    } catch (e) {
      container.innerHTML = '';
      const box = document.createElement('div');
      box.className = 'empty';
      box.textContent = `加载失败：${e.message}`;
      container.appendChild(box);
    }
    return;
  }
  window.location.hash = '#/dashboard';
}

export function start(el) {
  container = el;
  window.addEventListener('hashchange', dispatch);
  dispatch();
}
```

- [ ] **Step 11: 写 dashboard.js**

`app/src/main/assets/web/js/dashboard.js`：

```js
import { get } from './api.js';
import { fmtTime, el } from './ui.js';

const POLL_MS = 5000;

export async function render(root) {
  root.appendChild(buildSkeleton());

  let timer = null;
  const load = async () => {
    if (!root.isConnected) {
      clearInterval(timer);
      return;
    }
    try {
      const data = await get('/dashboard');
      paint(data);
    } catch (e) {
      setStatus(`连接中断：${e.message}`);
    }
  };

  await load();
  timer = setInterval(load, POLL_MS);
  window.addEventListener('hashchange', () => clearInterval(timer), { once: true });
}

function buildSkeleton() {
  const wrap = el('div', 'dash');

  const kpis = el('div', 'kpis');
  kpis.id = 'kpis';
  wrap.appendChild(kpis);

  const main = el('div', 'dash-main');
  main.innerHTML = `
    <div class="dash-col">
      <div class="panel" id="activeTask"></div>
      <div class="panel" id="taskList"></div>
    </div>
    <div class="dash-col" style="grid-template-rows: 1fr 1fr;">
      <div class="panel" id="deptChart"></div>
      <div class="panel" id="typeChart"></div>
    </div>`;
  wrap.appendChild(main);

  const foot = el('div', 'dash-foot');
  foot.innerHTML = '<span>数据来源：本机 PDA 数据库 · 每 5 秒自动刷新</span>'
    + '<span id="dashStatus">正在连接…</span>';
  wrap.appendChild(foot);

  return wrap;
}

function setStatus(text) {
  const s = document.getElementById('dashStatus');
  if (s) s.textContent = text;
}

function paint(data) {
  paintKpis(data);
  paintActiveTask(data.activeTask);
  paintTaskList(data);
  paintBars('deptChart', '部门分布', data.deptDistribution);
  paintBars('typeChart', '设备类型分布', data.typeDistribution);
  setStatus(`最后更新 ${new Date(data.updatedAt).toLocaleTimeString('zh-CN')}`);
}

function paintKpis(data) {
  const box = document.getElementById('kpis');
  if (!box) return;
  const counts = data.taskCounts || {};
  const items = [
    { v: data.ledger.total, l: '资产总数', cls: 'hi' },
    { v: data.ledger.deptCount, l: '领用部门' },
    { v: data.ledger.typeCount, l: '设备类型' },
    { v: counts.inProgress || 0, l: '进行中任务', cls: 'warn' },
    { v: counts.completed || 0, l: '已完成任务', cls: 'ok' },
  ];
  box.innerHTML = '';
  for (const it of items) {
    const k = el('div', `kpi ${it.cls || ''}`);
    k.appendChild(el('div', `v ${it.cls === 'warn' ? 'warn' : it.cls === 'ok' ? 'ok' : ''}`,
      String(it.v)));
    k.appendChild(el('div', 'l', it.l));
    box.appendChild(k);
  }
}

function paintActiveTask(task) {
  const box = document.getElementById('activeTask');
  if (!box) return;
  if (!task) {
    box.innerHTML = '<h3>当前任务</h3><div class="empty">PDA 上尚未选择任务</div>';
    return;
  }
  const pctValue = Math.round((task.progress || 0) * 100);
  box.innerHTML = `
    <h3>当前任务 <span>${escapeHtml(task.status === 'PAUSED' ? '已暂停' : '进行中')}</span></h3>
    <div class="ring-row">
      <div class="ring" style="--pct:${pctValue}%">
        <i><span class="p">${pctValue}%</span>
            <span class="s">${task.scanned}/${task.expected}</span></i>
      </div>
      <div class="stats">
        <div class="r"><span>已盘</span><b style="color:var(--color-ok)">${task.scanned}</b></div>
        <div class="r"><span>盘亏</span><b style="color:var(--color-bad)">${task.missing}</b></div>
        <div class="r"><span>盘盈</span><b style="color:var(--color-warn)">${task.extra}</b></div>
      </div>
    </div>`;
}

function paintTaskList(data) {
  const box = document.getElementById('taskList');
  if (!box) return;
  box.innerHTML = '<h3>任务概览 <span>进行中 ' + (data.taskCounts.inProgress || 0)
    + ' · 已完成 ' + (data.taskCounts.completed || 0) + '</span></h3>';
  const list = el('div');
  list.style.flex = '1';
  list.style.display = 'flex';
  list.style.flexDirection = 'column';
  list.style.justifyContent = 'space-around';
  box.appendChild(list);

  const active = data.activeTask;
  if (!active) {
    list.appendChild(el('div', 'empty', '暂无进行中的任务'));
    return;
  }
  list.appendChild(taskLine(active.name, active.progress, 'running'));
}

function taskLine(name, progress, kind) {
  const wrap = el('div', 'task-line');
  const top = el('div', 'top');
  top.appendChild(el('span', 'nm', name || '未命名任务'));
  top.appendChild(el('span', 'pc', `${Math.round((progress || 0) * 100)}%`));
  wrap.appendChild(top);
  const trk = el('div', 'trk');
  const fill = el('i', kind === 'paused' ? 'paused' : '');
  fill.style.width = `${Math.max(1, Math.round((progress || 0) * 100))}%`;
  trk.appendChild(fill);
  wrap.appendChild(trk);
  return wrap;
}

function paintBars(containerId, title, rows) {
  const box = document.getElementById(containerId);
  if (!box) return;
  box.innerHTML = `<h3>${title} <span>按资产数量 · Top 10</span></h3>`;
  const chart = el('div', 'barchart');
  box.appendChild(chart);

  if (!rows || rows.length === 0) {
    chart.appendChild(el('div', 'empty', '暂无数据'));
    return;
  }
  const max = Math.max(...rows.map((r) => r.count), 1);
  for (const r of rows) {
    const row = el('div', 'brow');
    row.appendChild(el('span', 'n', r.name));
    const trk = el('span', 'trk');
    const fill = el('i');
    fill.style.width = `${Math.max(2, Math.round((r.count / max) * 100))}%`;
    trk.appendChild(fill);
    row.appendChild(trk);
    row.appendChild(el('span', 'c', String(r.count)));
    chart.appendChild(row);
  }
}

function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
```

- [ ] **Step 12: 写 app.js**

`app/src/main/assets/web/js/app.js`：

```js
import { register, start } from './router.js';
import * as dashboard from './dashboard.js';

const THEME_KEY = 'asset-theme';

function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  const btn = document.getElementById('themeToggle');
  if (btn) btn.textContent = theme === 'dark' ? '☀️' : '🌙';
  localStorage.setItem(THEME_KEY, theme);
}

applyTheme(localStorage.getItem(THEME_KEY) || 'light');

document.getElementById('themeToggle').addEventListener('click', () => {
  const current = document.documentElement.getAttribute('data-theme');
  applyTheme(current === 'dark' ? 'light' : 'dark');
});

register('/dashboard', dashboard.render);
register('/', dashboard.render);

start(document.getElementById('view'));
```

- [ ] **Step 13: 跑测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.web.WebAssetsTest"`
Expected: 4 个用例 PASS

- [ ] **Step 14: 浏览器验证**

启动 App 的 Web 服务后打开 `http://<PDA-IP>:8080/`：
- 顶部导航 4 个 tab 可点
- 看板页显示 KPI / 当前任务环 / 两个条形图
- 右上角 🌙 切换深色
- 浏览器控制台无报错

- [ ] **Step 15: 提交**

```bash
git add app/src/main/assets/web \
        app/src/test/java/com/huaxia/inventory/web/WebAssetsTest.java
git commit -m "feat(web): 前端骨架 + 大屏看板页（浅色默认 / 深色可切）"
```

---

## Task 10: 任务管理页

**Files:**
- Create: `app/src/main/assets/web/js/tasks.js`
- Modify: `app/src/main/assets/web/js/app.js`
- Test: `app/src/test/java/com/huaxia/inventory/web/WebAssetsTest.java`（追加用例）

**Interfaces:**
- Consumes: `GET/POST/DELETE /api/tasks*`（Task 5）、`GET /api/tasks/{id}/results`（Task 6）、`api.js` / `ui.js`（Task 9）
- Produces: `tasks.js` 导出 `renderList(root)` / `renderDetail(root, params)`

- [ ] **Step 1: 追加失败测试**

在 `WebAssetsTest` 中追加：

```java
    @Test
    public void tasksJs_definesListAndDetail() throws Exception {
        String js = readAsset("web/js/tasks.js");
        assertThat(js).contains("export async function renderList");
        assertThat(js).contains("export async function renderDetail");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.web.WebAssetsTest"`
Expected: 新用例 FAIL — `web/js/tasks.js` 不存在

- [ ] **Step 3: 写 tasks.js**

`app/src/main/assets/web/js/tasks.js`：

```js
import { get, post, del, download } from './api.js';
import { toast, confirmDialog, empty, paginator, el, fmtTime, pct } from './ui.js';

const STATUS_LABEL = { IN_PROGRESS: '进行中', PAUSED: '已暂停', COMPLETED: '已完成' };
const STATUS_CLASS = { IN_PROGRESS: 'running', PAUSED: 'paused', COMPLETED: 'done' };

function notifySessionChange(res) {
  if (res && res.sessionChanged) {
    const prev = res.previousTaskId == null ? '无' : res.previousTaskId;
    toast(`已切换 PDA 当前扫描任务（原任务 ${prev}）`);
  }
}

export async function renderList(root) {
  const data = await get('/tasks');
  const wrap = el('div');

  const head = el('div', 'row');
  head.style.marginBottom = '16px';
  head.appendChild(el('h2', null, '盘点任务'));
  head.appendChild(el('div', 'spacer'));

  const btnClear = el('button', 'btn danger', '清除已完成');
  btnClear.addEventListener('click', async () => {
    if (!(await confirmDialog('清除全部已完成任务？资产会退回库存池。'))) return;
    await del('/tasks/completed');
    toast('已清除');
    rerender(root);
  });
  const btnNew = el('button', 'btn primary', '新建任务');
  btnNew.addEventListener('click', () => openCreateDialog(root));
  head.appendChild(btnClear);
  head.appendChild(btnNew);
  wrap.appendChild(head);

  if (!data.items.length) {
    empty(wrap, '还没有任务，点「新建任务」开始');
    root.appendChild(wrap);
    return;
  }

  for (const t of data.items) {
    wrap.appendChild(taskCard(root, t));
  }
  root.appendChild(wrap);
}

function taskCard(root, t) {
  const card = el('div', 'card');
  card.style.marginBottom = '12px';

  const top = el('div', 'row');
  top.appendChild(el('b', null, t.name || '未命名任务'));
  const tag = el('span', `tag ${STATUS_CLASS[t.status] || 'done'}`, STATUS_LABEL[t.status] || t.status);
  top.appendChild(tag);
  if (t.dept) top.appendChild(el('span', 'subtitle', `部门：${t.dept}`));
  top.appendChild(el('div', 'spacer'));
  top.appendChild(el('span', 'subtitle num',
    `${t.scanned} / ${t.expected} · ${pct(t.progress)}`));
  card.appendChild(top);

  const trk = el('div', 'task-line');
  const bar = el('div', 'trk');
  const fill = el('i', t.status === 'PAUSED' ? 'paused' : t.status === 'COMPLETED' ? 'done' : '');
  fill.style.width = `${Math.max(1, Math.round((t.progress || 0) * 100))}%`;
  bar.appendChild(fill);
  trk.appendChild(bar);
  trk.style.marginTop = '10px';
  card.appendChild(trk);

  const actions = el('div', 'row');
  actions.style.marginTop = '12px';

  if (t.status === 'IN_PROGRESS') {
    actions.appendChild(actionBtn('暂停', () => post(`/tasks/${t.id}/pause`), root));
  }
  if (t.status === 'PAUSED') {
    actions.appendChild(actionBtn('恢复', () => post(`/tasks/${t.id}/resume`), root));
  }
  if (t.status !== 'COMPLETED') {
    actions.appendChild(actionBtn('完成任务', () => post(`/tasks/${t.id}/complete`), root, true));
  }
  actions.appendChild(actionBtn('查看详情', null, root, false,
    () => { window.location.hash = `#/tasks/${t.id}`; }));
  actions.appendChild(actionBtn('导出报表', null, root, false,
    () => download(`/api/export/task/${t.id}.xlsx`)));
  actions.appendChild(actionBtn('删除', async () => {
    if (!(await confirmDialog(`删除任务「${t.name}」？资产会退回库存池。`))) return null;
    return del(`/tasks/${t.id}`);
  }, root, true));

  card.appendChild(actions);
  return card;
}

function actionBtn(label, fn, root, danger, override) {
  const b = el('button', `btn ${danger ? 'danger' : ''}`, label);
  b.addEventListener('click', async () => {
    if (override) { override(); return; }
    if (!fn) return;
    b.disabled = true;
    try {
      const res = await fn();
      notifySessionChange(res);
      if (res) toast('操作成功');
      rerender(root);
    } catch (e) {
      toast(e.message);
      b.disabled = false;
    }
  });
  return b;
}

function rerender(root) {
  root.innerHTML = '';
  renderList(root).catch((e) => {
    empty(root, `加载失败：${e.message}`);
  });
}

async function openCreateDialog(root) {
  const depts = await get('/tasks/depts');
  const mask = el('div');
  mask.style.cssText = 'position:fixed;inset:0;background:rgba(15,23,42,.45);'
    + 'display:flex;align-items:center;justify-content:center;z-index:500';

  const box = el('div', 'card');
  box.style.cssText = 'width:420px;max-width:92vw';
  box.innerHTML = '<h2 style="margin-bottom:16px">新建盘点任务</h2>';

  const nameInput = el('input');
  nameInput.type = 'text';
  nameInput.placeholder = '任务名称，如 2026Q4 行政部盘点';
  nameInput.style.cssText = 'width:100%;margin-bottom:12px';

  const deptSelect = document.createElement('select');
  deptSelect.style.cssText = 'width:100%;margin-bottom:12px';
  const optAll = document.createElement('option');
  optAll.value = '';
  optAll.textContent = '不限部门（盘点整个库存池）';
  deptSelect.appendChild(optAll);
  for (const d of depts) {
    const o = document.createElement('option');
    o.value = d;
    o.textContent = d;
    deptSelect.appendChild(o);
  }

  const remarkInput = el('input');
  remarkInput.type = 'text';
  remarkInput.placeholder = '备注（可选）';
  remarkInput.style.cssText = 'width:100%;margin-bottom:16px';

  const actions = el('div', 'row');
  actions.style.justifyContent = 'flex-end';
  const cancel = el('button', 'btn', '取消');
  const ok = el('button', 'btn primary', '创建');

  cancel.addEventListener('click', () => mask.remove());
  ok.addEventListener('click', async () => {
    ok.disabled = true;
    try {
      const res = await post('/tasks', {
        name: nameInput.value,
        dept: deptSelect.value,
        remark: remarkInput.value,
      });
      mask.remove();
      toast(`已创建，划拨 ${res.assigned} 台资产`);
      rerender(root);
    } catch (e) {
      toast(e.message);
      ok.disabled = false;
    }
  });

  actions.appendChild(cancel);
  actions.appendChild(ok);
  box.appendChild(nameInput);
  box.appendChild(deptSelect);
  box.appendChild(remarkInput);
  box.appendChild(actions);
  mask.appendChild(box);
  document.body.appendChild(mask);
  nameInput.focus();
}

export async function renderDetail(root, params) {
  const id = params.id;
  const task = await get(`/tasks/${id}`);

  const wrap = el('div');
  const head = el('div', 'row');
  head.style.marginBottom = '16px';
  head.appendChild(el('h2', null, task.name || '未命名任务'));
  head.appendChild(el('span', `tag ${STATUS_CLASS[task.status] || 'done'}`,
    STATUS_LABEL[task.status] || task.status));
  head.appendChild(el('div', 'spacer'));

  const btnReport = el('button', 'btn', '打印报表');
  btnReport.addEventListener('click', () => { window.location.hash = `#/report/${id}`; });
  const btnExport = el('button', 'btn primary', '导出报表');
  btnExport.addEventListener('click', () => download(`/api/export/task/${id}.xlsx`));
  head.appendChild(btnReport);
  head.appendChild(btnExport);
  wrap.appendChild(head);

  const kpis = el('div', 'kpis');
  kpis.style.marginBottom = '16px';
  const cards = [
    { v: task.expected, l: '应盘' },
    { v: task.scanned, l: '已盘' },
    { v: task.missing, l: '盘亏' },
    { v: task.extra, l: '盘盈' },
  ];
  for (const c of cards) {
    const k = el('div', 'kpi');
    k.appendChild(el('div', 'v', String(c.v)));
    k.appendChild(el('div', 'l', c.l));
    kpis.appendChild(k);
  }
  wrap.appendChild(kpis);

  const tabs = el('div', 'row');
  tabs.style.marginBottom = '12px';
  const tableBox = el('div', 'card');
  const pagerBox = el('div');

  const state = { tab: 'ALL', page: 1 };

  const loadTab = async () => {
    const data = await get(`/tasks/${id}/results`, {
      tab: state.tab, page: state.page, pageSize: 20,
    });
    tableBox.innerHTML = '';
    if (!data.items.length) {
      empty(tableBox, '暂无数据');
    } else {
      tableBox.appendChild(state.tab === 'EXTRA'
        ? extraTable(data.items) : assetTable(data.items));
    }
    paginator(pagerBox, { page: data.page, totalPages: data.totalPages }, (p) => {
      state.page = p;
      loadTab();
    });
  };

  for (const [key, label] of [['ALL', '全部'], ['FOUND', '已盘'], ['MISSING', '盘亏'], ['EXTRA', '盘盈']]) {
    const b = el('button', 'btn', label);
    b.addEventListener('click', () => {
      state.tab = key;
      state.page = 1;
      tabs.querySelectorAll('button').forEach((x) => x.classList.remove('primary'));
      b.classList.add('primary');
      loadTab();
    });
    if (key === 'ALL') b.classList.add('primary');
    tabs.appendChild(b);
  }

  wrap.appendChild(tabs);
  wrap.appendChild(tableBox);
  wrap.appendChild(pagerBox);
  root.appendChild(wrap);
  await loadTab();
}

function assetTable(items) {
  const table = document.createElement('table');
  table.innerHTML = `<thead><tr>
    <th>固资编号</th><th>设备类型</th><th>设备型号</th>
    <th>序列号</th><th>领用部门</th><th>领用人</th><th>盘点时间</th>
  </tr></thead>`;
  const tbody = document.createElement('tbody');
  for (const a of items) {
    const tr = document.createElement('tr');
    for (const v of [a.epc, a.assetType, a.assetModel, a.serialNumber, a.dept, a.custodian]) {
      const td = document.createElement('td');
      td.textContent = v == null ? '' : v;
      tr.appendChild(td);
    }
    const tdTime = document.createElement('td');
    tdTime.className = 'num';
    tdTime.textContent = fmtTime(a.inventoryTime);
    tr.appendChild(tdTime);
    tbody.appendChild(tr);
  }
  table.appendChild(tbody);
  return table;
}

function extraTable(items) {
  const table = document.createElement('table');
  table.innerHTML = '<thead><tr><th>固资编号</th><th>扫描时间</th><th>RSSI</th></tr></thead>';
  const tbody = document.createElement('tbody');
  for (const s of items) {
    const tr = document.createElement('tr');
    const tdEpc = document.createElement('td');
    tdEpc.textContent = s.epc || '';
    const tdTime = document.createElement('td');
    tdTime.className = 'num';
    tdTime.textContent = fmtTime(s.scannedAt);
    const tdRssi = document.createElement('td');
    tdRssi.textContent = s.rssi || '';
    tr.appendChild(tdEpc);
    tr.appendChild(tdTime);
    tr.appendChild(tdRssi);
    tbody.appendChild(tr);
  }
  table.appendChild(tbody);
  return table;
}
```

- [ ] **Step 4: 在 app.js 注册路由**

在 `app/src/main/assets/web/js/app.js` 中，`register('/dashboard', ...)` 之后追加：

```js
import * as tasks from './tasks.js';

register('/tasks', tasks.renderList);
register('/tasks/:id', tasks.renderDetail);
```

（import 必须放在文件顶部；把 `import * as tasks from './tasks.js';` 移到其它 import 之后。）

- [ ] **Step 5: 跑测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.web.WebAssetsTest"`
Expected: `tasksJs_definesListAndDetail` PASS，其余 4 个原有用例仍 PASS

- [ ] **Step 6: 浏览器验证**

- `#/tasks` 列出任务；新建任务后列表刷新且提示划拨台数
- 暂停/恢复/完成/删除均生效；恢复后出现「已切换 PDA 当前扫描任务」提示
- 点「查看详情」进入 `#/tasks/{id}`，四个 tab 可切换、分页可用

- [ ] **Step 7: 提交**

```bash
git add app/src/main/assets/web/js/tasks.js app/src/main/assets/web/js/app.js \
        app/src/test/java/com/huaxia/inventory/web/WebAssetsTest.java
git commit -m "feat(web): 任务管理页（列表 / 新建 / 状态流转 / 结果详情）"
```

---

## Task 11: 资产台账页 + 导入页

**Files:**
- Create: `app/src/main/assets/web/js/assets.js`
- Create: `app/src/main/assets/web/js/import.js`
- Modify: `app/src/main/assets/web/js/app.js`

**Interfaces:**
- Consumes: `GET /api/assets`、`GET /api/assets/filters`（Task 6）、`POST /api/import`（Task 7）、`GET /api/export/ledger.xlsx`、`GET /api/template.xlsx`（Task 8）
- Produces: `assets.js` 导出 `render(root)`；`import.js` 导出 `render(root)`

- [ ] **Step 1: 写 assets.js**

`app/src/main/assets/web/js/assets.js`：

```js
import { get, download } from './api.js';
import { empty, paginator, el, fmtTime } from './ui.js';

export async function render(root) {
  const filters = await get('/assets/filters');
  const state = { keyword: '', dept: '', type: '', page: 1, pageSize: 20 };

  const wrap = el('div');

  const head = el('div', 'row');
  head.style.marginBottom = '16px';
  head.appendChild(el('h2', null, '资产台账'));
  head.appendChild(el('div', 'spacer'));
  const btnExport = el('button', 'btn primary', '导出台账');
  btnExport.addEventListener('click', () => {
    const q = new URLSearchParams();
    if (state.keyword) q.set('keyword', state.keyword);
    if (state.dept) q.set('dept', state.dept);
    if (state.type) q.set('type', state.type);
    download(`/api/export/ledger.xlsx${q.toString() ? '?' + q : ''}`);
  });
  head.appendChild(btnExport);
  wrap.appendChild(head);

  const bar = el('div', 'row');
  bar.style.marginBottom = '12px';

  const search = el('input');
  search.type = 'search';
  search.placeholder = '搜索固资编号 / 型号 / 序列号 / 部门 / 领用人';
  search.style.cssText = 'flex:1;min-width:260px';
  search.addEventListener('input', debounce(() => {
    state.keyword = search.value.trim();
    state.page = 1;
    load();
  }, 300));

  const deptSelect = buildSelect('全部部门', filters.depts);
  deptSelect.addEventListener('change', () => {
    state.dept = deptSelect.value;
    state.page = 1;
    load();
  });

  const typeSelect = buildSelect('全部类型', filters.types);
  typeSelect.addEventListener('change', () => {
    state.type = typeSelect.value;
    state.page = 1;
    load();
  });

  bar.appendChild(search);
  bar.appendChild(deptSelect);
  bar.appendChild(typeSelect);
  wrap.appendChild(bar);

  const tableBox = el('div', 'card');
  const pagerBox = el('div');
  wrap.appendChild(tableBox);
  wrap.appendChild(pagerBox);
  root.appendChild(wrap);

  async function load() {
    const data = await get('/assets', {
      keyword: state.keyword,
      dept: state.dept,
      type: state.type,
      page: state.page,
      pageSize: state.pageSize,
    });
    tableBox.innerHTML = '';
    if (!data.items.length) {
      empty(tableBox, '没有匹配的资产');
    } else {
      tableBox.appendChild(buildTable(data.items));
    }
    pagerBox.innerHTML = '';
    paginator(pagerBox, { page: data.page, totalPages: data.totalPages }, (p) => {
      state.page = p;
      load();
    });
  }

  await load();
}

function buildSelect(allLabel, options) {
  const select = document.createElement('select');
  const optAll = document.createElement('option');
  optAll.value = '';
  optAll.textContent = allLabel;
  select.appendChild(optAll);
  for (const o of options) {
    const opt = document.createElement('option');
    opt.value = o.name;
    opt.textContent = `${o.name}（${o.count}）`;
    select.appendChild(opt);
  }
  return select;
}

function buildTable(items) {
  const table = document.createElement('table');
  table.innerHTML = `<thead><tr>
    <th>固资编号</th><th>设备类型</th><th>设备型号</th>
    <th>序列号</th><th>领用部门</th><th>领用人</th><th>盘点时间</th>
  </tr></thead>`;
  const tbody = document.createElement('tbody');
  for (const a of items) {
    const tr = document.createElement('tr');
    for (const v of [a.epc, a.assetType, a.assetModel, a.serialNumber, a.dept, a.custodian]) {
      const td = document.createElement('td');
      td.textContent = v == null ? '' : v;
      tr.appendChild(td);
    }
    const tdTime = document.createElement('td');
    tdTime.className = 'num';
    tdTime.textContent = fmtTime(a.inventoryTime);
    tr.appendChild(tdTime);
    tbody.appendChild(tr);
  }
  table.appendChild(tbody);
  return table;
}

function debounce(fn, ms) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), ms);
  };
}
```

- [ ] **Step 2: 写 import.js**

`app/src/main/assets/web/js/import.js`：

```js
import { uploadXlsx, download } from './api.js';
import { toast, el } from './ui.js';

export async function render(root) {
  const wrap = el('div');
  const head = el('div', 'row');
  head.style.marginBottom = '16px';
  head.appendChild(el('h2', null, '导入资产'));
  head.appendChild(el('div', 'spacer'));
  const btnTpl = el('button', 'btn', '下载导入模板');
  btnTpl.addEventListener('click', () => download('/api/template.xlsx'));
  head.appendChild(btnTpl);
  wrap.appendChild(head);

  const drop = el('div', 'drop');
  drop.innerHTML = '<div style="font-size:32px">📄</div>'
    + '<div style="margin-top:8px">拖拽 Excel 文件到这里，或点击选择</div>'
    + '<div class="subtitle" style="margin-top:6px">支持 .xlsx，导入后进入库存池</div>';

  const input = document.createElement('input');
  input.type = 'file';
  input.accept = '.xlsx';
  input.style.display = 'none';

  const result = el('div', 'card');
  result.style.marginTop = '16px';
  result.hidden = true;

  drop.addEventListener('click', () => input.click());
  drop.addEventListener('dragover', (e) => {
    e.preventDefault();
    drop.classList.add('over');
  });
  drop.addEventListener('dragleave', () => drop.classList.remove('over'));
  drop.addEventListener('drop', (e) => {
    e.preventDefault();
    drop.classList.remove('over');
    if (e.dataTransfer.files.length) handleFile(e.dataTransfer.files[0]);
  });
  input.addEventListener('change', () => {
    if (input.files.length) handleFile(input.files[0]);
  });

  wrap.appendChild(drop);
  wrap.appendChild(input);
  wrap.appendChild(result);
  root.appendChild(wrap);

  async function handleFile(file) {
    if (!file.name.toLowerCase().endsWith('.xlsx')) {
      toast('请选择 .xlsx 文件');
      return;
    }
    drop.innerHTML = `<div class="subtitle">正在上传 ${file.name} …</div>`;
    try {
      const buffer = await file.arrayBuffer();
      const res = await uploadXlsx('/import', file.name, buffer);
      drop.innerHTML = '<div style="font-size:32px">📄</div>'
        + '<div style="margin-top:8px">拖拽 Excel 文件到这里，或点击选择</div>';
      showResult(res);
      toast(`导入完成：成功 ${res.success} 条`);
    } catch (e) {
      drop.innerHTML = '<div style="font-size:32px">📄</div>'
        + '<div style="margin-top:8px">拖拽 Excel 文件到这里，或点击选择</div>';
      toast(e.message);
    }
  }

  function showResult(res) {
    result.hidden = false;
    result.innerHTML = `
      <h3 style="font-size:15px;margin-bottom:12px">导入结果</h3>
      <div class="row">
        <span class="tag running">成功 ${res.success}</span>
        <span class="tag done">覆盖更新 ${res.updated}</span>
        <span class="tag done">新增 ${res.added}</span>
        <span class="tag ${res.failed ? 'paused' : 'done'}">失败 ${res.failed}</span>
      </div>`;
    if (res.errors && res.errors.length) {
      const list = el('div');
      list.style.cssText = 'margin-top:12px;font-size:13px;color:var(--color-bad);max-height:240px;overflow:auto';
      for (const msg of res.errors) {
        const line = el('div', null, msg);
        line.style.marginBottom = '4px';
        list.appendChild(line);
      }
      if (res.truncated) list.appendChild(el('div', 'subtitle', '（错误过多，仅显示前 50 条）'));
      result.appendChild(list);
    }
  }
}
```

- [ ] **Step 3: 在 app.js 注册路由**

```js
import * as assets from './assets.js';
import * as importer from './import.js';

register('/assets', assets.render);
register('/import', importer.render);
```

- [ ] **Step 4: 跑测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.web.WebAssetsTest"`
Expected: 5 个用例全 PASS

- [ ] **Step 5: 浏览器验证**

- `#/assets`：搜索、部门/类型下拉、翻页、导出台账均可用
- `#/import`：拖拽或点选 xlsx，回显成功/覆盖/新增/失败与错误行
- 下载模板能拿到 `import_template.xlsx`

- [ ] **Step 6: 提交**

```bash
git add app/src/main/assets/web/js/assets.js app/src/main/assets/web/js/import.js \
        app/src/main/assets/web/js/app.js
git commit -m "feat(web): 资产台账页与 Excel 导入页"
```

---

## Task 12: 报表打印页

**Files:**
- Create: `app/src/main/assets/web/js/report.js`
- Create: `app/src/main/assets/web/css/print.css`
- Modify: `app/src/main/assets/web/js/app.js`

**Interfaces:**
- Consumes: `GET /api/tasks/{id}`、`GET /api/tasks/{id}/results?pageSize=200`（Task 5/6）
- Produces: `report.js` 导出 `render(root, params)`

- [ ] **Step 1: 写 print.css**

`app/src/main/assets/web/css/print.css`：

```css
@media print {
  @page { size: A4 landscape; margin: 12mm; }
  body { background: #fff; color: #000; }
  .topbar, .toast, .no-print { display: none !important; }
  main { padding: 0; }
  .report-head { margin-bottom: 10mm; }
  .report-title { font-size: 18pt; font-weight: 700; }
  .report-meta { font-size: 9pt; color: #444; margin-top: 2mm; }
  .report-stats { display: flex; gap: 10mm; margin: 6mm 0; font-size: 10pt; }
  table { width: 100%; border-collapse: collapse; font-size: 8pt; }
  th, td { border: 1px solid #999; padding: 2mm; text-align: left; }
  thead { display: table-header-group; }
  tr { page-break-inside: avoid; }
}
```

- [ ] **Step 2: 写 report.js**

`app/src/main/assets/web/js/report.js`：

```js
import { get } from './api.js';
import { el, fmtTime } from './ui.js';

const PAGE_SIZE = 200;

export async function render(root, params) {
  const id = params.id;
  const task = await get(`/tasks/${id}`);

  const wrap = el('div', 'report');
  wrap.style.background = 'var(--color-surface)';
  wrap.style.padding = '24px';
  wrap.style.borderRadius = 'var(--radius)';

  const toolbar = el('div', 'row no-print');
  toolbar.style.marginBottom = '16px';
  const back = el('button', 'btn', '返回任务');
  back.addEventListener('click', () => { window.location.hash = `#/tasks/${id}`; });
  const print = el('button', 'btn primary', '打印 / 另存为 PDF');
  print.addEventListener('click', () => window.print());
  toolbar.appendChild(back);
  toolbar.appendChild(el('div', 'spacer'));
  toolbar.appendChild(print);
  wrap.appendChild(toolbar);

  const head = el('div', 'report-head');
  head.appendChild(el('div', 'report-title', `固定资产盘点结果 — ${task.name || ''}`));
  head.appendChild(el('div', 'report-meta',
    `导出时间：${fmtTime(Date.now())}　·　部门：${task.dept || '不限'}　·　`
    + `应盘 ${task.expected}　已盘 ${task.scanned}　盘亏 ${task.missing}　盘盈 ${task.extra}`));
  wrap.appendChild(head);

  const rows = [];
  let page = 1;
  for (;;) {
    const data = await get(`/tasks/${id}/results`, { tab: 'ALL', page, pageSize: PAGE_SIZE });
    rows.push(...data.items);
    if (page >= data.totalPages || page > 50) break;
    page += 1;
  }

  const table = document.createElement('table');
  table.innerHTML = `<thead><tr>
    <th>序号</th><th>固资编号</th><th>设备类型</th><th>设备型号</th>
    <th>序列号</th><th>领用部门</th><th>领用人</th><th>盘点时间</th>
  </tr></thead>`;
  const tbody = document.createElement('tbody');
  rows.forEach((a, i) => {
    const tr = document.createElement('tr');
    const cells = [String(i + 1), a.epc, a.assetType, a.assetModel,
      a.serialNumber, a.dept, a.custodian, fmtTime(a.inventoryTime)];
    for (const v of cells) {
      const td = document.createElement('td');
      td.textContent = v == null ? '' : v;
      tr.appendChild(td);
    }
    tbody.appendChild(tr);
  });
  table.appendChild(tbody);
  wrap.appendChild(table);

  root.appendChild(wrap);
}
```

- [ ] **Step 3: 在 app.js 注册路由**

```js
import * as report from './report.js';

register('/report/:id', report.render);
```

- [ ] **Step 4: 追加路由守卫测试并跑测试**

在 `WebAssetsTest` 中追加：

```java
    @Test
    public void appJs_registersAllRoutes() throws Exception {
        String js = readAsset("web/js/app.js");
        assertThat(js).contains("'/tasks'");
        assertThat(js).contains("'/tasks/:id'");
        assertThat(js).contains("'/assets'");
        assertThat(js).contains("'/import'");
        assertThat(js).contains("'/report/:id'");
    }
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.huaxia.inventory.web.WebAssetsTest"`
Expected: 全部 6 个用例 PASS

- [ ] **Step 5: 浏览器验证**

- `#/report/{id}` 显示完整表格
- 点「打印 / 另存为 PDF」弹出打印对话框，预览为 A4 横向、无导航栏
- 选「另存为 PDF」能保存出中文正常的 PDF

- [ ] **Step 6: 提交**

```bash
git add app/src/main/assets/web/js/report.js app/src/main/assets/web/css/print.css \
        app/src/main/assets/web/js/app.js
git commit -m "feat(web): 报表打印页（浏览器另存为 PDF）"
```

---

## Task 13: README 与端到端验收

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: 全部前置任务
- Produces: 文档

- [ ] **Step 1: 更新 README**

在 `README.md` 的「## 技术栈」列表中追加一行（放在 Jackson 那行之后）：

```markdown
- NanoHTTPD 2.3.1（内嵌 HTTP 服务，见「Web 服务」章节）
```

在 `README.md` 末尾追加：

```markdown
## Web 服务（内嵌 HTTP）

App 内置一个 HTTP 服务，PDA 连上 WiFi 或自己开热点后，同网段任何设备用浏览器打开
`http://<PDA-IP>:8080` 即可使用，**不需要任何后端服务器**，数据仍然只在本机。

### 开启

齿轮菜单 → 「Web 服务」→ 打开开关。对话框与通知栏都会显示当前地址，端口从 8080 起
（被占用则顺延到 8089）。关闭开关或点通知栏「停止」即关停。

### 能做什么

| 页面 | 能力 |
|---|---|
| 看板 `#/dashboard` | 资产总数 / 部门 / 类型 / 任务状态 KPI；当前任务环形进度；部门与设备类型分布条形图。每 5 秒自动刷新，右上角可切深色主题 |
| 任务 `#/tasks` | 列表、新建（可按部门限定）、暂停、恢复、完成、删除、清除已完成；任务详情含全部/已盘/盘亏/盘盈四个结果 tab 与分页 |
| 资产 `#/assets` | 全量台账搜索、部门/类型筛选、分页、导出台账 Excel |
| 导入 `#/import` | 上传 `.xlsx` 导入库存池（与 App 端导入等价），回显成功/覆盖/新增/失败与错误行 |
| 报表 `#/report/{id}` | A4 横向报表预览，浏览器「打印 / 另存为 PDF」出 PDF |

### 已知限制

- **无认证** —— 同网段任何人都能访问并修改任务、上传 Excel。仅建议在可信局域网内开启。
  网页端删除类操作有二次确认，但那只是误操作防护，不是安全边界。
- 部分国产 ROM 的后台清理会杀掉前台服务，需要把 App 加入自启动/后台白名单。
- 修改前端静态资源需重新打包 APK。debug 构建下可把改好的文件
  `adb push` 到 `/sdcard/Android/data/com.assetmanager.inventory/files/web/` 临时验证。
- 网页端资产字段只读，编辑仍在 PDA 上完成。
- 一台 PDA 一个服务，多台 PDA 之间不共享数据。
```

- [ ] **Step 2: 跑全量测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿（基线 257 + 本次新增约 35 个用例）

- [ ] **Step 3: 构建并真机验收**

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
adb shell am start -n com.assetmanager.inventory/com.huaxia.inventory.MainActivity
```

按顺序走一遍（每步都要观察结果，不要只看日志）：

1. 齿轮 → Web 服务 → 开启；记下地址，通知栏出现常驻通知。
2. PC 浏览器打开地址 → 看板页出数据；右上角切深色后刷新仍保持深色。
3. 任务页新建一个任务 → PDA 任务 tab 立即出现同名任务（不需要手动刷新）。
4. 网页点「恢复」→ 弹「已切换 PDA 当前扫描任务」→ PDA 扫描页标题变成该任务。
5. 网页「删除」该任务 → PDA 扫描页归零，资产 tab 中该批资产回到库存池。
6. 导入页上传一个 xlsx → 回显成功条数；PDA 资产 tab 数量同步增加。
7. 资产页筛选后「导出台账」→ 打开 Excel，行数与网页显示的总数一致。
8. 报表页「打印 / 另存为 PDF」→ 预览无导航栏、A4 横向、中文正常。
9. 关闭 Web 服务开关 → 浏览器刷新报连接失败，通知消失。

- [ ] **Step 4: 提交**

```bash
git add README.md
git commit -m "docs(web): README 补充 Web 服务使用说明与已知限制"
```

---

## 自查记录

**Spec 覆盖检查**（逐节对照 `docs/superpowers/specs/2026-11-12-android-web-server-design.md`）：

| Spec 章节 | 对应任务 |
|---|---|
| §4.1 web 包结构 | Task 2（基础设施）、Task 4–8（五个 API） |
| §4.2 TaskService 抽取 | Task 1 |
| §4.3 进程模型 | Task 3（服务/端口/IP/通知）、Task 2（线程池） |
| §4.4 线程规则 | Global Constraints + Task 2 |
| §5.1 大屏接口 | Task 4 |
| §5.2 任务接口 | Task 5 |
| §5.3 资产/结果接口 | Task 6 |
| §5.4 导入导出接口 | Task 7、Task 8 |
| §5.5 静态页 | Task 2（`serveStatic`） |
| §5.6 统一错误 | Task 2（`ApiException` + `WebResponse.error`） |
| §6.1 两个 DAO 聚合方法 | Task 4 |
| §6.2 导入数据流 | Task 7 |
| §6.3 导出数据流 | Task 8 |
| §6.4 与 PDA 一致性 | Task 1（TaskService）+ Task 5（sessionChanged） |
| §7 前端页面与工程结构 | Task 9–12 |
| §8 错误处理与并发 | Task 2（体积上限/线程池）、Task 3（端口/缓存清理）、Task 7（导入串行）、Task 8（导出去重） |
| §9 测试 | 每个任务内的测试步骤 |
| §10 交付清单 | Task 1–13 全覆盖 |
| §11 已知限制 | Task 13（README） |
| §12 不做的事 | 计划中无对应任务，符合预期 |

**占位符扫描**：无 TBD / TODO / 「类似 Task N」；每个代码步骤都给了可直接粘贴的实现。

**类型一致性核对**：
- `WebResponse` 字段 `status/mime/bytes/stream/length/headers` 在 Task 2 定义，Task 4–8、Task 3 全部按此使用。
- `WebRequest` 方法 `query/queryInt/pathParam/pathParamLong/body/bodyAsString/withPathParams` 在 Task 2 定义，Task 4–8 一致使用。
- `TaskService` 九个方法在 Task 1 定义，Task 5 的 `TaskApi` 全部对齐。
- `AssetDao.countByDeptGrouped` / `countByCategoryAll`、`InventoryTaskDao.findAll` 在 Task 4 定义，Task 4/6 使用。
- `SqlLikeEscaper.likePattern` 在 Task 6 定义，Task 6/8 使用。
- 前端 `api.js` 的 `get/post/del/download/uploadXlsx` 在 Task 9 定义，Task 10–12 使用。


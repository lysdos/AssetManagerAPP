# 内嵌 HTTP 服务（Web 看板 + 任务管理 + 导入导出）— 设计

- **日期**: 2026-11-12
- **作者**: brainstorm 输出
- **状态**: 待实施
- **范围**: `app/build.gradle` / `AndroidManifest.xml` / 新增 `web/` 包 / 新增 `service/TaskService` / `assets/web/` 前端 / `ui/task/TaskListViewModel` / `ui/setting/WebServerDialog` / `README.md`

---

## 1. 背景与目标

「资产管家」当前是纯离线 PDA 应用：所有数据在设备本地 Room 里，操作只能在 PDA 屏幕上完成。
现场有三个绕不开的诉求：

1. **资产总览要能上大屏** —— 领导/管理员在会议室或办公区看实时盘点进度，不可能把 PDA 递过去。
2. **盘点任务要能远程管理** —— 人不在 PDA 旁边时也要能建任务、暂停、完成、删除。
3. **数据要能进出** —— 从 PC 上传 Excel 导入，把任务报表/资产台账下载下来归档或打印。

目标：**在 App 内嵌一个 HTTP 服务器**，PDA 连上 WiFi 或自己开热点后，同网段的任何设备用浏览器打开
`http://<PDA-IP>:8080` 就能完成上述三件事。数据仍然只存在于 PDA 本地，不引入任何后端服务。

### 1.1 约束

- 纯 Java 11，不引入 Kotlin。
- 不改变现有 Room schema —— `AppDatabase.version` 保持 `1`，无迁移脚本。
- PDA 端 UI 与硬件（RFID / 条码 / 摄像头）逻辑零改动。
- 千级数据量（< 1 万资产），无需分库分表或预聚合。

---

## 2. 需求确认

| 问题 | 结论 |
|---|---|
| 客户端从哪访问 | 同一 WiFi 局域网 **和** PDA 开热点，两种都要支持 → 绑定 `0.0.0.0` |
| 大屏形态 | 独立的深色/浅色宽屏仪表盘（1920×1080 优化），不复用 480px 手机端稿 |
| 大屏版式 | **V1**：顶部 5 宫格 KPI → 左侧「当前任务卡 + 其他任务列表」→ 右侧堆叠「部门分布 / 设备类型分布」横向条形图 |
| 大屏配色 | **默认浅色 · 蓝白**（`#1565C0`，与 App 顶栏蓝一致），右上角可切 **深色 · 夜蓝**（`#38BDF8`） |
| 数据刷新 | 前端每 5 秒轮询 `GET /api/dashboard`，服务端 1 秒内合并重复请求 |
| 任务管理权限 | 完整增删改 —— 创建、暂停、恢复、完成、删除、清除已完成，与 PDA 端一致 |
| 网页上传 Excel 去向 | 导入全局库存池（`taskId=0`），与 App 端「导入」完全一致；建任务时再划拨 |
| 导出内容 | 任务结果报表 xlsx、资产台账 xlsx、PDF 报表（浏览器打印）、在线表格预览 |
| PDF 实现 | **浏览器打印为 PDF**（`window.print()` + `@media print`），服务端不生成 PDF |
| 访问控制 | **无认证**（已确认接受风险，见 §11） |
| 服务生命周期 | 设置页手动开关 + 前台服务，切后台/锁屏不断 |
| 资产读写边界 | 网页端**资产字段只读**；任务可写。编辑仍归 PDA，避免两端同时改同一行 |
| 数据量级 | 千级（< 1 万） |

---

## 3. 方案选型

### 3.1 HTTP 服务器框架

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **NanoHTTPD 2.3.1** | 单 jar ~50KB；零反射零注解，R8 天然安全；`newFixedLengthResponse(InputStream, len)` 直接吐 Excel 流 | 路由/参数解析要自己写（约 150 行）；multipart 解析弱 | **采用** |
| AndServer 2.x | 注解路由，`@RestController` 上手快 | 2021 年后停更；依赖注解处理器；反射需额外 keep 规则；与 AGP 8.2 兼容性未验证 | 否 |
| Ktor / Jetty / Netty | — | 项目纯 Java（无 Kotlin）；包体与 Android 兼容性问题 | 否 |

multipart 弱项通过「原始字节体 + 查询参数带文件名」绕开，反而更简单可测（见 §5.4）。

### 3.2 前端承载

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **原生 HTML/CSS/JS，放 `assets/web/`** | 无构建步骤；离线可用；改一行刷新即生效；包体几十 KB | 图表要手写 SVG/CSS | **采用** |
| Vue + Vite + ECharts 构建后拷入 assets | 图表与交互更强 | 仓库需引入 Node 工具链，多一层「改前端要 build」的心智负担 | 否 |
| CDN 引 ECharts | 零包体 | 现场无外网即白屏 | 否 |

本设计中的图表只有「横向条形 + 环形进度」，手写 CSS 完全够用。若后续需要更复杂图表，
单独 vendored 一份 ECharts 进 `assets/web/vendor/` 即可，不影响后端。

---

## 4. 架构

### 4.1 新增包 `com.huaxia.inventory.web`

```
web/
├── WebServerService.java   # 前台服务：持有 WebServer 生命周期 + 通知栏
├── WebServer.java          # 继承 NanoHTTPD，把 session 交给 router
├── WebRouter.java          # 路径+方法 → Handler 映射（约 15 条）
├── WebRequest.java         # 包装 session：path / query / body / 文件上传
├── WebResponse.java        # 三种响应：JSON / 文件流 / 静态资源
├── StaticAssets.java       # 读 assets/web/，带 MIME 与 Cache-Control
├── ApiException.java       # 业务错误 → {"error":{"code","message"}}
└── api/
    ├── DashboardApi.java   # 大屏统计（含 1 秒快照缓存）
    ├── TaskApi.java        # 任务增删改查
    ├── AssetApi.java       # 资产 / 任务结果查询（只读）
    ├── ImportApi.java      # Excel 上传 → 库存池
    └── ExportApi.java      # 任务报表 / 台账下载
```

### 4.2 关键改动：抽出 `service/TaskService`

`TaskListViewModel` 里的任务事务有严格顺序语义，**Web 层绝不能复制一份**：

- `createTask`：插入任务 → `assignPoolToTask` 划拨 → 用 fresh row 修正 `expectedCount`
- `deleteTask`：`revertEditedFields`（仅未提交）→ `clearInventoryTimeForTask` → `clearSnapshotForTask` → `releaseTaskToPool` → 删 `ExtraClaim` → 删 `ScanRecord` → 删任务壳
- `completeTask`：翻 `COMPLETED` + `clearSnapshotForTask`（提交语义）

两边各写一份必然漂移，而现有 9 个守卫测试钉的是 ViewModel 这一侧。

**做法**：新增 `service/TaskService.java`（纯 Java，构造入参 `AppDatabase` + `InventorySession` + `Executor`），
把六个操作（create / pause / resume / complete / delete / clearCompleted）的事务体搬进去，方法**同步、抛异常**。
`TaskListViewModel` 保留原有回调签名不变，内部改为：

```java
public void deleteTask(long taskId, DeleteCallback cb) {
    diskIO.execute(() -> {
        try {
            int released = taskService.deleteTask(taskId);
            if (cb != null) cb.onDeleted(taskId, released);
        } catch (Exception e) {
            Log.e(TAG, "deleteTask failed", e);
            if (cb != null) cb.onError(taskId, ...);
        }
    });
}
```

现有 UI 与测试零改动，Web 层直接调 `TaskService`。

### 4.3 进程模型

| 项 | 决定 |
|---|---|
| 承载 | `WebServerService`，started **foreground service**，`foregroundServiceType="dataSync"`（targetSdk 34 强制） |
| 启停 | 齿轮菜单新增「🌐 Web 服务」→ `WebServerDialog`：开关 + 可用 IP 列表 + 端口 + 二维码。开关状态存 `InventoryApp.getPrefs()`；**不自动启动、不做开机自启** |
| 绑定地址 | `0.0.0.0`，同时覆盖 WiFi 与热点 |
| 端口 | 默认 8080，占用则 8080→8089 顺延；实际端口显示在对话框与通知 |
| 通知 | 常驻：「资产管家 Web 服务运行中 · 192.168.43.1:8080」，带「停止」动作 |
| IP 发现 | `NetworkInterface` 枚举 IPv4 非回环地址 —— 不申请 `ACCESS_WIFI_STATE`，热点/WiFi 一视同仁 |
| 并发 | NanoHTTPD 默认每请求一线程 → 换 `ThreadPoolExecutor(core=2, max=4)`，防止多个 Excel 导出把 PDA 打满 |

### 4.4 线程规则

handler 全部跑在服务器线程池，直接调 Room 同步 DAO（Room 允许非主线程）。
唯一涉及主线程的是 `InventorySession` 的 LiveData 写入 —— 其内部用 `postValue`，天然线程安全，无需切主线程。

---

## 5. 接口设计

全部挂在 `/api` 下，JSON 由已有 Jackson 输出（复用 `ObjectMapper`，不新增依赖）。

### 5.1 大屏

```
GET /api/dashboard
```

```json
{
  "ledger":  { "total": 1284, "deptCount": 12, "typeCount": 8 },
  "taskCounts": { "inProgress": 2, "paused": 1, "completed": 5 },
  "activeTask": {
    "id": 3, "name": "2026Q4 行政部盘点", "dept": "行政部",
    "expected": 200, "scanned": 150, "missing": 50, "extra": 3,
    "progress": 0.75, "lastScannedAt": 1762900000000
  },
  "deptDistribution": [ { "name": "行政部", "count": 320 } ],
  "typeDistribution": [ { "name": "笔记本电脑", "count": 210 } ],
  "updatedAt": 1762900000000
}
```

- `deptDistribution` / `typeDistribution` 取 Top 10 + 「其他」合并项；空值走现有
  `DEPT_BUCKET` / `TYPE_BUCKET` 归一化，显示为「未填写部门 / 未分类」而不是被丢掉。
- `activeTask` 取 `session.currentTaskId`，无则 `null`。
- 服务端 1 秒内合并重复请求（`volatile` 快照 + 时刻），不引入缓存框架。

### 5.2 任务管理

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/tasks` | 列表，每项含 `expected/scanned/missing/extra/progress/status` |
| `GET` | `/api/tasks/{id}` | 详情 + 统计 |
| `GET` | `/api/tasks/depts` | 库存池可选部门（建任务下拉） |
| `POST` | `/api/tasks` | body `{name, dept?, remark?}` → 创建并划拨 |
| `POST` | `/api/tasks/{id}/pause` | 暂停 |
| `POST` | `/api/tasks/{id}/resume` | 恢复 |
| `POST` | `/api/tasks/{id}/complete` | 完成（提交，不可逆） |
| `DELETE` | `/api/tasks/{id}` | 删除（资产退回库存池 + 回滚未提交编辑） |
| `DELETE` | `/api/tasks/completed` | 清除已完成 |

**副作用说明**：`resume` 会把 `session.currentTaskId` 指向该任务（与 App 行为一致，否则「网页恢复 → PDA 接着扫」不成立）。
若 PDA 上操作员正在扫另一个任务，这等于远程切换了他的扫描目标。因此写操作响应统一带：

```json
{ "ok": true, "sessionChanged": true, "previousTaskId": 2 }
```

前端据此弹提示「已把 PDA 当前扫描任务切换为 XXX」。`pause` / `complete` / `delete` 命中当前任务时同理。

### 5.3 资产与结果（只读）

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/assets?keyword=&dept=&type=&page=&pageSize=` | 全量台账分页 |
| `GET` | `/api/assets/filters` | 部门 / 类型可选值 + 各自数量 |
| `GET` | `/api/tasks/{id}/results?tab=ALL\|FOUND\|MISSING\|EXTRA&page=&pageSize=` | 任务结果分页 |

分页统一信封：

```json
{ "items": [], "page": 1, "pageSize": 20, "total": 123, "totalPages": 7 }
```

关键词转义复用现有 `!` → `!!`、`%` → `!%`、`_` → `!_` 逻辑，提取到 `util/SqlLikeEscaper` 共用，
避免 App 与 Web 两套转义。

### 5.4 导入导出

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/import?filename=xxx.xlsx` | body 为 xlsx **原始字节**（`application/octet-stream`），不走 multipart |
| `GET` | `/api/export/task/{id}.xlsx` | 任务结果报表（`ExcelExporter.exportStreamed`） |
| `GET` | `/api/export/ledger.xlsx?keyword=&dept=&type=` | 资产台账（`AssetLedgerExporter`） |
| `GET` | `/api/template.xlsx` | 导入模板（`assets/import_template.xlsx` 直接下发） |

导入响应：

```json
{ "success": 1200, "updated": 37, "added": 1163, "failed": 3,
  "errors": ["第 5 行：固资编号为空"], "truncated": false }
```

`errors` 截断前 50 条并给 `truncated` 标志。浏览器端用 `FileReader.readAsArrayBuffer` 读文件后直接 POST，
因此服务端无需 multipart 解析。

**导出实现**：先写到 `getCacheDir()/web-export/<uuid>.xlsx`，再用
`newFixedLengthResponse(OK, mime, 自动删除的 FileInputStream, length)` 下发。
不用管道流 —— 客户端中途取消下载时导出器写管道会抛 broken pipe，写临时文件无此失败模式。

### 5.5 静态页

- `/` 及任意非 `/api` 路径 → `assets/web/index.html`（前端用 hash 路由）。
- `/static/*` → `assets/web/`。
- 未匹配的 `/api/*` → 404 JSON。

### 5.6 统一错误

```json
{ "error": { "code": "TASK_NOT_FOUND", "message": "任务不存在" } }
```

`code` 为稳定枚举：`TASK_NOT_FOUND` / `TASK_COMPLETED_READONLY` / `IMPORT_PARSE_FAILED` /
`EXPORT_IN_PROGRESS` / `IMPORT_IN_PROGRESS` / `BAD_REQUEST` / `PAYLOAD_TOO_LARGE` /
`TOO_MANY_REQUESTS` / `INTERNAL`。`message` 为中文用户文案。

---

## 6. 数据流

### 6.1 大屏轮询

```
浏览器 ──5s──> GET /api/dashboard
                 └─> DashboardApi（服务器线程池）
                       ├─ assetDao.countAll()                 // 全量台账（库存池 + 已划拨）
                       ├─ assetDao.countByDeptGrouped()       ← 新增，全局 GROUP BY
                       ├─ assetDao.countByCategoryAll()       ← 新增，全局 GROUP BY
                       ├─ inventoryTaskDao.findAll()          ← 新增，同步版
                       └─ session.currentTaskId → scanRecordDao.aggregateStats(id)
```

**口径**：大屏统计的是**全量台账**（库存池 `taskId=0` + 已划拨到任务的资产），与资产 tab 的
「全量台账」一致，因此用 `countAll()` 而不是 `countPool()`。现有 `countByCategory(taskId)` /
`countPool()` 都是按任务隔离的，不能直接用。

新增三个 DAO 方法：

- `InventoryTaskDao.findAll()` —— 同步列表（现有只有 `LiveData` 版，Web 层用不了）。
- `AssetDao.countByDeptGrouped()` —— 全局 `GROUP BY` 部门分布，复用现有 `DEPT_BUCKET` 表达式，
  让「未填写部门」也成为一个真实分桶而不是被过滤掉。
- `AssetDao.countByCategoryAll()` —— 全局 `GROUP BY` 类型分布，同理复用 `TYPE_BUCKET`。

`ledger.deptCount` / `ledger.typeCount` **不额外查询** —— 直接从分组结果的分组个数得出，
避免为「Top 10」之外的桶多跑一次 COUNT。


### 6.2 导入

```
浏览器 FileReader.readAsArrayBuffer
   → POST /api/import?filename=xx.xlsx   (application/octet-stream)
      └─> ImportApi：body 写入 cacheDir/web-upload/<uuid>.xlsx
            └─> ExcelImporter.stream(in, 0L, 500, handler)
                  handler.onBatch → db.runInTransaction {
                                       findExistingEpcs(batch)   // 统计"覆盖更新"数
                                       assetDao.upsertBatch(batch)
                                     }
      finally 删除临时文件
```

与 `ImportViewModel.commitToTask` **完全同构**：单批事务包住「查重 + 写入」，防止并发插入让 `updated` 计数漂移。
区别只在于数据源是临时文件而非 `ContentResolver`。

**不做「预览后再提交」两步**：网页上传一次即落库，响应回统计。App 端的预览是为了让操作员确认选对文件，
浏览器上文件名与统计已足够。

### 6.3 导出

```
GET /api/export/task/3.xlsx
  └─> 并发去重（ConcurrentHashMap<Long,Boolean> 在飞标记）
        ├─ 已在导出 → 409 EXPORT_IN_PROGRESS
        └─ 否则 → cacheDir/web-export/<uuid>.xlsx
              ├─ ExcelExporter.exportStreamed(out, name, headers, daos, taskId, cacheDir)
              └─ newFixedLengthResponse(OK, xlsx-mime, 自动删除的 FileInputStream, len)
```

`originalHeaders` 从该任务首个 asset 的 `rawRowJson` 重建（复用 `HeaderSpec` 现有逻辑），与 App 端一致。
台账导出走 `AssetLedgerExporter.export`，筛选参数与 `/api/assets` 共用同一套转义结果 ——
保证「网页上筛出来的」与「导出的 Excel」必然一致。

### 6.4 与 PDA 的一致性

所有写操作在 `TaskService` 或 `runInTransaction` 内完成，`InventorySession` 变更通过 `LiveData.postValue`
自动推到 PDA 的四个 Fragment，**PDA 端不需要任何轮询或刷新代码**。这是复用 `TaskService` + `InventorySession` 的直接收益。

---

## 7. 前端

### 7.1 页面

| 路由 | 页面 | 要点 |
|---|---|---|
| `#/dashboard` | 大屏看板 | V1 版式，5 秒轮询 |
| `#/tasks` | 任务管理 | 卡片列表；每张显示任务名/部门/状态标签/进度条/已盘·应盘；操作按钮按状态显隐；顶部「新建任务」，右上「清除已完成」 |
| `#/tasks/{id}` | 任务详情 | 统计卡 + 结果列表（全部/已盘/盘亏/盘盈 四 tab）+ 分页 + 右上「导出报表」「打印报表」 |
| `#/assets` | 资产台账 | 搜索框 + 部门/类型筛选 + 表格分页 + 「导出台账」 |
| `#/import` | 导入 | 拖拽/点击上传区，选完立即上传，回显成功/覆盖/新增/失败 + 可展开错误行 |
| `#/report/{id}` | 报表预览 | A4 横向白底黑字，`@media print` 专用样式，「打印 / 另存为 PDF」调 `window.print()` |

窄屏（手机/平板打开同一 IP）自动降级：大屏三栏堆成单列，表格横向滚动。不另做移动端页面。

### 7.2 大屏版式（V1）

```
┌──────────────────────────────────────────────────────────────┐
│ 📦 资产管家 · 盘点看板                  ● 已连接  最后更新 14:32:05 │
├──────────────────────────────────────────────────────────────┤
│ 资产总数 1284 │ 部门 12 │ 类型 8 │ 进行中 2 │ 今日已盘 150      │
├──────────────┬───────────────────────────────────────────────┤
│ 当前任务      │ 部门分布（横向条形 · Top 10 + 其他）             │
│  ◯ 75%       │                                               │
│  已盘/盘亏/盘盈├───────────────────────────────────────────────┤
│ 其他任务列表   │ 设备类型分布（横向条形 · Top 10 + 其他）         │
├──────────────┴───────────────────────────────────────────────┤
│ 数据来源：本机 PDA 数据库 · 每 5 秒自动刷新   最近扫描 …          │
└──────────────────────────────────────────────────────────────┘
```

配色两套，同一套 CSS 变量、切换 `data-theme="dark|light"`，不维护两份样式：

| 主题 | 底色 | 主色 | 适用 |
|---|---|---|---|
| **浅色 · 蓝白（默认）** | `#F7FAFD → #EEF3F9` + 白卡片 | `#1565C0` | 明亮会议室、投影仪、打印截图 |
| 深色 · 夜蓝 | `#080D18` + `#101A2B` 面板 | `#38BDF8` | 挂墙大屏、暗光环境 |

### 7.3 工程结构

```
app/src/main/assets/web/
├── index.html
├── css/
│   ├── tokens.css     # CSS 变量：颜色/字号/间距，含 [data-theme=dark] 覆盖
│   ├── base.css       # reset + 通用排版
│   ├── app.css        # 组件：卡片/按钮/表格/状态标签/弹窗/toast/分页
│   ├── dashboard.css  # 大屏专用栅格
│   └── print.css      # 报表打印样式
└── js/
    ├── api.js         # fetch 封装：统一解析 {error:{code,message}}、超时、下载
    ├── router.js      # hash 路由 + 视图挂载
    ├── ui.js          # toast / 确认弹窗 / 分页器 / 空态
    ├── dashboard.js
    ├── tasks.js
    ├── assets.js
    ├── import.js
    └── report.js
```

无构建步骤，ES module 直接 `<script type="module">`。

**调试口子**：`StaticAssets` 在 debug 构建下优先返回 `getExternalFilesDir()/web/` 中的同名文件，
便于现场用 `adb push` 替换 css/js 验证而不必重新打包。release 构建只读 assets。

---

## 8. 错误处理与并发

### 8.1 错误处理

- `ApiException(code, message, httpStatus)` → 统一 JSON 信封；前端按 `code` 分支、`message` 直接展示。
- 未捕获异常 → 500 `INTERNAL`，`Log.e` 记录，**日志走现有脱敏习惯**（`ExcelExporter.redactTail`），不打印明文固资编号/EPC。
- 请求体上限 20MB，超限 413。
- 导出临时文件 `finally` 删除；`WebServerService.onDestroy` 清空 `cacheDir/web-export/` 与 `web-upload/`。
- 端口 8080–8089 全被占 → 对话框明确报错，不静默失败。
- IP 变化（WiFi ↔ 热点切换）→ 对话框每次打开时现算，通知栏文案随网络变化刷新。

### 8.2 并发

| 场景 | 策略 |
|---|---|
| 请求线程 | `ThreadPoolExecutor(core=2, max=4)`，队列满返回 503 `TOO_MANY_REQUESTS` |
| 同一任务重复导出 | `ConcurrentHashMap<Long, Boolean>` 在飞标记，重复请求 409 |
| 同时多个导入 | `AtomicBoolean` 串行化，第二个返回 409 —— 并发 upsert 同一 epc 会互相覆盖 |
| 大屏轮询 | 1 秒内重复请求返回缓存快照 |

---

## 9. 测试

沿用仓库现有三层风格（JUnit4 + Robolectric + Room-testing + 源码文本守卫）。

| 测试 | 覆盖 |
|---|---|
| `WebRouterTest` | 路径匹配、方法不匹配 405、未知路径 404、`/api` 前缀隔离 |
| `DashboardApiTest` | in-memory Room 造数 → Top10+「其他」合并、空库不报错、无当前任务时 `activeTask=null` |
| `DashboardCacheTest` | 1 秒内二次请求命中缓存 |
| `TaskApiTest` | 增删改查走 `TaskService`；`resume` 设 `currentTaskId`、`delete` 命中当前任务时 `session.clear()` |
| `TaskServiceTest` | 六个操作的事务结果 —— 复用现有 9 个守卫测试的断言点，证明抽取后行为不变 |
| `ImportApiTest` | 用 `assets/import_template.xlsx` 真实字节流，断言入库/覆盖/错误行 |
| `ExportApiTest` | `Content-Type`、中文文件名走 RFC 5987 `filename*=UTF-8''…`、临时文件已删除 |
| `StaticAssetsTest` | MIME 映射、`../` 路径穿越拒绝 |
| `WebServerServiceTest`（Robolectric） | `startForeground` 被调用、stop 后端口可重新绑定 |

**回归底线**：现有 257 个用例必须全绿，尤其
`TaskListViewModelTransactionTest`、`DeleteTaskRollbackOrderGuardTest`、`CompleteTaskSnapshotTest`、
`CreateTaskUsesFreshRowTest`、`ClearCompletedKeepsAssetsTest`。

**不加任何数据库迁移** —— 不改实体，`AppDatabase.version` 仍为 `1`。

---

## 10. 交付清单

| 文件 | 变更 |
|---|---|
| `app/build.gradle` | + `org.nanohttpd:nanohttpd:2.3.1` |
| `AndroidManifest.xml` | + `INTERNET` / `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` / `POST_NOTIFICATIONS`，+ `WebServerService` |
| `web/**`（新增 12 个类：7 个基础设施 + 5 个 API） | 服务器、路由、API |
| `service/TaskService.java`（新增） | 任务事务 |
| `ui/task/TaskListViewModel.java` | 内部委托 `TaskService`，公开 API 不变 |
| `data/dao/AssetDao.java` | + `countByDeptGrouped()` / `countByCategoryAll()` |
| `data/dao/InventoryTaskDao.java` | + `findAll()` 同步版 |
| `util/SqlLikeEscaper.java`（新增） | 从 `AssetOverviewViewModel` 提取关键词转义，App 与 Web 共用 |
| `ui/asset/AssetOverviewViewModel.java` | 改用 `SqlLikeEscaper` |
| `assets/web/**`（新增） | 前端静态页 |
| `ui/setting/WebServerDialog.java` + layout + strings + `popup_settings.xml` | 开关与地址展示 |
| `README.md` | 新增章节 |

`proguard-rules.pro` **不需要**加 keep —— NanoHTTPD 零反射，`WebServerService` 由 Manifest 引用会被自动保留。

---

## 11. 已知限制

1. **无认证** —— 同网段任何人都能删任务、上传 Excel 覆盖资产。已确认接受；网页端对删除/清除已完成做
   「输入任务名」二次确认，仅是误操作防护，**不是安全边界**。
2. 国产 ROM 的后台清理可能杀掉前台服务，需引导用户加自启动白名单。
3. 改前端静态资源要重新打包 APK（debug 构建可用外部目录覆盖临时调试）。
4. 网页端资产字段只读，编辑仍归 PDA。
5. 只支持单机访问 —— 一台 PDA 一个服务，多台 PDA 之间不共享数据。

---

## 12. 不做的事（YAGNI）

- 不做用户体系 / 登录 / 权限分级。
- 不做 HTTPS（局域网内自签证书浏览器会拦截，收益低于成本）。
- 不做多 PDA 数据同步或中心化后端。
- 不做服务端 PDF 生成（改用浏览器打印）。
- 不做 SSE / WebSocket 实时推送（5 秒轮询已满足看板需求）。
- 不做网页端资产字段编辑、盘盈补全、扫描触发。

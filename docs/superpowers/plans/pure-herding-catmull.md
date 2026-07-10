# 三件重构 + Excel 新格式切换

## Context

PDA 资产盘点 app 当前布局不匹配新业务诉求，需要一次性落地四件事：

1. **导入入口换位**：把"导入资产"从底部 nav 第一个 tab 移到右上角齿轮菜单下，腾出位置给真正的"任务"管理。
2. **任务 tab 替代导入 tab**：底部"导入"tab 改为"任务"tab，按 HTML 设计稿（`资产管理系统HTML界面.html`）支持按领用部门创建盘点任务、查看进度、开始/暂停盘点。
3. **扫描页加盘盈卡片**：HTML 设计稿扫描页是 4 卡片横向滚动（`overflow-x:auto; min-width:142px`），当前 Android 只有 3 卡，需要把第 4 张"盘盈"加进去。
4. **Excel 格式切换**：用户提供的 `（全）华夏资产导入测试.xlsx` 是 8 列精简版（`序号|设备类型|设备型号|序列号|固资编号|领用部门|领用人|盘点日期`），跟旧 9 列格式不一样。**旧格式作废、旧数据已清空**，可直接 destructive 迁移。

预期效果：进入 app → 齿轮看到「功率设置/导入资产」→ 导入新 8 列 xlsx → 任务 tab 按部门建任务 → 扫描页 4 卡片显示盘点进度 → 结果页导出。

## 决策摘要

### Asset 实体字段精简（13 → 11）
- **保留**：`id`, `taskId`, `excelRowIndex`, `epc`, `assetType`, `assetModel`, `rawRowJson`, `inventoryTime`
- **新增（对齐新表）**：`serialNumber`（序列号）/ `custodian`（领用人）/ `dept`（领用部门）
- **永久删除（真删，DB 列也 DROP）**：`assetName` / `org`（旧"所属机构"=机构代码，非部门）/`detailedLocation` / `placementLocation` / `assetStatus`

### InventoryTask 加 dept
- 新增 `@ColumnInfo(name = "dept") public String dept;`，`NULL = 不限`。Spinner 选项来自 `Asset.dept` DISTINCT（DB 字段名是 dept 是因为新 Excel 表叫"领用部门"，不再用旧 Entity 里的"org"或 HTML 设计稿里的"归口部门"——统一口径"领用部门"）。
- `status` 保留 `IN_PROGRESS` / `COMPLETED`。HTML 里有"已暂停"状态，**MVP 先不加 PAUSED**，等真有需要再加。

### Room 迁移：version 升 3 + 走 fallbackToDestructiveMigration
- `InventoryApp.java:48` 已配 `.fallbackToDestructiveMigration()`
- 只需把 `AppDatabase` 的 `version` 从 2 改 3，Room 自动 DROP 全表 + 按新 @Entity 重建
- **不写 MIGRATION_2_3** — 让 Room 框架处理，避免手写 DROP 漏字段
- `MIGRATION_1_2` 仍在（但升级到 v3 时不经过它）

### HeaderSpec 8 列
```java
public static final List<String> REQUIRED = Arrays.asList(
        "序号", "设备类型", "设备型号", "序列号",
        "固资编号", "领用部门", "领用人", "盘点日期");
```
「盘点日期」在表头但解析后**丢弃**（按用户指示：源文件该列应为空；遇到非空值=历史快照，不入库）。

### ExcelImporter 字段映射
```java
a.serialNumber = pick(cells, headers, "序列号");
a.custodian   = pick(cells, headers, "领用人");
a.dept        = pick(cells, headers, "领用部门");
// 移除: a.assetName / a.org / a.detailedLocation / a.placementLocation / a.assetStatus
// "盘点日期" → 不写任何字段
```

### InventorySession 复用
`InventoryApp.session` 已有 `currentTaskId` 概念，任务 tab 直接读写。**InventoryTask 不引入 IN_PROGRESS 的「当前任务」概念**（原本由 ImportFragment 创建），改用「选中任务」机制：
- 全局 `currentTaskId` 仍指向 IN_PROGRESS 那个
- 任务列表可显示多个任务（IN_PROGRESS / COMPLETED / 旧的）
- 点击任务卡片 → 切到该任务：调用 `app.session.setCurrentTaskId(taskId)`，再 `nav.setSelectedItemId(R.id.nav_scan)`

---

## 实施步骤（按依赖顺序）

### Step 1：数据层
| # | 文件 | 操作 |
|---|---|---|
| 1.1 | `app/src/main/java/com/huaxia/inventory/data/entity/Asset.java` | 删 5 字段、加 3 字段 |
| 1.2 | `app/src/main/java/com/huaxia/inventory/data/entity/InventoryTask.java` | 加 `dept TEXT` |
| 1.3 | `app/src/main/java/com/huaxia/inventory/data/AppDatabase.java` | `version: 2 → 3` |

Room 看到 version=3 且没有 MIGRATION_2_3，自动调 `fallbackToDestructiveMigration()` → DROP 全表 → 按新 @Entity 注解创建 schema。无需手写 DDL。

### Step 2：解析层
| # | 文件 | 操作 |
|---|---|---|
| 2.1 | `excel/HeaderSpec.java` | REQUIRED 换新 8 列 |
| 2.2 | `excel/ExcelImporter.java` | 字段映射改：删 5 行赋值、加 3 行赋值、盘点日期不写 |
| 2.3 | `excel/ExcelExporter.java` | **无需改**：从 rawRowJson 读原始列名，`RESULT_COLUMNS = ["盘点状态","盘点时间"]` 拼到尾部；"盘点日期" 过滤在 `ResultFragment.java:198-202`，本文件不动 |
| 2.4 | `excel/HeaderSpec.java` | `epcColumnIndex()` 仍然返回"固资编号"列下标 |

### Step 3：扫描页加盘盈卡片
| # | 文件 | 操作 |
|---|---|---|
| 3.1 | `res/layout/fragment_scan.xml` | 把 line 23-52 的 `LinearLayout horizontal` 包一层 `HorizontalScrollView`，容纳 4 张卡（保持 `min-width=142dp` 贴 HTML） |
| 3.2 | `res/values/strings.xml` | 加 `scan_stat_extra="盘盈"` + `scan_stat_extra_sub="待确认"` |
| 3.3 | `ui/scan/ScanFragment.java` | line 35 加 `extraNumber/extraSub` 字段；line 78-79 后加 `bindStatCard(..., R.string.scan_stat_extra, R.color.statusExtra, "待确认")`；line 83/86 后加 extraNumber/extraSub find；line 110-114 后加 click → `ResultTab.EXTRA`（已存在枚举，见 `ui/result/ResultTab.java:4`）；observer line 142-156 加 `extraNumber.setText(stats.extra)` + `extraSub.setText(stats.extra > 0 ? "待确认" : "OK")` |

数据源已就绪：`ScanViewModel.Stats.extra`（`ScanViewModel.java:38`）+ `ScanAggregate.extraCount`（`ScanRecordDao.java:57`）+ `ResultTab.EXTRA` 枚举（`ResultTab.java:4`）。✅ 不需要新 DAO。

### Step 4：齿轮菜单下放导入
| # | 文件 | 操作 |
|---|---|---|
| 4.1 | `res/menu/menu_main.xml` | 加 `<item android:id="@+id/action_settings" android:title="操作" android:icon="@drawable/ic_settings" app:showAsAction="always"/>`，**移除** `action_power_setting`（合并进"操作"弹窗） |
| 4.2 | `MainActivity.java` | `onOptionsItemSelected` 加 case `R.id.action_settings`：弹 `AlertDialog`（标题"操作" + items "功率设置" / "导入资产"），items 触发各自 action |
| 4.3 | `MainActivity.java` | 提取"导入资产"逻辑：把 `ImportFragment.filePicker` (line 33-41) 和 `ImportViewModel.commitToTask` 调用搬到 `MainActivity` 或新建 helper `ImportAssetDelegate`，让齿轮点击后能复用。`ImportViewModel` 实例由 Activity 范围持有（AndroidViewModel in Application context 或 Activity context） |
| 4.4 | `strings.xml` | 加 `menu_settings="操作"`、`menu_action_power="功率设置"`、`menu_action_import="导入资产"` |

### Step 5：任务 tab（替代原导入 tab）
| # | 文件 | 操作 |
|---|---|---|
| 5.1 | 新建包 `com.huaxia.inventory.ui.task` | 4 个新类 + 3 个 layout |
| 5.1.1 | `TaskListFragment.java` | 顶部 3 张摘要小卡（总任务数/进行中/已完成 — 仿 HTML `task-summary-row`），下方 RecyclerView 列任务卡片；底部双按钮"创建任务"/"清除已完成" |
| 5.1.2 | `TaskListViewModel.java` | `LiveData<List<InventoryTask>>` 接 `db.inventoryTaskDao().findAll()`（已有）；统计 derived；创建任务 / 切任务 / 删任务 methods |
| 5.1.3 | `TaskAdapter.java` | task 卡片 RecyclerView Adapter；每项：name + status tag + dept + createdDate + total/checked + progressbar + actions (开始/暂停/查看详情/删除，status=COMPLETED 时只显示查看) |
| 5.1.4 | `CreateTaskDialogFragment.java` | DialogFragment；表单：任务名（必填） + **领用部门**（Spinner，label 用现有 `R.string.label_borrow_dept`，从 `db.assetDao().distinctDept()` 动态填，加个"不限制（全部资产）"占位） + 备注；下方实时显示"将盘点 N 台"预览（`previewDeptAssets` 仿 HTML）；保存时插 InventoryTask |
| 5.2 | `res/layout/fragment_task_list.xml` | 顶部 3 卡 summary + RecyclerView + 底部双按钮 |
| 5.3 | `res/layout/view_task_card.xml` | 任务卡片 |
| 5.4 | `res/layout/dialog_create_task.xml` | 新建任务弹窗 |
| 5.5 | `res/menu/bottom_nav.xml` | `nav_import` 重命名为 `nav_task`，title 改为"📋 任务" |
| 5.6 | `MainActivity.java` | `R.id.nav_import` 引用全部改 `R.id.nav_task`；对应 `new ImportFragment()` 改 `new TaskListFragment()` |
| 5.7 | `strings.xml` | `nav_import="📥 导入"` → `nav_task="📋 任务"`；加任务相关 strings |
| 5.8 | 新 DAO 方法 | `InventoryTaskDao.java` 加 `findDistinctDept()` 或 `AssetDao.java` 加 `distinctDept()`。建议加到 `AssetDao`：`@Query("SELECT DISTINCT dept FROM Asset WHERE dept IS NOT NULL ORDER BY dept") List<String> distinctDept()` |
| 5.9 | `ui/import_/ImportFragment.java` | **删除**（任务 tab 已不再承担导入职能）。但 `ImportViewModel.java` 保留给齿轮导入复用 |

### Step 6：AssetDetailDialog + AssetResultAdapter 适配
| # | 文件 | 操作 |
|---|---|---|
| 6.1 | `res/layout/dialog_asset_detail.xml` | 删 5 个旧字段的 TextView（assetName/org/detailedLocation/placementLocation/assetStatus）；保留 epc/type/model/serialNumber/dept/custodian + inventoryTime。**全部业务字段变 EditText**（除 inventoryTime 只读） |
| 6.2 | `ui/result/AssetDetailDialog.java` | - line 43-56 改成 6 个 EditText + 1 个 inventoryTime 只读 TextView<br>- 业务字段：设备类型/设备型号/序列号/固资编号/领用部门/领用人 = 6 个 EditText<br>- 6 个字段初值从 `Asset.*` 字段读取（不再走 rawRowJson）<br>- 保存时写回 `Asset.assetType/assetModel/serialNumber/epc/dept/custodian` + 同步 rawRowJson<br>- **固资编号（epc）保存时调 `EpcNormalizer.normalize()` 校验**：16/17/24 位十进制通过；其他值拒绝并 Toast 提示<br>- inventoryTime 仍走 ResultFragment.onAssetDetailSaved 算（不改） |
| 6.3 | `res/layout/view_asset_row.xml` | 4 个字段 code/name/model/org → 4 个 code/serialNo/custodian/dept |
| 6.4 | `ui/result/AssetResultAdapter.java` | line 47-50：`a.epc / a.assetType / a.serialNumber / a.dept` |

⚠️ 删除的 5 个字段如果还有写 ReadExcel 的代码路径引用，全部改为新字段。

### Step 7：清理依赖
| # | 文件 | 操作 |
|---|---|---|
| 7.1 | 全局 grep | `asset.assetName` / `a.org` / `detailedLocation` / `placementLocation` / `assetStatus` 残留引用全部清除 |
| 7.2 | 全局 grep | `R.id.nav_import` 残留全部改 `R.id.nav_task` |

---

## 关键文件清单

### 新增
- `app/src/main/java/com/huaxia/inventory/ui/task/TaskListFragment.java`
- `app/src/main/java/com/huaxia/inventory/ui/task/TaskListViewModel.java`
- `app/src/main/java/com/huaxia/inventory/ui/task/TaskAdapter.java`
- `app/src/main/java/com/huaxia/inventory/ui/task/CreateTaskDialogFragment.java`
- `app/src/main/res/layout/fragment_task_list.xml`
- `app/src/main/res/layout/view_task_card.xml`
- `app/src/main/res/layout/dialog_create_task.xml`

### 修改（约 18 个文件）
**数据层**：Asset.java、InventoryTask.java、AppDatabase.java、AssetDao.java、ExcelImporter.java、HeaderSpec.java

**UI 层**：
- `MainActivity.java`（菜单挂载、nav id 切换）
- `ui/scan/ScanFragment.java`、`fragment_scan.xml`
- `ui/result/AssetDetailDialog.java`、`dialog_asset_detail.xml`
- `ui/result/AssetResultAdapter.java`、`view_asset_row.xml`

**资源**：`menu_main.xml`、`bottom_nav.xml`、`strings.xml`

**删除**：`ui/import_/ImportFragment.java`（保留 ImportViewModel.java）

### 删除字段（DB 列也删）
- Asset.assetName / Asset.org / Asset.detailedLocation / Asset.placementLocation / Asset.assetStatus

---

## 验证

### 编译检查
```bash
gradle :app:compileDebugJavaWithJavac
gradle :app:lintDebug
```

### 端到端流程
1. **卸载重装** app（清掉 v2 schema DB）
2. **菜单路径**：进入 app → 右上齿轮 → 看到"操作" → 弹出"功率设置 / 导入资产"
3. **导入验证**：选 `（全）华夏资产导入测试.xlsx`（3400 行）→ 成功导入 → 看到"已导入 3400 条"
4. **任务 tab**：
   - 默认进入"任务"页（底部第一个 tab）
   - 顶部 3 张摘要：总任务 1 / 进行中 1 / 已完成 0
   - 看到 1 张任务卡，dept=营业部（按时间排序最新）
   - 点"+ 创建任务"→ 弹窗 → 任务名 "研发部Q3" + 部门下拉动态列出（营业部/法律合规部/...等）→ 选"研发部"→ 预览"该部门 N 台设备"→ 确认
   - 任务列表加一卡
5. **扫描页**：
   - 4 张卡片横向可滚：在库/已盘/异常/盘盈
   - 扫一张 EPC 标签 → 已盘 +1；如果 EPC 不在导入表里 → 盘盈 +1
6. **结果页**：
   - 点"盘盈"卡片 → 跳到结果页且筛选 EXTRA tab（已有逻辑）
   - 点某行 → 弹详情 → 显示 6 个业务字段（类型/型号/序列号/固资编号/部门/领用人，全可编辑）+ inventoryTime 盘点时间（只读）+ 盘点状态 tag
   - 导出 xlsx → 打开检查列结构（应包含固资编号/设备类型/领用部门等 7 列原表头 — 已剔除"盘点日期" + 「盘点状态/盘点时间」2 列 = 共 9 列）

### 数据库结构验证
```bash
adb shell run-as com.huaxia.inventory sqlite3 databases/huaxia-inventory.db ".schema Asset"
```
应该看到 **只有** 新字段（serialNumber/custodian/dept），不出现 assetName/org/detailedLocation/pl-location/assetStatus。

### 旧数据兼容性
不验证（用户已确认清空）

---

## 关键决策备注

1. **任务状态不引入 PAUSED**：HTML 里有但 MVP 简化处理，先不实现。如果用户后期要，再加 enum 值 + UI 按钮（工作量 ≤ 30 行）。

2. **InventoryTask 不再存 importedAt-only 单任务**：现在变成多任务列表，但所有任务来自 `db.inventoryTaskDao().findAll()`，**AppDatabase 中仍只有一个 IN_PROGRESS 表示"当前正在使用的"**。切换任务就是改 `app.session.currentTaskId` 然后进 ScanFragment。

3. **AssetDetailDialog 全字段可编辑**：
   - 业务字段 6 个全部 EditText：设备类型 / 设备型号 / 序列号 / 固资编号 / 领用部门 / 领用人
   - 盘点时间（inventoryTime）仍只读，由系统自动填充（首次扫描时间或当前时间）
   - 初值读 `Asset.*` 字段，不再走 rawRowJson
   - 保存时写回 `Asset` 字段 + 同步 rawRowJson
   - **固资编号编辑触发 `EpcNormalizer.normalize()` 校验**：16/17/24 位通过；其他值拒绝并 Toast

4. **导出列结构保持原样**：
   - `ExcelExporter.RESULT_COLUMNS = ["盘点状态", "盘点时间"]`（`ExcelExporter.java:23-24`）—— 导出时尾部固定两列
   - `ResultFragment.doExport()`（`ResultFragment.java:198-202`）从 rawRowJson 读原表头时，**过滤掉"盘点日期"**（`if (!"盘点日期".equals(k))`）—— 这是 Excel 的源 header 过滤，不影响 Asset 实体
   - 所以导出的 xlsx 列结构 = 原 Excel 列（不含"盘点日期"）+ 「盘点状态」+ 「盘点时间」
   - 「盘点时间」列必须有值：来自 `Asset.inventoryTime`（`ExcelExporter.java:143-145`）。已有逻辑 OK。
   - 行为**完全保留**，不需要改任何代码

5. **风险点**：version 升 3 走 fallbackToDestructiveMigration，升级期旧数据全部丢失（Room 框架自处理，不需手写 DROP）。已在用户确认范围（"开发阶段，数据库和 app 都可以删"）。

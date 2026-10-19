# 固定资产盘点 (Huaxia Inventory)

Android RFID 资产盘点应用，运行在 UROVO 工业 PDA 上。

## 功能

- 从 Excel 导入资产清单（8 个必需列：序号 / 设备类型 / 设备型号 / 序列号 / 固资编号 / 领用部门 / 领用人 / 盘点日期；重复固资编号按行覆盖更新）
- 盘点任务管理：创建（可按领用部门限定、可填备注，备注显示在任务卡片）/ 暂停 / 恢复 / 完成任务 / 删除（资产退回库存池）/ 清除已完成；已完成任务「查看详情」→ 扫描页只读查看统计（切换 扫描/结果 tab 保持该任务，可再下钻结果页看列表 / 导出，不可编辑）
- 编辑的草稿 / 提交语义：进行中或暂停任务的字段编辑为**草稿**（不改主数据）；**完成任务 = 提交**（编辑值成为主数据）；**删除 / 重置任务 = 回滚**到导入原值，并清盘点时间
- RFID 扫描标签，实时匹配固资编号（UHF 读写器）
- 手动盘点：输入固资编号查询，或摄像头扫条码（无扫码枪设备上的兜底）
- 统计 已盘 / 盘亏 / 盘盈；盘盈（未匹配标签）可补全资产信息
- 结果页按 全部 / 已盘 / 盘亏 / 盘盈 筛选 + 分页；盘盈支持单条删除
- 导出盘点结果 Excel（保留原表头 + 追加 盘点状态 / 盘点时间 + 应盘/已盘/盘亏/盘盈 统计行）
- 重置扫描进度：清扫描记录 + 回滚本次编辑的字段与盘点时间（保留任务与资产），可原地重扫

## 技术栈

- Java 11 / AndroidX (minSdk 26, targetSdk 34, compileSdk 34)
- Single Activity + BottomNavigationView（📋 任务 / 🔍 扫描 / 📊 结果 三 tab）+ 若干 Fragment / Dialog
- Room 2.6（本地数据库，当前 schema v11）
- Apache POI 5.2.5（Excel 解析 / 流式写出）
- Jackson 2.17（JSON）
- CameraX 1.3 + ML Kit Barcode（手动盘点页的摄像头扫码兜底）
- UROVO RFID SDK (rscja `DeviceAPI_ver20230301_release.aar`，见 `app/libs/`)
- JUnit 4 + Robolectric + Mockito + Room-testing（单元测试）

## 项目结构

参见 [docs/superpowers/specs/2026-06-10-rfid-asset-inventory-design.md](docs/superpowers/specs/2026-06-10-rfid-asset-inventory-design.md) § 3.2.

简要:
```
app/src/main/java/com/huaxia/inventory/
├── data/              # Room 实体 + DAO + 数据库 + 迁移
├── rfid/              # RFID SDK 封装
├── barcode/           # 条码：摄像头(CameraX+ML Kit) / 硬件解码
├── excel/             # Excel 导入/导出
├── session/           # 任务状态共享
├── ui/
│   ├── task/          #   任务列表
│   ├── scan/          #   扫描
│   ├── manual/        #   手动盘点（输入 / 摄像头扫码）
│   ├── result/        #   结果（含资产/盘盈详情弹窗）
│   ├── import_/       #   导入（右上角齿轮菜单弹窗）
│   └── setting/       #   功率设置弹窗
└── util/              # 工具类
```
底部导航三 tab：📋 任务 / 🔍 扫描 / 📊 结果（导入已移入右上角齿轮菜单）。

## 构建

```bash
# 一次性: 生成 Gradle wrapper (需要先安装 Gradle 8.x)
gradle wrapper --gradle-version 8.2

# 在 local.properties 配置 SDK 路径
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 编译
./gradlew :app:assembleDebug

# 安装到设备
./gradlew :app:installDebug

# 启动
adb shell am start -n com.huaxia.inventory/.MainActivity
```

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

当前全量单测 **231 个用例全部通过**（改代码后请重跑此命令确认）。除常规行为测试（`EpcNormalizer` / `StatusDerivation` / `ExcelImporter` / `ExcelExporter` / `HeaderSpec` / DAO / `RfidController` 等）外，仓库大量使用两类防回归守卫：
- **源码文本守卫**：反射式提取方法体 + 断言，钉住事务边界、生命周期守卫、迁移版本、索引声明等不变量；
- **Robolectric 行为测试**：验证 detach 后回调、旋转时弹窗关闭等运行时行为。

## 部署到 UROVO PDA

1. 启用 USB 调试
2. `adb connect <pda-ip>:5555`
3. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
4. 启动 App，按 PDA 扫描枪按键验证扫描功能

## 实施记录

- 实施计划: [docs/superpowers/plans/2026-06-10-rfid-asset-inventory.md](docs/superpowers/plans/2026-06-10-rfid-asset-inventory.md)
- 设计文档: [docs/superpowers/specs/2026-06-10-rfid-asset-inventory-design.md](docs/superpowers/specs/2026-06-10-rfid-asset-inventory-design.md)
- 冒烟测试: [docs/smoke-test-2026-06-10.md](docs/smoke-test-2026-06-10.md)

## 已知限制

- release 签名凭据（`local.properties` / `app/keystore/`）随仓库跟踪，属交付包的一部分 — 依赖外部分发渠道管控
- DB 迁移链存在历史缺口 2→3→4→5：仅当已有这些旧版本号的数据时才会退化走 destructive fallback（这些版本实际未上线；v5 及以后均有正常迁移路径）
- 深色模式沿用系统 DayNight，未做专项优化
- 物理 PDA 扫描枪触发键（F1 / KEYCODE_BUTTON_R1 等）需在真机确认

## 2026-06-11 UI 重构

### 概述
全面对齐 `资产管理系统HTML界面.html` 设计稿（480px 移动端 H5 模板），三页（导入/扫描/结果）UI 资源 + 组件化重构。

### 改动范围
- 25 个 commit，5 阶段渐进式迁移
- 通用组件：`view_stat_card` / `view_tag_pill` / `view_info_card` / `view_asset_row`
- 颜色/尺寸/主题统一（圆角 12dp、状态色 #2E7D32/#C62828/#EF6C00）
- AppBar 蓝底白字"📦 资产管家"
- 底导 emoji 三 tab（📥 导入 / 🔍 扫描 / 📊 结果）
- 导入页：虚线大上传区 + tip box，去掉预览列表
- 扫描页：3 统计卡 + 3 说明卡 + OutlinedButton，去掉最近扫描列表与 FAB
- 结果页：2x2 宫格点击筛选 + 6 条/页分页，去掉 Tab/搜索框

### 新增资源
- `colors.xml`：21 项（含 13 项新增：tag*Bg/Fg, tip*Bg/Fg, statScanned, statTotal, borderDashed, bgSurface, cardCorner）
- `dimens.xml`：13 项（含 9 项新增：cardCornerRadius, cardElevation, textSizeLabel, statNumberSize, tagPaddingH/V, bottomNavHeight, appBarHeight, uploadAreaHeight）
- `attrs.xml`：StatCard / TagPill declare-styleable
- `styles.xml`：Huaxia.Card / Huaxia.Toolbar / Huaxia.StatNumber / StatLabel / StatSub
- `themes.xml`：Theme.HuaxiaInventory（蓝底白字 + 状态栏沉浸 + windowLightStatusBar=false）
- 7 个 shape drawable：bg_dashed_border / bg_tag_{blue,green,orange,red} / bg_tip_box / bg_file_info
- 4 个矢量图标：ic_import / ic_scan / ic_result / ic_export

### 新增 DAO 方法
- `AssetDao.countByCategory` / `countFound` / `countMissing` / `pageAll` / `pageFound` / `pageMissing`
- `ScanRecordDao.countExtras` / `pageExtra`

### 文件变更
- 修改：layout/activity_main, fragment_import, fragment_scan, fragment_result, menu/bottom_nav; colors/dimens/strings/themes; MainActivity.java; ScanViewModel.java; ResultViewModel.java; ScanFragment.java; ResultFragment.java; ImportFragment.java; ImportViewModel.java; AssetResultAdapter.java; AssetDao.java; ScanRecordDao.java
- 新建：view_stat_card / view_tag_pill / view_info_card / view_asset_row; menu_main; CategoryCount.java
- 删除：AssetPreviewAdapter.java / item_asset_preview.xml / RecentScanAdapter.java / item_recent_scan.xml / item_asset_result.xml

### 已知限制 / 后续迭代
- ~~`Asset.createdAt` 字段未实现，"今日新增"统计暂显示 0（占位）~~ → 2026-10-10 已实现：`Asset.createdAt`（首次导入时间）+ `AssetDao.countCreatedAfter(taskId, DateUtil.startOfToday())`，扫描页「在库设备」副标显示「+N 今日」（N = 本任务内今天首次导入的资产数）
- ~~`lastInventoryAt` 当前用 `InventoryTask.importedAt` 作代理~~ → 已改为真实的最后扫描时间（`ScanRecordDao.aggregateStats` 的 `lastScannedAt`，扫描页「最后盘点」显示它）
- ~~EXTRA 模式下 `vm.extras().observe` 是空 body~~ → 已实现 `ExtraResultAdapter`，盘盈 tab 独立渲染 + 支持补全 / 单条删除
- 深色模式未做专项优化（沿用系统 DayNight）

### 文档
- 设计文档：`docs/superpowers/specs/2026-06-11-ui-redesign-html-mockup-alignment-design.md`
- 实施计划：`docs/superpowers/plans/2026-06-11-ui-redesign-html-mockup-alignment.md`

## 验证

单元测试已就位并在本地执行通过（`./gradlew :app:testDebugUnitTest`，231 用例全绿）。真机部署前建议对扫描 / 导入导出 / 任务增删等主流程做一次回归。

## 开发者注意事项

- RFID 回调 (`onInventoryTag`) 在 SDK 内部 worker 线程，不要在回调内直接操作 UI
- 启动 RFID 扫描时 `epc` 字段必须先做归一化 (trim + 纯十进制 + 16/17/24 位三分支，详见 `EpcNormalizer.normalize`；2026-06-16 后已废弃 hex 转换)
- EPC 去重使用 `HashSet` + DB 唯一索引双重保护
- 导出 Excel 时从首个 asset 的 `rawRowJson` 重建 8 个必需列表头顺序 (序号 / 设备类型 / 设备型号 / 序列号 / 固资编号 / 领用部门 / 领用人 / 盘点日期)，并丢弃"盘点日期"列；尾部追加两列 `RESULT_COLUMNS = ["盘点状态", "盘点时间"]`
- `Asset` 有 7 个 `orig_*` "快照列"（v10）：建任务划拨时保存业务字段原值，删除/重置任务回滚（`revertEditedFields`）、完成任务丢弃（`clearSnapshotForTask`）。结果页保存走整行 `@Update`（`AssetDetailDialog.copyAsset`），**新增 Asset 持久化字段时必须同步复制 `orig_*`**，否则每次保存会静默清空快照

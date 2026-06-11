# UI 全面对齐 HTML 设计稿 — 设计文档

- 日期：2026-06-11
- 状态：待用户复审
- 范围：Android UI 资源层 + 3 个 Fragment + MainActivity，不动后端业务

## 1. 背景

`资产管理系统HTML界面.html` 是用户设计的 480px 宽移动端 H5 模板，三页（导入/扫描/结果），主色 `#1976D2`。当前 Android 工程已经按业务功能实现三页，但视觉与设计稿差异较大（卡片圆角、统计布局、底部导航图标、顶部栏等）。本次重做把 Android UI 全面对齐设计稿，保留全部现有后端逻辑（Room / RFID / Excel 导入导出）。

## 2. 目标 / 非目标

### 目标
- 视觉与设计稿一致：圆角 12dp 卡片、状态色系、虚线大上传区、3 张扫描统计卡、3 张说明卡、4 宫格结果统计、emoji 底导
- AppBar 蓝底白字显示"📦 资产管家"
- 底导三个 emoji tab（导入 / 扫描 / 结果）
- 业务行为不变：Excel 解析、RFID 扫码、统计、导出全部走现有代码路径
- 5 个独立 commit，每个 commit 后可编译

### 非目标（明确不做）
- 改变 EPC 编码 / 16 位固资编号合约
- 改 Room schema / DAO 接口语义
- 引入新的第三方 UI 库（继续用 Material Components + ConstraintLayout）
- 历史任务列表页（已在 OQ5 标记为 1.0 不做）
- 适配平板/横屏（仅竖屏 PDA 单设备）
- 深色模式专项优化（沿用 DayNight 系统切换）

## 3. 架构

### 3.1 资源层
- `res/values/`：colors / dimens / attrs / styles / themes
- `res/layout/`：3 个 fragment + 4 个 view_* 通用组件 + activity_main
- `res/drawable/`：bg_*.xml（圆角背景）+ ic_*.xml（底导矢量兜底）
- `res/menu/`：bottom_nav.xml（emoji title）+ menu_main.xml（导出按钮）

### 3.2 组件层
- `view_stat_card` 自定义属性：`number` / `numberColor` / `label` / `sub`，继承 `MaterialCardView`
- `view_tag_pill` 自定义属性：`tagText` / `tagBg` / `tagFg`，纯 `TextView` + shape 背景
- `view_info_card` 由 `MaterialCardView` + `LinearLayout` 组合，title / desc / tags 通过 `<include>` 注入
- `view_asset_row` 改造现有 `item_asset_result.xml`：固资编号（粗 14sp）+ 设备名（13sp 灰）+ 部门（12sp 淡灰）+ 右侧 tag

### 3.3 Fragment 层
- `ImportFragment`：删除 `previewRv` / `previewHeaderTv` / `AssetPreviewAdapter`
- `ScanFragment`：删除 `recentRv` / `recentHeaderTv` / `viewResultFab` / `RecentScanAdapter`；新增 3 个 stat card + 3 个 info card
- `ResultFragment`：删除 `tabs` / `searchView` / 内部 `exportBtn`；新增 4 个 stat card + 分页控件

### 3.4 ViewModel 层
- `ImportViewModel` — 业务不动
- `ScanViewModel` — 新增 `LiveData<ScanStats>` = (inStock, scanned, anomaly)
- `ResultViewModel` — 新增 `LiveData<ResultStats>` = (total, found, missing, extra) + `currentFilter` + `currentPage`

### 3.5 DAO 层
- `AssetDao` 新增：
  - `int countByStatus(@Status String status)` — 已盘/盘亏/盘盈
  - `int countCreatedAfter(long sinceMillis)` — 今日新增（用于 "+12 今日" 副标）
  - `List<CategoryCount> countByCategory()` — 按"设备类型"聚合
  - `List<Asset> paging(int offset, int limit, @Status String status)` — 分页
- `ScanRecordDao` 已有 `countDistinctEpc()`，无需新增

### 3.6 Activity 层
- `MainActivity` 加 `MaterialToolbar` + `setSupportActionBar`，导出 menu 路由到当前 `ResultFragment`

## 4. 数据流

### 4.1 统计字段来源

| 字段 | 来源 | 触发更新 |
|---|---|---|
| `inStockCount` | `AssetDao.countAll()` | Excel 导入完成 |
| `scannedCount` | `ScanRecordDao.countDistinctEpc()` | RFID 回调落库后 |
| `anomalyCount` | `max(0, inStock - scanned)` | 上述任一变化 |
| `todayAddedCount` | `AssetDao.countCreatedAfter(todayStartMillis)` | Excel 导入完成 |
| `totalCount` | `AssetDao.countAll()` | 同上 |
| `foundCount` | `AssetDao.countByStatus("FOUND")` | 状态派生完成 |
| `missingCount` | `AssetDao.countByStatus("MISSING")` | 同上 |
| `extraCount` | `AssetDao.countByStatus("EXTRA")` | 同上 |

### 4.2 状态派生规则（沿用 `StatusDerivation`）
- `FOUND` = 预期 ∩ 已扫 EPC
- `MISSING` = 预期 − 已扫
- `EXTRA` = 已扫 − 预期

### 4.3 列表过滤与分页
- 用户点击 4 宫格之一 → `ResultViewModel.setFilter(FOUND|MISSING|EXTRA|ALL)` → 重新查询
- 翻页：`ResultViewModel.loadPage(pageNum)` → `AssetDao.paging(pageNum*6, 6, filter)`
- 边界：`pageNum=0` 禁用上一页；`pageNum*6+list.size >= totalCount` 禁用下一页

### 4.4 类别聚合（扫描页 3 张说明卡）
- `inStockCard` 的 tag（电脑类 432 / 家具类 286 / 仪器类 198）来自 `AssetDao.countByCategory()`，取数量前三的类型
- `recentResultCard` 的 tag（正常 943 / 盘亏 18 / 盘盈 6）来自 `ResultStats`，与结果页 stat 共享同一数据源

## 5. 错误处理

| 场景 | 处理 |
|---|---|
| 选文件取消 | 无操作 |
| Excel 解析失败 | Toast + tip box 变红 + 文件名旁加 ❌ |
| RFID 未就绪 | `deviceStatusTv` 显示"设备未就绪"（移入"已盘点设备"卡上方） |
| 结果页空数据 | 居中 📭 "暂无数据" + "去导入" 按钮（切到导入页） |
| 分页越界 | 按钮置灰 + 不发请求 |
| 导出失败 | Snackbar 红色 "导出失败：xxx" |
| 导出成功 | Snackbar 绿色 "已导出" |

错误文案统一在 `strings.xml`：
```
err_parse_failed   "Excel 解析失败：%1$s"
err_empty_result   "暂无数据"
err_export_failed  "导出失败：%1$s"
action_goto_import "去导入"
```

## 6. 主题与样式

### 6.1 颜色 (`colors.xml`)
```
colorPrimary       #1976D2
colorPrimaryDark   #0D47A1
colorAccent        #FF9800
statusFound        #2E7D32
statusMissing      #C62828
statusExtra        #EF6C00
bgSurface          #F5F5F5
cardCorner         #FFFFFF
tagBlueBg          #E3F2FD   tagBlueFg  #1565C0
tagGreenBg         #E8F5E9   tagGreenFg #2E7D32
tagOrangeBg        #FFF3E0   tagOrangeFg#EF6C00
tagRedBg           #FFEBEE   tagRedFg   #C62828
tipBg              #E3F2FD   tipFg      #1565C0
statScanned        #1565C0   (扫描页"已盘点"数字色 / 结果页"全部"复用)
statTotal          #9C27B0   (结果页"全部设备"数字色)
```

### 6.2 尺寸 (`dimens.xml`)
```
cardCornerRadius 12dp   cardElevation 2dp   cardMargin 12dp
cardPadding      16dp   textSizeTitle 20sp  textSizeBody 14sp
textSizeLabel    12sp   statNumberSize 26sp
tagPaddingH      12dp   tagPaddingV  4dp
bottomNavHeight  56dp   appBarHeight  56dp
```

### 6.3 主题 (`themes.xml`)
- 父主题：`Theme.MaterialComponents.DayNight.NoActionBar`
- `colorPrimary` / `colorPrimaryDark` / `colorOnPrimary = white`
- `android:statusBarColor = @color/colorPrimary`
- `android:windowLightStatusBar = false`（状态栏文字白色）
- Toolbar：`Widget.MaterialComponents.Toolbar.Primary`（蓝底白字）

## 7. 布局结构

### 7.1 `activity_main.xml`
```
ConstraintLayout
 ├─ AppBarLayout (id=appBar)
 │    └─ MaterialToolbar (id=toolbar)  title="📦 资产管家"
 ├─ FrameLayout (id=fragmentContainer)  在 appBar 之下、bottomNav 之上
 └─ BottomNavigationView (id=bottomNav)  app:menu=@menu/bottom_nav
```

### 7.2 `fragment_import.xml`
```
ScrollView
 └─ LinearLayout (vertical, padding 16dp)
     ├─ TextView "📥 导入资产数据"  textSizeTitle
     ├─ FrameLayout (height 200dp, bg=bg_dashed_border)  clickable → 选文件
     │    ├─ ImageView 📂 54sp
     │    ├─ TextView "拖拽或 点击浏览"
     │    └─ TextView "支持 .xlsx / .xls" 12sp
     ├─ LinearLayout fileInfoRow (gone, bg #f5f5f5, radius 8dp)
     │    ├─ TextView "已选择：xxx.xlsx"
     │    └─ TextView "清除" 红字
     ├─ MaterialButton (primary, full-width) "开始导入"
     └─ LinearLayout tipBox (bg #E3F2FD, radius 8dp)  "💡 ..."
```

### 7.3 `fragment_scan.xml`
```
ScrollView
 └─ LinearLayout (vertical, padding 16dp)
     ├─ TextView "🔍 扫描管理"  textSizeTitle
     ├─ LinearLayout (horizontal, 3 等分, 间距 10dp)
     │    ├─ view_stat_card  在库设备  #2E7D32 1284  sub="+12 今日"
     │    ├─ view_stat_card  已盘点    #1565C0 967   sub="75.3%"
     │    └─ view_stat_card  异常      #C62828 24    sub="需处理"
     ├─ view_info_card  📍 在库设备
     │    desc + tags=[电脑类 432, 家具类 286, 仪器类 198]
     ├─ TextView deviceStatusTv (12sp 灰) "设备状态：就绪"  ← 仅"已盘点设备"卡上方
     ├─ view_info_card  📌 已盘点设备
     │    desc + sub="⏱ 最后盘点：xxxx"
     ├─ view_info_card  📋 最近盘点结果
     │    desc + tags=[正常 943, 盘亏 18, 盘盈 6]
     └─ MaterialButton (secondary, outline) "➕ 开始盘点"
```

### 7.4 `fragment_result.xml`
```
LinearLayout (vertical)
 ├─ TextView "📊 盘点结果" (padding 16dp)
 ├─ GridLayout (2×2, 间距 10dp)
 │    ├─ view_stat_card  全部设备  #9C27B0 1512 clickable
 │    ├─ view_stat_card  已盘      #2E7D32 1486 clickable  selected default
 │    ├─ view_stat_card  盘亏      #C62828 18   clickable
 │    └─ view_stat_card  盘盈      #EF6C00 8    clickable
 ├─ RecyclerView resultRv (weight 1)
 └─ LinearLayout pagination
      ├─ TextView "共 1,512 条，显示 1-6"
      └─ Buttons: "上一页" / "下一页"(primary)
```

## 8. 测试

### 8.1 保持通过（既有单测）
- EpcNormalizerTest / EpcCodecTest / StatusDerivationTest
- HeaderSpecTest / ExcelImporterTest / ExcelExporterTest
- AssetDaoTest / ScanRecordDaoTest
- RfidControllerTest

### 8.2 新增
- `viewmodel/ScanViewModelTest`：mock DAO，验证 3 个 stat + anomaly 计算
- `viewmodel/ResultViewModelTest`：mock DAO，验证 4 个 stat + filter 切换 + 分页边界
- `dao/AssetDaoPagingTest`：Robolectric 验证 `paging()` LIMIT/OFFSET 与 filter 组合

### 8.3 不在单测范围
- 渲染像素一致性 / emoji 字体 / PDA 按键 → 真机验证
- Material 样式 lint 校验即可

## 9. 实施阶段（5 个独立 commit）

| 阶段 | 主要文件 | 可验证产物 | 估 diff |
|---|---|---|---|
| 1 基建 | colors/dimens/themes/attrs/styles + activity_main + view_*_card + view_tag_pill + view_info_card + view_asset_row + bg_*.xml + ic_*.xml + menu_main + MainActivity + strings | AppBar 蓝底白字 + 底导 emoji 可见 + 3 个占位 Fragment 渲染通用组件 | ~600 |
| 2 导入页 | fragment_import + ImportFragment + 删除 AssetPreviewAdapter + strings | 虚线大上传区 + tip box + 走通 Excel 解析 | ~250 |
| 3 扫描页 | fragment_scan + ScanFragment + ScanViewModel + AssetDao 新增 countByCategory + 删除 RecentScanAdapter | 3 统计 + 3 说明卡 + 扫码业务跑通 | ~400 |
| 4 结果页 | fragment_result + ResultFragment + ResultViewModel + AssetDao 新增 paging/countByStatus + AssetResultAdapter | 4 宫格点击筛选 + 6 条/页分页 + 导出 | ~450 |
| 5 集成 | bottom_nav 改造 + MainActivity 导出 menu 路由 + 真机验证 | 整套 UI 与设计稿一致 | ~150 |

**总计约 1850 行 diff**，每个阶段后 `./gradlew :app:assembleDebug` 必须能编过。

## 10. 风险与回退

| 风险 | 缓解 | 回退 |
|---|---|---|
| emoji 在 PDA ROM 显示为方块 | 矢量 `ic_import/scan/result` 兜底 | 移除 title 中 emoji，仅留矢量图 |
| 类别聚合 SQL 性能 | 资产量千级以内，DAO 加 `idx_category` | 首版可写死三类，不调 DAO |
| 分页与 stat 数据不同步 | 4 stat 来自同一 `LiveData<ResultStats>` | 不可能不同步（强约束） |
| 阶段 1 之后 UI 半新半旧 | 每个阶段独立 commit | `git revert <sha>` 即回退 |

## 11. 关联文档

- 现有设计稿：`资产管理系统HTML界面.html`（项目根）
- 现有业务设计：`docs/superpowers/specs/2026-06-10-rfid-asset-inventory-design.md`
- 实施计划待写：`docs/superpowers/plans/2026-06-11-ui-redesign-html-mockup-alignment.md`

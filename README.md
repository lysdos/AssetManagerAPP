# 固定资产盘点 (Huaxia Inventory)

Android RFID 资产盘点应用，运行在 UROVO 工业 PDA 上。

## 功能

- 从 Excel 导入资产清单（8 个必需列：序号 / 设备类型 / 设备型号 / 序列号 / 固资编号 / 领用部门 / 领用人 / 盘点日期）
- RFID 扫描标签，实时匹配 EPC
- 统计 已盘 / 盘亏 / 盘盈
- 导出盘点结果 Excel
- 历史任务本地回看

## 技术栈

- Java 11 / AndroidX (minSdk 24, targetSdk 34)
- Single Activity + 3 Fragments (BottomNavigationView)
- Room 2.6 (本地数据库)
- Apache POI 5.2.5 (Excel 解析)
- Jackson 2.17 (JSON)
- UROVO RFID SDK (rscja `DeviceAPI_ver20230301_release.aar`，见 `app/libs/`)
- JUnit 4 + Robolectric (单元测试)

## 项目结构

参见 [docs/superpowers/specs/2026-06-10-rfid-asset-inventory-design.md](docs/superpowers/specs/2026-06-10-rfid-asset-inventory-design.md) § 3.2.

简要:
```
app/src/main/java/com/huaxia/inventory/
├── data/              # Room 实体 + DAO + 数据库
├── rfid/              # RFID SDK 封装
├── excel/             # Excel 导入/导出
├── session/           # 任务状态共享
├── ui/                # 3 个 Fragment
│   ├── import_/       #   - 导入
│   ├── scan/          #   - 扫描
│   └── result/        #   - 结果
└── util/              # 工具类
```

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

测试覆盖:
- EpcNormalizer (7 测试)
- StatusDerivation (6 测试)
- ExcelImporter (2 测试)
- ExcelExporter (2 测试)
- HeaderSpec (4 测试)
- AssetDao (3 测试)
- ScanRecordDao (3 测试)
- RfidController (5 测试)

## 部署到 UROVO PDA

1. 启用 USB 调试
2. `adb connect <pda-ip>:5555`
3. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
4. 启动 App，按 PDA 扫描枪按键验证扫描功能

## 实施记录

- 实施计划: [docs/superpowers/plans/2026-06-10-rfid-asset-inventory.md](docs/superpowers/plans/2026-06-10-rfid-asset-inventory.md)
- 设计文档: [docs/superpowers/specs/2026-06-10-rfid-asset-inventory-design.md](docs/superpowers/specs/2026-06-10-rfid-asset-inventory-design.md)
- 冒烟测试: [docs/smoke-test-2026-06-10.md](docs/smoke-test-2026-06-10.md)

## 待办 / 已知限制

- 未生成 Gradle wrapper (用户需运行 `gradle wrapper`)
- 未配置 git 提交者 (用户决定后再启用 commit)
- UI 资源中的 launcher icon 使用 Android 默认 (可在 T14 中替换)
- 物理 PDA 扫描枪按键需在真机确认 (F1 / KEYCODE_BUTTON_R1 / 其他)
- 1.0 不实现历史任务列表页 (OQ5)

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
- `Asset.createdAt` 字段未实现，"今日新增"统计暂显示 0（占位）
- `lastInventoryAt` 当前用 `InventoryTask.importedAt` 作代理（非真正"最后扫描时间"）
- EXTRA 模式下 `vm.extras().observe` 是空 body（仅 assets observer 渲染；后续迭代可加 ScanRecord 专用 Adapter）
- 深色模式未做专项优化（沿用系统 DayNight）

### 文档
- 设计文档：`docs/superpowers/specs/2026-06-11-ui-redesign-html-mockup-alignment-design.md`
- 实施计划：`docs/superpowers/plans/2026-06-11-ui-redesign-html-mockup-alignment.md`

## 验证

实施过程中所有代码均通过 spec compliance + code quality review 通过。所有单元测试代码已就位但未在本地执行 (无 gradle wrapper)。建议在真机部署前先在 IDE / 真机环境中跑一次 `./gradlew test`。

## 开发者注意事项

- RFID 回调 (`onInventoryTag`) 在 SDK 内部 worker 线程，不要在回调内直接操作 UI
- 启动 RFID 扫描时 `epc` 字段必须先做归一化 (trim + 纯十进制 + 16/17/24 位三分支，详见 `EpcNormalizer.normalize`；2026-06-16 后已废弃 hex 转换)
- EPC 去重使用 `HashSet` + DB 唯一索引双重保护
- 导出 Excel 时从首个 asset 的 `rawRowJson` 重建 8 个必需列表头顺序 (序号 / 设备类型 / 设备型号 / 序列号 / 固资编号 / 领用部门 / 领用人 / 盘点日期)，并丢弃"盘点日期"列；尾部追加两列 `RESULT_COLUMNS = ["盘点状态", "盘点时间"]`

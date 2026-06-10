# 固定资产盘点 (Huaxia Inventory)

Android RFID 资产盘点应用，运行在 UROVO 工业 PDA 上。

## 功能

- 从 Excel 导入资产清单（35 列）
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
- UROVO RFID SDK (`libs/URFIDLibrary-v2.4.1226.aar`)
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

## 验证

实施过程中所有代码均通过 spec compliance + code quality review 通过。所有单元测试代码已就位但未在本地执行 (无 gradle wrapper)。建议在真机部署前先在 IDE / 真机环境中跑一次 `./gradlew test`。

## 开发者注意事项

- RFID 回调 (`onInventoryTag`) 在 SDK 内部 worker 线程，不要在回调内直接操作 UI
- 启动 RFID 扫描时 `epc` 字段必须先做归一化 (trim + uppercase + 16进制 + 4 倍数)
- EPC 去重使用 `HashSet` + DB 唯一索引双重保护
- 导出 Excel 时从首个 asset 的 `rawRowJson` 重建 35 列表头顺序

# RFID 资产盘点 Android App — 设计文档

- **日期**: 2026-06-10
- **作者**: brainstorm 输出
- **状态**: 待用户 review
- **目标 SDK**: 24 (Android 7.0) — 34 (Android 14)
- **包名**: `com.huaxia.inventory`
- **应用名**: 固定资产盘点 (英文: Huaxia Inventory)

---

## 1. 概述 (Overview)

为银行/政企客户的固定资产盘点场景开发一个 Android 原生 App，运行在 UROVO 工业级 RFID PDA 上。核心流程：

1. 工作人员从 Excel 导入**期望清单**（含每件资产的 RFID EPC 编码）。
2. 手持 PDA 在仓库/机房巡检，**逐件扫描 RFID 标签**。
3. App 实时匹配扫描结果，统计 **在册已盘 / 盘亏 / 盘盈**。
4. 完成后导出含盘点结果的 Excel 文件，**支持历史回看**。

**关键约束**:
- 数据**不联网**，完全本地处理
- 单 PDA、单机使用，无需多设备同步
- 必须能在工业 PDA 上稳定运行（GROVE 设备、Android 9+、带 UHF RFID 模块）

---

## 2. 目标与非目标 (Goals & Non-Goals)

### 2.1 Goals
- G1. 从 Excel 导入资产清单，解析 8 个必需列 (序号 / 设备类型 / 设备型号 / 序列号 / 固资编号 / 领用部门 / 领用人 / 盘点日期) 并落库
- G2. 通过 UROVO RFID SDK 扫描标签，实时与导入数据匹配
- G3. 统计三类盘点状态：在册已盘 / 盘亏 / 盘盈
- G4. 盘点结果可导出为 Excel（保留 8 个必需列 + 追加 2 列结果：盘点状态 / 盘点时间）
- G5. 历史盘点任务可在本地数据库中回看
- G6. 离线运行，无网络依赖

### 2.2 Non-Goals
- N1. ❌ 不实现云端同步
- N2. ❌ 不实现多设备协同
- N3. ❌ 不实现资产新增/编辑/删除（仅盘点）
- N4. ❌ 不实现用户/角色/权限系统
- N5. ❌ 不实现盘点计划、提醒、消息推送
- N6. ❌ 不写 RFID 标签（只读，不调用 `writeTag*`）

---

## 3. 总体架构 (Architecture)

### 3.1 技术栈

| 层 | 选型 | 备注 |
|---|---|---|
| 语言 | Java 11 | 源集 `sourceCompatibility = JavaVersion.VERSION_11` |
| UI | AndroidX + 传统 View (XML Layout) | 不用 Compose |
| 架构 | Single Activity + 3 Fragments | BottomNavigationView 切换 |
| 状态 | ViewModel + LiveData | Java 兼容 |
| 数据库 | Room 2.6.x | 编译期 SQL 校验 |
| Excel | Apache POI 5.2.5 (`poi-ooxml`) | ~3.5 MB |
| JSON | Jackson Databind 2.17.0 | 用于 rawRowJson 序列化 |
| 文件 | Storage Access Framework (SAF) | 无需 `READ_EXTERNAL_STORAGE` 权限 |
| RFID | `DeviceAPI_ver20230301_release.aar` (rscja DeviceAPI) | 厂商 SDK（同步轮询模型） |

### 3.2 模块布局

```
huaxiaapp/
├── libs/DeviceAPI_ver20230301_release.aar  # 厂商 SDK（rscja DeviceAPI）
├── uhf-uart-demo/uhf-uart-demo/            # 厂商 demo 源码（API 调用参考）
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/huaxia/inventory/
│       │   ├── InventoryApp.java              # Application：触发 RFID init
│       │   ├── MainActivity.java              # 单 Activity 容器
│       │   ├── data/
│       │   │   ├── AppDatabase.java
│       │   │   ├── entity/
│       │   │   │   ├── InventoryTask.java
│       │   │   │   ├── Asset.java
│       │   │   │   ├── ScanRecord.java
│       │   │   │   └── TaskStatus.java
│       │   │   └── dao/
│       │   │       ├── InventoryTaskDao.java
│       │   │       ├── AssetDao.java
│       │   │       └── ScanRecordDao.java
│       │   ├── rfid/
│       │   │   ├── SdkRfidAdapter.java        # 适配 rscja 同步轮询 SDK
│       │   │   └── RfidController.java        # 封装 SDK 生命周期
│       │   ├── excel/
│       │   │   ├── ExcelImporter.java
│       │   │   └── ExcelExporter.java
│       │   ├── session/
│       │   │   └── InventorySession.java      # 当前任务共享状态
│       │   ├── ui/
│       │   │   ├── common/
│       │   │   │   └── AssetListAdapter.java  # 列表 item 复用
│       │   │   ├── import_/
│       │   │   │   ├── ImportFragment.java
│       │   │   │   └── ImportViewModel.java
│       │   │   ├── scan/
│       │   │   │   ├── ScanFragment.java
│       │   │   │   ├── ScanViewModel.java
│       │   │   │   └── RecentScanAdapter.java
│       │   │   └── result/
│       │   │       ├── ResultFragment.java
│       │   │       ├── ResultViewModel.java
│       │   │       └── ResultTabAdapter.java
│       │   └── util/
│       │       ├── AppExecutors.java          # 线程池
│       │       ├── EpcNormalizer.java         # EPC 字符串归一化
│       │       └── DateUtil.java
│       └── res/
│           ├── layout/
│           ├── values/strings.xml (zh + en)
│           ├── drawable/
│           └── menu/
└── docs/superpowers/specs/...
```

### 3.3 三屏与数据流

```
┌──────────────┐  pick xlsx  ┌──────────────┐  start scan  ┌──────────────┐
│ 1. Import    │ ──────────► │ 2. Scan      │ ───────────► │ 3. Result    │
│  (ImportF)   │             │  (ScanF)     │              │  (ResultF)   │
│              │             │              │              │              │
│ - 选 xlsx    │             │ - 实时 EPC   │              │ - 4 个 tab   │
│ - 解析预览   │             │ - 进度统计   │              │ - 搜索过滤   │
│ - 显示条数   │             │ - 停止扫描   │              │ - 导出 Excel │
└──────────────┘             └──────────────┘              └──────────────┘
       │                              │                            │
       └───────── InventorySession (Application 作用域单例) ──────┘
                                  │
                                  ▼
                            AppDatabase (Room)
                                  │
                ┌─────────────────┼─────────────────┐
                ▼                 ▼                 ▼
          InventoryTask        Asset          ScanRecord
```

- `InventorySession` 持有 `currentTaskId`，三个 Fragment 通过 `((InventoryApp) requireActivity().getApplication()).session` 拿到同一实例
- 状态切换：导入成功 → ScanFragment 可启动；扫描中 → ResultFragment 通过 LiveData 实时刷新统计
- 底部 Tab 用 `BottomNavigationView` + `FragmentTransaction`

---

## 4. 数据模型 (Data Model)

### 4.1 Room 实体

#### `InventoryTask` — 一次盘点任务

```java
@Entity
public class InventoryTask {
    @PrimaryKey(autoGenerate = true) long id;
    String sourceFileName;              // 原始 Excel 文件名
    long importedAt;                    // 导入时间戳 (ms)
    @ColumnInfo(name = "status") String status;  // "IN_PROGRESS" / "COMPLETED"
    int expectedCount;                  // 期望资产数（导入时计算）
    int scannedUniqueCount;             // 已扫描唯一标签数（实时更新）
}
```

`TaskStatus` 枚举：IN_PROGRESS, COMPLETED（不引入 TypeConverter，存 String）。

#### `Asset` — 资产主数据（一行 Excel = 一条记录）

混合存储：索引字段加速查询，`rawRowJson` 保真整行。

```java
@Entity(indices = {
    @Index("taskId"),
    @Index(value = {"taskId", "epc"})
})
public class Asset {
    @PrimaryKey(autoGenerate = true) long id;
    long taskId;
    int excelRowIndex;                  // 原始 Excel 行号（导出定位）

    // 索引字段（查询 / 列表展示用）
    String epc;                         // col 13: 电子标签 —— 匹配键
    String assetName;                   // col 5:  设备名称
    String assetType;                   // col 8:  设备类型
    String assetModel;                  // col 9:  设备型号
    String org;                         // col 2:  所属机构
    String detailedLocation;            // col 21: 详细地点
    String placementLocation;           // col 22: 放置地点
    String assetStatus;                 // col 14: 资产状态

    // 完整保真（导出时按列序还原 Excel）
    @ColumnInfo(name = "raw_row_json") String rawRowJson;
}
```

#### `ScanRecord` — 扫描结果（每个唯一 EPC 一条）

```java
@Entity(indices = {
    @Index("taskId"),
    @Index("matchedAssetId"),
    @Index(value = {"taskId", "epc"}, unique = true)
})
public class ScanRecord {
    @PrimaryKey(autoGenerate = true) long id;
    long taskId;
    String epc;                         // 唯一约束 (taskId, epc)
    String tid;                         // 标签 TID（来自回调, 可选）
    String rssi;                        // 信号强度
    long scannedAt;                     // 首次扫描时间戳
    Long matchedAssetId;                // 关联 Asset.id；null = 盘盈
}
```

唯一索引保证重复扫描入库幂等（防御性，HashSet 已在内存层先做去重）。

### 4.2 盘点状态推算 (Derived)

不另存 `InventoryResult` 表，**实时 JOIN 计算**：

| 状态 | 定义 | 颜色 |
|---|---|---|
| FOUND 在册已盘 | `Asset.id ∈ ScanRecord.matchedAssetId` | 🟢 `#4CAF50` |
| MISSING 盘亏 | `Asset.id ∉ ScanRecord.matchedAssetId` | 🔴 `#F44336` |
| EXTRA 盘盈 | `ScanRecord.matchedAssetId IS NULL` | 🟠 `#FF9800` |

校验不变量: `FOUND + MISSING = expectedCount`, `EXTRA ≥ 0`。

### 4.3 导出 Excel 格式

> **修订说明**：本节最初描述 "35 列 + 3 列结果" 是 1.0 设计的旧合约。2026-06-10 后改用 8 列新格式 (`HeaderSpec.REQUIRED`)；2026-06-11 删除冗余的 RSSI 列，RESULT_COLUMNS 缩减为 2 列。当前实现与本节一致：8 个必需列 + 2 个结果列。

**保留 8 个必需列 + 追加 2 列结果**（不修改原列，丢弃原"盘点日期"列，由"盘点时间"取代）:

| # | 列名 | 备注 |
|---|---|---|
| 1 | 序号 | 从 `Asset.rawRowJson` 还原 |
| 2 | 设备类型 | |
| 3 | 设备型号 | |
| 4 | 序列号 | |
| 5 | 固资编号 | 24 位十进制规范形态 |
| 6 | 领用部门 | |
| 7 | 领用人 | |
| 8 | (原"盘点日期"列丢弃) | 由"盘点时间"取代 |
| 9 | 盘点状态 | "已盘" / "盘亏" / "盘盈" |
| 10 | 盘点时间 | `yyyy-MM-dd HH:mm:ss` |

**盘盈记录**: 由于没有对应的 Asset 行，追加在主表后面：
- 空一行
- 写入一行 "【盘盈记录】" 标题
- 重复表头
- 遍历 `ScanRecord WHERE matchedAssetId IS NULL`，col 1-8 留空，col 9-10 填写

---

## 5. UI 设计

### 5.1 Fragment 1: `ImportFragment`

**Layout** (`fragment_import.xml`):

```
┌──────────────────────────────────────┐
│ 导入盘点清单                          │  ← Toolbar
├──────────────────────────────────────┤
│                                      │
│  📄  [选择 Excel 文件]               │  ← CardView 按钮（无文件时）
│                                      │
│  ─────── 已选文件 ───────            │  ← 文件被选择后展开
│  文件名: 固定资产测试.xlsx            │
│  记录数: 133                          │
│  标签列: 13 (电子标签)                │
│  ⚠️ 第 23 行 EPC 为空, 已跳过        │  ← 解析结果提示
│                                      │
│  ─────── 预览 (前 10 条) ───────     │  ← RecyclerView 预览
│  1  RFID设备  DPM901  运营管理部      │
│  2  UPS       科士达   信息科技部     │
│  ...                                 │
│                                      │
│       [   开 始 盘 点   ]             │  ← 底部主按钮
└──────────────────────────────────────┘
```

**职责**:
- 调用 SAF `ACTION_OPEN_DOCUMENT` 选 xlsx
- 解析时校验：第 3 行为表头、`电子标签` 列存在、至少 1 条 EPC 非空
- 通过则：写 DB → 创建 `InventoryTask(IN_PROGRESS)` → `InventorySession.setCurrentTaskId()` → 跳到 `ScanFragment`

### 5.2 Fragment 2: `ScanFragment`

**Layout** (`fragment_scan.xml`):

```
┌──────────────────────────────────────┐
│ 盘点扫描  任务 #5                    │
├──────────────────────────────────────┤
│  ┌─────────┬─────────┐               │
│  │  预期   │  已扫    │              │  ← 顶部统计
│  │  133    │  87     │              │
│  └─────────┴─────────┘               │
│                                      │
│   ┌──────────────────────────┐      │
│   │   ▶  开 始 扫 描         │      │  ← 大按钮（按下变红色"停止"）
│   └──────────────────────────┘      │
│   设备: UROVO  功率: 26dBm          │  ← 设备状态
│                                      │
│  ── 最近扫描 ──                      │
│  ⌚ 14:23:05  EPC:3034...            │  ← 实时滚动列表 (2026-06-11 后不再显示 RSSI)
│  ⌚ 14:23:04  EPC:3034...            │     同一 EPC 显示 ✓ 已盘
│  ⌚ 14:23:03  EPC:3099...            │
│  ...                                 │
│                                      │
│  [查看结果]  ← 右下角 FAB           │
└──────────────────────────────────────┘
```

**职责**:
- `onResume`: `RfidController.registerCallback()` + `startInventory()`
- `onPause`: `stopInventory()` + `unregisterCallback()`
- 扫描中: 收到的 EPC 先查 `HashSet<String>` 去重；新 EPC 写 DB；UI 通过 `LiveData` 实时刷新统计
- 物理按键: PDA 扫描枪按键映射到"开始/停止"按钮的点击

### 5.3 Fragment 3: `ResultFragment`

**Layout** (`fragment_result.xml`):

```
┌──────────────────────────────────────┐
│ 盘点结果  [导出 Excel]               │  ← Toolbar + 导出按钮
├──────────────────────────────────────┤
│ ┌────────────────────────────────┐  │
│ │ [全部 133] [已盘 87] [盘亏 46] [盘盈 3] │  ← TabLayout
│ └────────────────────────────────┘  │
│  🔍 搜索设备名/EPC/位置              │  ← SearchView
│                                      │
│  ┌──────────────────────────────┐   │
│  │ #1  RFID设备 / DPM901        │   │  ← RecyclerView 卡片
│  │ 所属: 运营管理部               │   │
│  │ 地点: 松柏支行                 │   │
│  │ EPC: 3034...A1B2              │   │
│  │ [🟢 已盘] 14:23:05           │   │  ← 状态 chip + 时间 (2026-06-11 后不再显示 RSSI)
│  └──────────────────────────────┘   │
│  ┌──────────────────────────────┐   │
│  │ #5  UPS / 科士达 6-FML-100   │   │
│  │ ...                           │   │
│  │ [🔴 盘亏]                     │   │
│  └──────────────────────────────┘   │
└──────────────────────────────────────┘
```

**职责**:
- 4 个 Tab 共享同一个 `RecyclerView`，用 `ViewModel` 中 `MutableLiveData<FilterType>` 切换
- 搜索: `SQL LIKE` 过滤 `assetName / epc / detailedLocation`
- 导出: 用 SAF `ACTION_CREATE_DOCUMENT` 保存到用户选择的位置，文件名默认 `盘点结果_{taskId}_{yyyyMMdd_HHmmss}.xlsx`

### 5.4 共享 UI 元素

- **底部导航**: `BottomNavigationView` (3 个 tab) + `FragmentContainerView`
- **App 启动默认 Fragment**: MainActivity 启动时若 `InventorySession.currentTaskId == null` 默认加载 `ImportFragment`；若有当前任务则加载 `ScanFragment`（允许用户继续未完盘点）
- **状态色**:
  - 🟢 `#4CAF50` 已盘
  - 🔴 `#F44336` 盘亏
  - 🟠 `#FF9800` 盘盈
- **中文字体**: 系统默认（不强制指定）

---

## 6. RFID 集成与线程模型

### 6.1 `RfidController` 设计

封装 SDK 为单例，**只对 UI 暴露安全 API**：

```java
public class RfidController {
    public enum State { IDLE, INITIALIZING, READY, SCANNING, ERROR }

    public void init(Context appCtx, Callback<Boolean> onReady);
    public void registerCallback(SdkRfidAdapter.TagListener cb);  // 转发到 SDK
    public void unregisterCallback(SdkRfidAdapter.TagListener cb);
    public void startInventory();
    public void stopInventory();
    public void powerOff();
    public void powerOn();
    public void release();
    public State getState();
}
```

**关键不变量**:
- `init` 只在第一次调用时执行；后续调用直接返回当前状态
- `registerCallback` / `unregisterCallback` 维护内部 `Set<SdkRfidAdapter.TagListener>`，支持多订阅者
- `startInventory` 内部检查状态：`IDLE/READY → startRead()`；`SCANNING → no-op`；`ERROR → 拒绝并提示`
- `release` 幂等：第二次调用不抛异常
- 新 SDK 内部使用 `SdkRfidAdapter` 启动一个守护 `rfid-poll` 线程桥接同步轮询，转发为 `TagListener.onTag(epc, tid, rssi)` 回调

### 6.2 生命周期归属

| 时机 | 操作 | 调用方 |
|---|---|---|
| `Application.onCreate` | `init()`（**早执行 2 秒**） | `InventoryApp` |
| `ScanFragment.onResume` | `registerCallback` + `startInventory` | Fragment |
| `ScanFragment.onPause` | `stopInventory` + `unregisterCallback` | Fragment |
| `MainActivity.onStop`（>30 min） | `powerOff()` | Activity（用 `lastStopTime` 字段记录，30 分钟后 `powerOff`） |
| `MainActivity.onDestroy` | `release()` | Activity |

**取舍说明**: 扫描中切到"结果"页查看统计，**不**停止 RFID 读取（后台仍滚动写入）。切到"导入"或退出 App 时才停。

### 6.3 线程模型

rscja DeviceAPI 是**同步轮询**模型：调用 `startInventoryTag()` 启动扫描后，调用方需在自有线程里循环调用 `readTagFromBuffer()` 拉取结果。本项目在 `SdkRfidAdapter` 内启动一个守护线程 `rfid-poll` 桥接，再以 `TagListener` 回调对外暴露（与 UI 期望的事件模型对齐）。

```
┌──────────────────┐  readTagFromBuffer()  ┌──────────────────┐  onTag(epc,tid,rssi)  ┌──────────────────┐
│  RFID 硬件       │ ◄─────────────────── │ SdkRfidAdapter   │ ────────────────────► │ RfidController   │
│  (rscja SDK)     │   每 20ms 轮询        │ rfid-poll 线程   │  fan-out 到所有订阅者  │ (handler.post)   │
└──────────────────┘                       └──────────────────┘                       └────────┬─────────┘
                                                                                               │ 主线程
                                                                                               ▼
                                                                                      ┌──────────────────┐
                                                                                      │ ScanViewModel    │
                                                                                      │ .onEpcScanned()  │
                                                                                      └────────┬─────────┘
                                                                                               │ 提交到 diskIO
                                                                                               ▼
                                                                                      ┌──────────────────┐
                                                                                      │  Room DAO        │
                                                                                      │  (background)    │
                                                                                      └────────┬─────────┘
                                                                                               │ 完成后
                                                                                               ▼
                                                                                      ┌──────────────────┐
                                                                                      │ LiveData 更新    │
                                                                                      │ (postValue)      │
                                                                                      └────────┬─────────┘
                                                                                               ▼
                                                                                            UI 刷新
```

**`AppExecutors.java`**:
```java
public class AppExecutors {
    public final ExecutorService diskIO;     // 4 线程, DB 写入
    public final ExecutorService excelIO;    // 单线程, Excel 解析/导出
    public final Handler mainThread;         // 主线程 Handler
}
```

**为什么不用 RxJava/Coroutines**: Java + 传统 View 项目，依赖最小化；`Handler` + `Executor` 已足够。

### 6.4 EPC 去重与匹配 (核心算法)

```java
// ScanViewModel.onEpcScanned(epc, tid, rssi)
String clean = EpcNormalizer.normalize(epc);  // trim + uppercase + 长度校验
if (clean == null) return;
if (scannedEpcSet.contains(clean)) {
    recentScans.update(clean);  // 2026-06-11 后不再维护 RSSI, 最近列表只刷新 EPC
    return;
}
scannedEpcSet.add(clean);

diskIO.execute(() -> {
    Asset matched = assetDao.findByEpc(taskId, clean);
    ScanRecord record = new ScanRecord(
        taskId, clean, tid, rssi, now(),
        matched != null ? matched.id : null
    );
    scanRecordDao.insert(record);  // OnConflictStrategy.IGNORE

    mainThread.post(() -> {
        statsLiveData.setValue(computeStats());
        recentScans.prepend(record);
    });
});
```

**`scannedEpcSet` 的来源与持久化**:
- 启动扫描时：`SELECT epc FROM ScanRecord WHERE taskId = ?` → 填充 `HashSet`
- 后续每条回调：先查 set，再写 DB
- 优点: 即使 App 被系统杀死重启，set 也能从 DB 重建
- 内存: 每条 EPC ~32 字节，10 万条 ≈ 3 MB，可接受

### 6.5 EPC 字符串规范化

> **修订说明**：本节原合约 (trim + uppercase + 长度 4 倍数 + 仅 [0-9A-F]) 是 1.0 设计的 hex 解释。2026-06-16 后确认卡上直接存十进制 16/17/24 位字符串，不再做任何 hex 转换；当前实现与本节差异如下:

```java
// EpcNormalizer.normalize()  — 2026-06-16 后的合约
public static String normalize(String raw) {
    if (raw == null) return null;
    String s = raw.trim();
    if (s.isEmpty()) return null;
    if (!s.matches("[0-9]+")) return null;          // 仅接受纯十进制, 拒绝 hex
    switch (s.length()) {
        case 16: return "00000000" + s;              // 16 位左补 8 个 0 → 24 位
        case 17: return "0000000" + s;               // 17 位左补 7 个 0 → 24 位
        case 24: return s;                            // 24 位原样
        default:  return null;                        // 其他长度一律拒绝
    }
}
```

---

## 7. Excel I/O

### 7.1 导入 (ExcelImporter.java)

**流程**:
```
SAF Uri → ContentResolver.openInputStream → XSSFWorkbook
   ↓
getSheetAt(0) → 验证表头行（第 3 行）
   ↓
逐行解析（从第 4 行起）：
  ├─ 提取 col 13 = EPC（必填, 否则跳过该行）
  ├─ 提取索引字段（assetName, assetType, ...）
  └─ rawRowJson = JSON.stringify(35 个单元格)
   ↓
批量插入 Asset 表（事务）
   ↓
返回 ImportResult { total, success, skipped[] }
```

**表头校验**: 读第 3 行，必须包含以下列（缺一即拒绝，错误信息列出全部缺失项）:
```
序号 | 设备名称 | 设备类型 | 设备型号 | 所属机构
| 详细地点 | 放置地点 | 资产状态 | 电子标签
```

**依赖** (in `app/build.gradle`):
```gradle
implementation 'org.apache.poi:poi-ooxml:5.2.5'
implementation 'com.fasterxml.jackson.core:jackson-databind:2.17.0'
```

### 7.2 导出 (ExcelExporter.java)

**流程**:
```
SAF Uri (ACTION_CREATE_DOCUMENT) → OutputStream
   ↓
XSSFWorkbook + Sheet "盘点结果"
   ↓
行 1: 复制源文件标题
行 2: 制单人 = 当前用户, 编制时间 = now
行 3: 复制原始 8 个必需列表头 + 追加 2 列 (盘点状态 / 盘点时间)
行 4 ~ N+3: 遍历 Asset
   ├─ cells 1-8: 从 rawRowJson 还原
   ├─ cell 9 (盘点状态): "已盘" / "盘亏"
   └─ cell 10 (盘点时间): "yyyy-MM-dd HH:mm:ss" 或空
   ↓
行 N+5 起: "【盘盈记录】" 段落
   ├─ 同一表头
   └─ 遍历 ScanRecord WHERE matchedAssetId IS NULL
   ↓
workbook.write(outputStream)
```

**默认文件名**: `盘点结果_{taskName}_{yyyyMMdd_HHmmss}.xlsx`
用户可通过 SAF 文件选择器改名 / 改路径。

---

## 8. 错误处理矩阵

| 阶段 | 错误 | 处理 |
|---|---|---|
| 选文件 | 用户取消 | 静默返回 |
| 选文件 | URI 权限丢失 | Toast "文件不可访问，请重选" |
| 解析 | 非 .xlsx (zip 头不匹配) | 弹窗"文件格式错误" |
| 解析 | 找不到 sheet1 | 弹窗"文件结构不符" |
| 解析 | 必需表头缺失 | 弹窗列出**缺失列名** + 取消 |
| 解析 | 全部行 EPC 为空 | 弹窗"无可用电子标签" |
| 解析 | 文件 > 20 MB | 弹窗"文件过大" |
| 解析 | POI 抛异常 | 弹窗"文件可能已损坏" + 详细日志 |
| DB | 唯一约束冲突 | IGNORE 静默 |
| DB | 磁盘满 | 弹窗 + 提示清理存储 |
| 导出 | 路径无效 | Toast + 重选 |
| 导出 | IO 异常 | Toast + 结果**不丢失**（DB 中保留） |
| 扫描 | RFID 未连接 | 按钮 disabled + 提示 |
| 扫描 | 回调线程异常 | 捕获 + Log.e，不影响后续 |
| 生命周期 | onPause 时正在扫描 | 静默 stop，onResume 自动恢复 |
| 生命周期 | `InventorySession.currentTaskId == null` | 跳回 ImportFragment |

---

## 9. 测试策略

按价值与可行性的权衡：

| 类型 | 范围 | 工具 | 优先级 |
|---|---|---|---|
| **JVM 单元测试** | Excel 解析、JSON 序列化、状态推算、EPC 归一化 | JUnit 5 + AssertJ | **高** |
| **Robolectric** | Room DAO 读写、ViewModel 状态 | Robolectric + AndroidX Test | **中** |
| **仪器测试** | 真机 PDA 上的 RFID 回调、E2E | 实际 UROVO 设备 | 仅冒烟 |

### 9.1 关键单元测试用例

```java
// 1. ExcelImporterTest
@Test void parsesSampleFile_createsCorrectAssetCount();
@Test void missingHeaders_listsAllMissingColumns();
@Test void emptyEpcRow_isSkipped();
@Test void rawRowJson_roundTripsExactly();   // 解析→导出→比较 8 列

// 2. EpcNormalizerTest  — 2026-06-16 后合约
@Test void rejects16Digit_padsTo24();
@Test void rejects17Digit_padsTo24();
@Test void rejectsNonDigitOrHex();
@Test void rejectsWrongLength();

// 3. StatusDerivationTest
@Test void scannedAssetIsFound();
@Test void unscannedAssetIsMissing();
@Test void unknownEpcIsExtra();
@Test void statsAddUpToExpected();  // found + missing == expected

// 4. ScanViewModelTest (Robolectric)
@Test void duplicateEpc_writesOnceToDb();
@Test void scannedEpcSet_rebuiltFromDbOnRestart();
```

### 9.2 明确不写测试的项

- UI 渲染细节
- RFID 真实硬件行为（依赖实地冒烟测试）
- SAF 文件选择器（系统行为）

---

## 10. 决策日志 (Decisions Log)

| 决策 | 选择 | 备选 | 理由 |
|---|---|---|---|
| 语言 | Java 11 | Kotlin | 用户指定 Java + 传统 View |
| UI 框架 | 传统 XML View | Compose | 用户指定传统 View |
| 架构 | Single Activity + 3 Fragments | 3 Activities / ViewPager2 | 现代主流，状态共享简单 |
| 数据 | Room | 裸 SQLite | 编译期校验，少样板 |
| Excel | Apache POI | fastexcel / 手写 XML | 业内标准 |
| 文件访问 | SAF | 直接文件路径 | Android 11+ 兼容，免权限 |
| 标签匹配 | EPC 唯一 | TID + EPC | 用户选择 EPC 唯一 |
| UI 范围 | 精简 3 屏 | 多页+历史+设置 | 用户选择 |
| 导出格式 | 8 个必需列 + 追加 2 列 | 替换原"盘点日期"列 | 与新导入格式对齐 |
| RFID 去重 | 内存 HashSet + DB 唯一索引 | 仅 DB 唯一索引 | HashSet 减少 DB 写入 |

---

## 11. 词汇表 (Glossary)

| 中文 | 英文 | 含义 |
|---|---|---|
| 电子标签 | EPC | RFID 标签的电子产品代码，本设计中作为匹配键 |
| 在册已盘 | FOUND | 期望清单中存在且被扫到的资产 |
| 盘亏 | MISSING | 期望清单中存在但未扫到 |
| 盘盈 | EXTRA | 扫到但不在期望清单中 |
| 盘点任务 | Inventory Task | 一次完整的导入-扫描-导出流程 |
| 原数据 | rawRowJson | Excel 行的完整 JSON 序列化，用于导出保真 |

---

## 12. 待办 / 开放问题 (Open Questions)

- OQ1. **PDA 物理按键映射**: UROVO 不同型号扫描枪按键不同，需要在开发机上做按键测试后确认是 `F1` / `F2` 还是 `KEYCODE_BUTTON_R1` 等。
- OQ2. **应用图标与启动屏**: 用户尚未提供品牌资源，先用 Android 默认。
- OQ3. **导出文件编码**: xlsx 本身就是 UTF-8，不需要额外处理；但若用户要求兼容老版 Office 2003 (.xls)，需要切换为 `HSSFWorkbook`，工作量约 1 天。
- OQ4. **盘点中的"未完成"状态持久化**: 如果 App 在盘点途中被杀死，重启后是否能继续扫描当前任务？当前设计: ✅ 可以（`InventoryTask` 状态 `IN_PROGRESS` 持久化）。
- OQ5. **历史任务列表**: 当前 3 屏不包含"历史任务"页。是否需要追加？建议先在 1.0 简化版不实现，2.0 再加。**默认不实现**。

---

**Spec 终稿，待用户 review。**

---

## 13. Self-Review 记录

- ✅ **占位符扫描**: 无 TBD / TODO，所有字段已填或显式列为 Open Question
- ✅ **内部一致性**: 6.2 节的 `>30 min` 在两处出现且语义一致；4.1 实体字段与 6.4 算法使用的字段一致
- ✅ **范围检查**: 单 Android App、3 屏、约 30-40 文件，单一实施计划可覆盖
- ✅ **歧义检查**: 已补充"启动默认 Fragment"、"30 min 计时方式"两处易被误解的细节
- ⚠️ **未提交到 git**: 项目目录不是 git 仓库，提交步骤跳过

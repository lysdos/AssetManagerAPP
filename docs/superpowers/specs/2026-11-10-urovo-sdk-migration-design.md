# 手持机 SDK 由 rscja 替换为优博讯（Urovo）— 设计

- **日期**: 2026-11-10
- **作者**: brainstorm 输出
- **状态**: 已实现 (2026-11-10, 分支 `feat/urovo-sdk`)
- **范围**: `app/build.gradle` / `app/libs/` / `rfid/SdkRfidAdapter` / `barcode/BarcodeHardwareController` / `InventoryApp` 诊断探针 / `proguard-rules.pro` / 相关单测 / `README.md`

---

## 1. 背景

当前 App 全部硬件能力依赖「成为」(rscja) 的 `DeviceAPI_ver20230301_release.aar`：

| 功能 | 现用类 | 调用方 |
|---|---|---|
| UHF RFID 连续盘点 | `com.rscja.deviceapi.RFIDWithUHFUART`（轮询式） | `rfid/SdkRfidAdapter` |
| 标签实体 / TID / RSSI | `com.rscja.deviceapi.entity.UHFTAGInfo` | 同上 |
| 物理扫码头 | `com.rscja.barcode.BarcodeDecoder`（回调式） | `barcode/BarcodeHardwareController` |

目标设备改为优博讯手持机，两份 SDK 均已在仓库 `SDK_ReleaseforAndroid-master/` 内：

- **平台 SDK**：`SDK Release/platform_sdk_v4.1.0326/platform_sdk_v4.1.0326.jar`
  提供 `android.device.ScanManager`（条码）、`android.device.DeviceManager` 等。
- **UHF SDK**：`RFIDSDKSample-main/libs/URFIDLibrary-v2.5.0718.aar`
  提供 `com.ubx.usdk.RFIDSDKManager` / `com.ubx.usdk.rfid.RfidManager` / `IRfidCallback`。

两份产物无重复类：UHF 库内部只引用 `android.device.DeviceManager.getSettingProperty(String)`，该方法在 v4.1 中存在（已用 `javap` 双方核对）。

**目标：功能不变。** 上层 `RfidController`、`InventoryApp`、`ScanFragment`、
`ManualScanFragment`、功率设置弹窗、Room 数据模型均不改动。

---

## 2. 方案选型

| 方案 | 说明 | 结论 |
|---|---|---|
| **A. 原地换芯** | 保持 `SdkRfidAdapter` / `BarcodeHardwareController` 的公开 API 完全不变，只替换内部实现、依赖、ProGuard、诊断探针与相关单测 | **采用**。UI 零改动、diff 最小、最贴合「功能不变」 |
| B. 抽多厂商 Gateway 接口 | 在适配器之上再加一层可插拔抽象 | 超出范围，属重构而非替换 |
| C. 把 SDK 调用下沉到 UI | — | 破坏现有分层 |

旧 SDK 残留物处理（已与用户确认）：`app/libs` 中的旧 AAR 从依赖里摘除、不再进包；
`uhf-uart-demo/`（133 个文件的旧 SDK 示例）与「“成为”品牌的PDA手持机的SDK.txt」**保留不动**，避免一次 diff 过大。

---

## 3. 依赖变更

`app/build.gradle`：

```groovy
// 删除
implementation files('libs/DeviceAPI_ver20230301_release.aar')
// 新增
implementation files('libs/platform_sdk_v4.1.0326.jar')
implementation files('libs/URFIDLibrary-v2.5.0718.aar')
```

新增的两个文件从 SDK 包内复制到 `app/libs/`（与现有 AAR 同目录、同样的 `files(...)` 引入方式，
沿用项目既有风格，不引入 `flatDir` 仓库）。

UHF AAR 自带 `jni/{arm64-v8a,armeabi,armeabi-v7a}` 三个 ABI 的 `.so`，minSdk 26 无冲突。

---

## 4. RFID：`SdkRfidAdapter` 内部重写

### 4.1 公开 API 不变

`SdkRfidAdapter` 对外暴露的成员一个都不改，`RfidController` 继续按原样调用：

```java
public interface Callback     { void onStatus(boolean ready); }
public interface TagListener  { void onTag(String epc, String tid, String rssi); }

public SdkRfidAdapter()
public void    init(Context appCtx, Callback cb)
public boolean isConnected()
public boolean isPowered()
public int     startInventory()          // 0 = 成功
public int     stopInventory()           // 0 = 成功
public void    disConnect()
public void    power(boolean on)
public boolean setPower(int power)       // 0–30 dBm
public int     getPower()                // 失败 -1
public void    release()
public void    registerCallback(TagListener l)
public void    unregisterCallback(TagListener l)
```

`RfidController` 的 `State` 机、幂等 init、`CopyOnWriteArraySet` 扇出逻辑全部保持现状。

### 4.2 内部映射

| 公开方法 | 优博讯实现 |
|---|---|
| `init(ctx, cb)` | `RFIDSDKManager.getInstance().init(ctx, status -> ...)`；`status==true` 时取 `getRfidManager()` 并 `registerCallback(sdkCallback)`；整体 try/catch `Throwable` 兜住 |
| 标签回调 | `IRfidCallback.onInventoryTag(String EPC, String Data, int rssi)` → 转成 `TagListener.onTag(epc, tid, rssi)`；`Data` 为 null/空串时 `tid = null`（与旧 SDK 未读 TID 时的行为一致），rssi 用 `String.valueOf(int)` |
| `startInventory()` | `rfidManager.startInventory()`，返回 0 成功 → 本方法返回 0，否则 -1 |
| `stopInventory()` | `rfidManager.stopInventory()`；`scanning` 用 `compareAndSet` 保证幂等 |
| `setPower(p)` | 先校验 0–30，再 `setOutputPower(p)`，返回 `rc == 0` |
| `getPower()` | `getOutputPower()`，负数视为失败返回 -1 |
| `isConnected()` | `reader != null && reader.isConnected()` |
| `power(on)` | 保持旧语义：`false` 时先 `stopInventory()` 再清 `powered` 标志；新 SDK 无独立上电原语，不新增行为 |
| `disConnect()` | `rfidManager.disConnect()`（新 SDK 有真实断开原语，旧实现是空方法） |
| `release()` | `stopInventory()` → `unregisterCallback(sdkCallback)` → `RFIDSDKManager.getInstance().release()` → `reader = null`、`powered = false`；幂等 |

### 4.3 并发结构

**整条轮询线程被删除**（旧 SDK 是 `readTagFromBuffer()` 轮询，新 SDK 是回调式）：

- 删除 `pollThread`、`pollLoop()`、`POLL_IDLE_SLEEP_MS`，以及 `reader` 字段的 `volatile` 必要性（保留 `volatile` 无害，但不再有跨线程读语义）
- 保留 `lifecycleLock` 串行化 `startInventory` / `stopInventory` / `release`
- 保留 `CopyOnWriteArraySet<TagListener>`，保持 register/unregister 与分发并发安全
- `IRfidCallback` 可能在 binder 线程回调；每个 listener 的异常仍逐个 try/catch，避免一个 listener 抛异常断掉其余订阅者

### 4.4 时序说明

`RFIDSDKManager.init` 通过 `InitListener` 异步回报（旧 rscja `init()` 是同步返回 boolean）。
`InventoryApp.onCreate` 已在主线程发起 `rfid.init(this, cb)`，`ScanFragment.onResume` 里的
`powerOn()` 会调用 `SdkRfidAdapter.power(true)`，而后者无条件把 `powered` 置 true —— 所以
`isPowered()` 分支**不会**拦住「init 尚未完成」的窗口，实际表现是：

- init 完成前的窗口内，扫描按钮仍可点；此时 `startInventory()` 返回失败，UI 弹出
  「启动盘点失败」snackbar，用户重试即可；
- 永久失败（init 超时或 SDK 回调 `false`）时 `powered` 为 false，扫描按钮被禁用并显示
  「设备未就绪」。

这是与迁移前一致的容错表现（旧实现的 `power()` 同样只是记录期望状态），不是本次迁移
新引入的回归；因此 `RfidController` 与 UI 仍然不需要改动。

---

## 5. 条码：`BarcodeHardwareController` 内部重写

### 5.1 公开 API 不变

```java
public interface Callback { void onBarcodeDetected(String rawCode); void onStartFailed(String reason); }

public static void prewarm(Context appCtx)
public static boolean isReady()
public void startScan(Callback callback)
public void stopScan()
public void release()
```

`ManualScanFragment` 的「硬件优先 / CameraX 兜底」分支、2 秒去重语义、主线程回调均不变。

### 5.2 内部映射

| 生命周期 | 旧（rscja） | 新（优博讯 `android.device.ScanManager`） |
|---|---|---|
| `prewarm` | `BarcodeFactory.getInstance().getBarcodeDecoder().open(ctx)` + `enablePlaySuccessSound(false)` | 进程级缓存 `new ScanManager()`（已 `getScannerState()==true` 则不重复 `openScanner()`）→ `openScanner()` → `setUnBeep()`（关 SDK 自带成功音，仍由 `BeepPlayer` 统一响）→ `switchOutputMode(0)`（INTENT 广播模式，避免条码被注入聚焦的 `inputEt`） |
| `isReady` | 静态 `sPrewarmed` | 同左，`openScanner()` 成功即 true |
| `startScan` | `setDecodeCallback(...)` + `startScan()` | 注册 `ACTION_DECODE` 广播接收器 → `startDecode()`；`startDecode()` 返回 false 时注销并 `onStartFailed` |
| 解码结果 | `BarcodeEntity.getBarcodeData()` | 广播 extra：优先 `PropertyID.WEDGE_INTENT_DATA_STRING_TAG` 配置值，回退 `ScanManager.BARCODE_STRING_TAG`（`"barcode_string"`），再回退 `DECODE_DATA_TAG` 字节数组 |
| `stopScan` | `stopScan()` → `setDecodeCallback(null)` | `stopDecode()` → **注销广播接收器**（等价的切断回调链，防止 Fragment 被 SDK 持有泄漏） |
| 去重 | 同码 2 秒窗口 | 不变（`lastEmitCode` / `lastEmitMs`），`stopScan` 必须重置 |
| `release` | `stopScan()` | `stopScan()` → `closeScanner()` |

`ScanManager` 实例做成**进程级共享**（`prewarm` 创建并缓存，controller 复用），
与旧 `BarcodeFactory` 单例语义对齐，避免多个实例各持一条 IPC 连接。

广播 action 名在 `prewarm` 时通过
`getParameterString(new int[]{PropertyID.WEDGE_INTENT_ACTION_NAME})` 读取，
为空则用 `ScanManager.ACTION_DECODE`（`"android.intent.ACTION_DECODE_DATA"`），
与官方 `ScanManagerDemo` 的取法一致。

设备非优博讯 / 无扫码头时 `openScanner()` 失败 → `sPrewarmed=false` →
`ManualScanFragment` 自动走 CameraX，与现状一致。

---

## 6. ProGuard 与 R8 诊断

### 6.1 `app/proguard-rules.pro`

把 `rscja UHF/Barcode SDK` 整段替换为：

```proguard
# Urovo 平台 SDK (libs/platform_sdk_v4.1.0326.jar)
-keep class android.device.** { *; }
-dontwarn android.device.**

# Urovo UHF SDK (libs/URFIDLibrary-v2.5.0718.aar)
# RFIDSDKManager / RfidManager 大量用 DeclaredMethodUtils 反射调方法, 必须整包 keep
-keep class com.ubx.usdk.** { *; }
-dontwarn com.ubx.usdk.**
```

`android.os.IInputActionListener` / `android.content.pm.*` 只被 SEManager /
DeviceManager 的安装类接口使用，本项目不调用，不加 keep（`javap` 已确认
`ScanManager` 不引用它们）。

原有 `-dontwarn com.datalogic.iptech.evl.**` 等 rscja 多平台条码引擎的死引用
`-dontwarn` 一并删除（这些类随旧 AAR 移除后不再出现在 classpath）。

### 6.2 `InventoryApp.checkRfidClasses()`

探针数组换成新 SDK 的 7 个类，注释同步改写（保留「仅 release 有意义 / 看 logcat
`[ClassCheck]`」的验证路径说明）：

```java
"com.ubx.usdk.RFIDSDKManager",
"com.ubx.usdk.rfid.RfidManager",
"com.ubx.usdk.rfid.aidl.IRfidCallback",
"com.ubx.usdk.bean.Tag6C",
"android.device.ScanManager",
"android.device.DeviceManager",
"android.device.scanner.configuration.PropertyID",
```

### 6.3 `app/build.gradle` 注释

`release { minifyEnabled true }` 上方提到 "rscja team.*" 的注释同步改为 Urovo 包名。

---

## 7. 文档

`README.md`：
- 技术栈：`UROVO RFID SDK (rscja DeviceAPI…)` → 拆成两行
  `UROVO 平台 SDK (android.device.ScanManager, platform_sdk_v4.1.0326.jar)` 与
  `UROVO UHF SDK (com.ubx.usdk, URFIDLibrary-v2.5.0718.aar)`
- 手写盘点 / RFID 段落里出现 "rscja" 的表述改为优博讯

---

## 8. 测试

### 8.1 需要改写

| 测试 | 原因 | 新断言 |
|---|---|---|
| `ProguardR8KeepCommentTest` | 断言 `"rscja UHF/Barcode SDK"` 段名与 `com.rscja.*` keep 行 | 段名改为 Urovo，校验 `android.device.**` / `com.ubx.usdk.**` keep 行仍在，仍要求「release-only + ClassCheck 验证路径」注释 |
| `SdkRfidAdapterRaceTest` | 断言 `private volatile RFIDWithUHFUART reader` 与 `pollLoop()` 结构 | 改为回调式不变量：不存在轮询线程、`listeners` 是 `CopyOnWriteArraySet`、`startInventory`/`stopInventory` 走 `lifecycleLock` 且 `compareAndSet` 幂等、`unregisterCallback` 在 `release()` 内 |
| `BarcodeHardwareControllerDecodeCallbackTest` | 断言 `setDecodeCallback(null)` | 改为断言 `stopDecode()` 出现在 `unregisterReceiver(...)` **之前**，且 `lastEmitMs = 0L` / `lastEmitCode = ""` 重置仍在 |
| `InventoryAppR8CommentTest` | 校验 `checkRfidClasses()` 前置注释不谎称 debug 开 R8 | 语义保持，只需注释随改写同步，测试本身可不动 |

### 8.2 不需要改动

- `RfidControllerTest`：Mockito mock `SdkRfidAdapter`，与底层 SDK 无关
- `InventoryAppSetPowerIsAsyncTest`、`InventoryAppFieldsFinalTest`：校验的是 `InventoryApp` 结构与线程切换，保持通过
- `SdkRfidAdapterNoTestSeamTest`：仍要求存在 `public SdkRfidAdapter()` 且无注入式构造器 — 新实现同样满足（不新增测试 seam）

### 8.3 验证命令

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease      # 验证 R8 + ProGuard keep 规则
```

---

## 9. 已知风险

1. **无真机**：条码触发键、盘点距离/功率、SDK 初始化的实际行为只能上机验证。
   本地能保证的只有编译通过、单测通过、release 包 R8 不报 missing class。
2. **平台 jar 版本差**：UHF AAR 编译时链接的是 `platform_sdk_v3.1.221124.jar`，
   本项目用 `v4.1.0326`。已核对唯一用到的 `DeviceManager.getSettingProperty(String)`
   两版都有；若真机上仍有兼容问题，退路是换成 SDK 包内自带的 v3.1 jar
   （`RFIDSDKSample-main/UHF_Demo/app/libs/platform_sdk_v3.1.221124.jar`），
   只需改一行 `implementation files(...)`。
3. **`init` 由同步变异步**：`RfidController` 已有 `INITIALIZING/READY/ERROR` 状态机与
   `isPowered()` UI 分支，无需改动；但若将来有人在 `InventoryApp.onCreate` 之后
   立刻同步依赖 `isConnected()`，需要按「可能尚未 READY」处理。
4. **功率语义**：旧 `setPower` 与新的 `setOutputPower` 都是 0–30 dBm 整数，
   现有 5/15/26/30 预设与默认 26 不需要换算。若目标机型最大功率 < 30，
   `getSupportMaxOutputPower()` 可拿到上限，但当前设计不做额外限幅（与旧 SDK 行为一致）。

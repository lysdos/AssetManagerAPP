# 手持机 SDK 替换为优博讯（Urovo）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 App 的硬件 SDK 从「成为」rscja（`DeviceAPI_ver20230301_release.aar`）整体换成优博讯 `platform_sdk_v4.1.0326.jar` + `URFIDLibrary-v2.5.0718.aar`，RFID 盘点 / 条码扫描 / 功率设置功能行为不变。

**Architecture:** 保持 `SdkRfidAdapter`、`BarcodeHardwareController`、`RfidController` 的公开 API 原样不动，只替换内部实现：RFID 从「轮询线程 + `readTagFromBuffer`」换成「`IRfidCallback` 回调」；条码从「`BarcodeDecoder` 回调」换成「`ScanManager` 广播接收器」。UI / Room / Excel / 会话层零改动。

**Tech Stack:** Java 11 / AndroidX / compileSdk 34 / minSdk 26 / AGP 8.2.2 + Gradle 8.2 / JUnit 4 + Robolectric + Mockito / Urovo `android.device.ScanManager` + `com.ubx.usdk.RfidManager`

**设计文档:** `docs/superpowers/specs/2026-11-10-urovo-sdk-migration-design.md`

## Global Constraints

- 公开 API 不变：`SdkRfidAdapter` / `BarcodeHardwareController` / `RfidController` 的 public 成员签名与语义不得修改，`ui/` 下只有注释与一行日志文案允许改动。
- 功率语义不变：整数 dBm，范围 0–30，默认 26，预设 5/15/26/30，不做单位换算。
- 去重语义不变：硬件条码 2 秒同码去重；RFID 侧不新增去重（`ScanViewModel` 已有）。
- 不改 `app/src/main/java/com/huaxia/inventory/data/`、`excel/`、`ui/` 的逻辑，不动 Room schema。
- 日志统一 `Log.xxx`，禁止 `System.out.println`；注释保持现有中文风格。
- 构建命令在仓库根目录执行：`./gradlew :app:assembleDebug`、`./gradlew :app:testDebugUnitTest`、`./gradlew :app:assembleRelease`。
- 每个任务结束提交一次；若不想提交，跳过各 Task 的 Commit 步骤即可，其它步骤与顺序不受影响。

---

## 文件结构

| 文件 | 责任 |
|---|---|
| `app/libs/platform_sdk_v4.1.0326.jar` | 优博讯平台 SDK（`android.device.ScanManager` / `DeviceManager`） |
| `app/libs/URFIDLibrary-v2.5.0718.aar` | 优博讯 UHF SDK（`com.ubx.usdk.*`）+ 3 个 ABI 的 `.so` |
| `app/src/main/java/com/huaxia/inventory/rfid/SdkRfidAdapter.java` | RFID 适配层；本计划中整文件重写为回调式 |
| `app/src/main/java/com/huaxia/inventory/barcode/BarcodeHardwareController.java` | 硬件条码适配层；本计划中整文件重写为广播式 |
| `app/src/main/java/com/huaxia/inventory/InventoryApp.java` | R8 诊断探针类名单（仅数组 + 注释） |
| `app/proguard-rules.pro` | SDK keep 规则（整段替换） |
| `app/build.gradle` | 依赖与 release 注释 |
| `README.md` | 技术栈说明 |

---

### Task 1: 引入优博讯 SDK 产物与依赖（新旧并存）

**Files:**
- Create: `app/libs/platform_sdk_v4.1.0326.jar`
- Create: `app/libs/URFIDLibrary-v2.5.0718.aar`
- Modify: `app/build.gradle:133`（`implementation files('libs/DeviceAPI_ver20230301_release.aar')` 上方新增两条）

**Interfaces:**
- Consumes: 无
- Produces: classpath 上的 `android.device.ScanManager`、`android.device.scanner.configuration.PropertyID`、`com.ubx.usdk.RFIDSDKManager`、`com.ubx.usdk.rfid.RfidManager`、`com.ubx.usdk.rfid.aidl.IRfidCallback`、`com.ubx.usdk.listener.InitListener`

- [ ] **Step 1: 复制两个 SDK 产物到 app/libs**

```bash
cd /e/Code/AssetManagerAPP
cp "SDK_ReleaseforAndroid-master/SDK Release/platform_sdk_v4.1.0326/platform_sdk_v4.1.0326.jar" app/libs/
cp "SDK_ReleaseforAndroid-master/RFIDSDKSample-main/libs/URFIDLibrary-v2.5.0718.aar" app/libs/
ls -l app/libs/
```

Expected: `app/libs/` 下同时存在 3 个文件（旧 AAR + 新增 2 个）。

- [ ] **Step 2: 在 build.gradle 增加依赖（暂时保留旧 AAR）**

`app/build.gradle` 找到：

```groovy
    implementation files('libs/DeviceAPI_ver20230301_release.aar')
```

替换为：

```groovy
    // 优博讯平台 SDK — 条码 ScanManager / DeviceManager 等平台能力 (android.device.*)
    implementation files('libs/platform_sdk_v4.1.0326.jar')
    // 优博讯 UHF 读写器 SDK — com.ubx.usdk.*, 自带 arm64-v8a / armeabi-v7a / armeabi 的 .so
    implementation files('libs/URFIDLibrary-v2.5.0718.aar')
    // 旧「成为」(rscja) SDK — Task 5 摘除
    implementation files('libs/DeviceAPI_ver20230301_release.aar')
```

- [ ] **Step 3: 编译验证（旧代码仍能编过，新 SDK 不引入冲突）**

Run: `./gradlew :app:compileDebugJavaWithJavac`

Expected: `BUILD SUCCESSFUL`。若出现 `Duplicate class android.device.*`，说明 platform jar 被重复引入，检查 `app/libs` 下是否多放了第二个平台 jar。

- [ ] **Step 4: Commit**

```bash
git add app/libs/platform_sdk_v4.1.0326.jar app/libs/URFIDLibrary-v2.5.0718.aar app/build.gradle
git commit -m "build(sdk): 引入优博讯平台 SDK 与 UHF SDK 产物"
```

---

### Task 2: `SdkRfidAdapter` 切换到优博讯 UHF SDK

**Files:**
- Modify (整文件替换): `app/src/main/java/com/huaxia/inventory/rfid/SdkRfidAdapter.java`
- Modify (整文件替换): `app/src/test/java/com/huaxia/inventory/rfid/SdkRfidAdapterRaceTest.java`

**Interfaces:**
- Consumes: `com.ubx.usdk.RFIDSDKManager.getInstance().init(Context, InitListener)`、`.getRfidManager()`、`.release()`；`RfidManager.startInventory()`（0 = 成功）、`.stopInventory()`、`.setOutputPower(int)`（0 = 成功）、`.getOutputPower()`（负数 = 失败）、`.isConnected()`、`.disConnect()`、`.registerCallback(IRfidCallback)`、`.unregisterCallback(IRfidCallback)`；`IRfidCallback.onInventoryTag(String epc, String data, int rssi)`
- Produces: 与旧实现完全相同的 public API —— `init(Context, Callback)`、`isConnected()`、`isPowered()`、`startInventory()`（0 成功）、`stopInventory()`（0 成功）、`disConnect()`、`power(boolean)`、`setPower(int)`、`getPower()`（失败 -1）、`release()`、`registerCallback(TagListener)`、`unregisterCallback(TagListener)`、`interface Callback { void onStatus(boolean) }`、`interface TagListener { void onTag(String epc, String tid, String rssi) }`

- [ ] **Step 1: 先改写防回归测试（预期失败）**

把 `app/src/test/java/com/huaxia/inventory/rfid/SdkRfidAdapterRaceTest.java` 整体替换为：

```java
package com.huaxia.inventory.rfid;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: SdkRfidAdapter 在换成优博讯 UHF SDK 后是<b>回调式</b>, 下面几条不变量
 * 任一被破坏都会重新引入旧实现的坑:
 * <ol>
 *   <li>不允许再出现轮询线程 ({@code pollLoop} / {@code readTagFromBuffer} /
 *       {@code RFIDWithUHFUART}) — 新 SDK 通过 {@code IRfidCallback} 推标签.</li>
 *   <li>订阅者集合必须是 {@link java.util.concurrent.CopyOnWriteArraySet} —
 *       register/unregister 与 binder 线程上的分发并发.</li>
 *   <li>start/stop 必须串行化并且幂等 (compareAndSet), 否则重复点击扫描按钮会
 *       让 scanning 状态与 SDK 实际状态错位.</li>
 *   <li>release() 必须先 unregisterCallback 再置空 reader — 否则 SDK 仍持有可能
 *       已被释放的适配器回调.</li>
 * </ol>
 */
public class SdkRfidAdapterRaceTest {

    @Test
    public void source_hasNoPollingThread() throws Exception {
        String text = readSource();
        assertFalse("SdkRfidAdapter must NOT keep the old polling design. Source:\n" + text,
                text.contains("RFIDWithUHFUART"));
        assertFalse("SdkRfidAdapter must NOT keep pollLoop(). Source:\n" + text,
                text.contains("pollLoop"));
        assertFalse("SdkRfidAdapter must NOT call readTagFromBuffer(). Source:\n" + text,
                text.contains("readTagFromBuffer"));
    }

    @Test
    public void listeners_isCopyOnWriteArraySet() throws Exception {
        String text = readSource();
        assertTrue("listeners must stay a CopyOnWriteArraySet — binder 线程分发与 "
                        + "register/unregister 并发. Source:\n" + text,
                text.contains("new CopyOnWriteArraySet<>()"));
    }

    @Test
    public void init_registersSdkCallback() throws Exception {
        String text = readSource();
        String body = extractMethodBody(text, "public void init(Context appCtx, Callback cb)");
        assertTrue("init body not found.\nSource:\n" + text, body.length() > 0);
        assertTrue("init() must register the IRfidCallback with the Urovo SDK. Body:\n" + body,
                body.contains("registerCallback(sdkCallback)"));
        assertTrue("init() must obtain the reader from RFIDSDKManager. Body:\n" + body,
                body.contains("getRfidManager()"));
    }

    @Test
    public void startStop_areSerializedAndIdempotent() throws Exception {
        String text = readSource();
        String start = extractMethodBody(text, "public int startInventory()");
        assertTrue("startInventory body not found.\nSource:\n" + text, start.length() > 0);
        assertTrue("startInventory must serialize on lifecycleLock. Body:\n" + start,
                start.contains("synchronized (lifecycleLock)"));
        assertTrue("startInventory must be idempotent via scanning.get(). Body:\n" + start,
                start.contains("scanning.get()"));

        String stop = extractMethodBody(text, "public int stopInventory()");
        assertTrue("stopInventory body not found.\nSource:\n" + text, stop.length() > 0);
        assertTrue("stopInventory must serialize on lifecycleLock. Body:\n" + stop,
                stop.contains("synchronized (lifecycleLock)"));
        assertTrue("stopInventory must be idempotent via compareAndSet. Body:\n" + stop,
                stop.contains("compareAndSet(true, false)"));
    }

    @Test
    public void release_unregistersCallbackBeforeClearingReader() throws Exception {
        String text = readSource();
        String body = extractMethodBody(text, "public void release()");
        assertTrue("release body not found.\nSource:\n" + text, body.length() > 0);
        int unregisterIdx = body.indexOf("unregisterCallback(sdkCallback)");
        int nullIdx = body.indexOf("reader = null");
        assertTrue("release() must unregisterCallback(sdkCallback) before reader = null. "
                + "Body:\n" + body, unregisterIdx >= 0 && nullIdx > unregisterIdx);
    }

    private static String readSource() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/rfid/SdkRfidAdapter.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/rfid/SdkRfidAdapter.java");
        }
        assertTrue("SdkRfidAdapter.java not found: " + source.getAbsolutePath(),
                source.exists());
        return new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
    }

    private static String extractMethodBody(String text, String signature) {
        int sigStart = text.indexOf(signature);
        if (sigStart < 0) return "";
        int braceStart = text.indexOf('{', sigStart);
        if (braceStart < 0) return "";
        int depth = 1;
        int i = braceStart + 1;
        while (i < text.length() && depth > 0) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        if (depth != 0) return "";
        return text.substring(braceStart + 1, i - 1);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.rfid.SdkRfidAdapterRaceTest`

Expected: FAIL —— `source_hasNoPollingThread` 因源码仍含 `RFIDWithUHFUART` 失败，`init_registersSdkCallback` 因找不到 `registerCallback(sdkCallback)` 失败。

- [ ] **Step 3: 整文件替换 `SdkRfidAdapter.java`**

```java
package com.huaxia.inventory.rfid;

import android.content.Context;
import android.util.Log;

import com.ubx.usdk.RFIDSDKManager;
import com.ubx.usdk.rfid.RfidManager;
import com.ubx.usdk.rfid.aidl.IRfidCallback;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 优博讯 (UROVO) UHF RFID SDK 封装
 * ({@code URFIDLibrary-v2.5.0718.aar}, 包名 {@code com.ubx.usdk}).
 *
 * <p>与旧 rscja SDK 的差异:</p>
 * <ul>
 *   <li>旧 SDK 是<b>轮询</b>模型, 适配器里要养一条 {@code rfid-poll} 守护线程
 *       定期拉取标签; 新 SDK 是<b>回调</b>模型
 *       ({@link IRfidCallback#onInventoryTag}), 一条线程都不用起.</li>
 *   <li>{@code init} 由同步 (返回 boolean) 变成异步
 *       ({@code InitListener.onStatus}).</li>
 *   <li>功率 API 换成 {@code setOutputPower/getOutputPower}, 语义不变: 整数
 *       dBm, 0-30, 返回码 0 = 成功.</li>
 * </ul>
 *
 * <p>对外 API 与旧实现完全一致, {@link RfidController} / UI 层无需改动.</p>
 */
public class SdkRfidAdapter {

    private static final String TAG = "SdkRfidAdapter";

    /**
     * SDK 读写器句柄. {@code volatile}: init 回调通常落在 SDK 自己的线程, 而
     * start/stop/setPower 由主线程或 diskIO 线程调用 — 用 volatile 保证
     * "init 写入 reader" 对下游调用方可见; {@link #release()} 把它置 null 后,
     * 其它线程下一次 {@link #requireReader()} 会重新抛 IllegalStateException,
     * 而不是继续用已释放的实例.
     */
    private volatile RfidManager reader;
    private final AtomicBoolean powered = new AtomicBoolean(false);
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final Set<TagListener> listeners = new CopyOnWriteArraySet<>();
    /** 串行化 start/stop/release — 防止并发调用互相覆盖 scanning 状态. */
    private final Object lifecycleLock = new Object();

    /**
     * SDK 回调. 在 binder 线程触发, 只做 fan-out, 不碰 UI.
     * init 成功时注册一次, {@link #release()} 时注销.
     */
    private final IRfidCallback sdkCallback = new IRfidCallback() {
        @Override
        public void onInventoryTag(String epc, String data, int rssi) {
            dispatch(epc, data, rssi);
        }

        @Override
        public void onInventoryTagEnd() {
            // 一轮盘存结束. 连续盘存模式下 SDK 会自己开下一轮, 这里不需要做事 —
            // scanning 只由 startInventory()/stopInventory() 驱动.
        }
    };

    /** Default constructor — 真正的 SDK 句柄在 {@link #init} 里拿. */
    public SdkRfidAdapter() {
    }

    private RfidManager requireReader() {
        if (reader == null) {
            try {
                reader = RFIDSDKManager.getInstance().getRfidManager();
            } catch (Throwable t) {
                throw new IllegalStateException("UHF reader unavailable", t);
            }
            if (reader == null) {
                throw new IllegalStateException("UHF reader unavailable");
            }
        }
        return reader;
    }

    /** Single-shot readiness callback used during {@link #init(Context, Callback)}. */
    public interface Callback {
        /**
         * @param ready {@code true} once the SDK has finished initializing;
         *              {@code false} on failure.
         */
        void onStatus(boolean ready);
    }

    /** Per-tag callback fired by the SDK for each tag observed. */
    public interface TagListener {
        void onTag(String epc, String tid, String rssi);
    }

    /** Whether the last {@link #power(boolean)} call marked the module on. */
    public boolean isPowered() {
        return powered.get();
    }

    /**
     * Initialize the UHF SDK. The {@code Callback} is invoked exactly once.
     *
     * <p>注意: 优博讯 SDK 的 init 是<b>异步</b>的 — 回调可能落在 SDK 自己的线程.
     * 调用方 (RfidController / InventoryApp) 本来就是"等回调再判 ready", 无需另做切线程.</p>
     */
    public void init(Context appCtx, Callback cb) {
        Log.i("UHFInit", "[SdkRfidAdapter] init() entered");
        try {
            RFIDSDKManager.getInstance().init(appCtx, status -> {
                boolean ok = status;
                if (ok) {
                    RfidManager rm = null;
                    try {
                        rm = RFIDSDKManager.getInstance().getRfidManager();
                    } catch (Throwable t) {
                        Log.w(TAG, "getRfidManager() failed", t);
                    }
                    if (rm != null) {
                        try {
                            rm.registerCallback(sdkCallback);
                            reader = rm;
                        } catch (Throwable t) {
                            Log.w(TAG, "registerCallback() failed", t);
                            ok = false;
                        }
                    } else {
                        Log.w(TAG, "init() reported ready but getRfidManager() returned null");
                        ok = false;
                    }
                }
                powered.set(ok);
                if (cb != null) cb.onStatus(ok);
                Log.i("UHFInit", "[SdkRfidAdapter] init() done, ok=" + ok);
            });
        } catch (Throwable t) {
            Log.e("UHFInit", "[SdkRfidAdapter] init() threw " + t.getClass().getName()
                    + ": " + t.getMessage(), t);
            powered.set(false);
            if (cb != null) cb.onStatus(false);
        }
    }

    /** Whether the underlying RFID module is currently connected. */
    public boolean isConnected() {
        RfidManager r = reader;
        if (r == null) return false;
        try {
            return r.isConnected();
        } catch (Throwable t) {
            Log.w(TAG, "isConnected() failed", t);
            return false;
        }
    }

    /**
     * 设置RFID读写器功率。
     *
     * @param power 功率值，范围 0-30 dBm
     * @return {@code true} 设置成功，{@code false} 设置失败
     */
    public boolean setPower(int power) {
        if (power < 0 || power > 30) {
            Log.w(TAG, "setPower() invalid power value: " + power + ", must be 0-30");
            return false;
        }
        try {
            int rc = requireReader().setOutputPower(power);
            Log.i(TAG, "setOutputPower(" + power + ") returned " + rc);
            return rc == 0;
        } catch (Throwable t) {
            Log.w(TAG, "setPower() failed", t);
            return false;
        }
    }

    /**
     * 获取当前RFID读写器功率。
     *
     * @return 当前功率值(dBm)，获取失败返回 -1
     */
    public int getPower() {
        try {
            int power = requireReader().getOutputPower();
            Log.i(TAG, "getOutputPower() returned " + power);
            return power < 0 ? -1 : power;
        } catch (Throwable t) {
            Log.w(TAG, "getPower() failed", t);
            return -1;
        }
    }

    /** Register a per-tag callback with the adapter. */
    public void registerCallback(TagListener l) {
        listeners.add(l);
    }

    /** Unregister a previously-registered per-tag callback. */
    public void unregisterCallback(TagListener l) {
        listeners.remove(l);
    }

    /**
     * Begin continuous EPC inventory.
     *
     * @return {@code 0} on success, non-zero on failure.
     */
    public int startInventory() {
        synchronized (lifecycleLock) {
            if (scanning.get()) {
                return 0;
            }
            int rc;
            try {
                rc = requireReader().startInventory();
            } catch (Throwable t) {
                Log.w(TAG, "startInventory() failed", t);
                return -1;
            }
            if (rc != 0) {
                Log.w(TAG, "startInventory() returned " + rc);
                return -1;
            }
            scanning.set(true);
            return 0;
        }
    }

    /**
     * Stop continuous EPC inventory. 幂等: 没在扫描时直接返回 0.
     */
    public int stopInventory() {
        synchronized (lifecycleLock) {
            if (!scanning.compareAndSet(true, false)) {
                return 0;
            }
            try {
                requireReader().stopInventory();
            } catch (Throwable ex) {
                Log.w(TAG, "stopInventory() failed", ex);
            }
            return 0;
        }
    }

    /** Disconnect the RFID module. */
    public void disConnect() {
        RfidManager r = reader;
        if (r == null) return;
        try {
            r.disConnect();
        } catch (Throwable t) {
            Log.w(TAG, "disConnect() failed", t);
        }
    }

    /**
     * Power the RFID module on/off. 新 SDK 没有独立的"上电"原语, 这里保持旧实现
     * 的语义: 记录期望状态, 关电时先停盘点.
     */
    public void power(boolean on) {
        try {
            if (!on) {
                stopInventory();
            }
            powered.set(on);
        } catch (Throwable t) {
            Log.w(TAG, "power() failed", t);
        }
    }

    /**
     * Release SDK resources. Call from {@code onDestroy()} of the owning component.
     *
     * <p>顺序固定: stopInventory → unregisterCallback → SDK release → 把
     * {@link #reader} 置 null → 清 {@link #powered}.</p>
     */
    public void release() {
        stopInventory();
        RfidManager r = reader;
        if (r != null) {
            try {
                r.unregisterCallback(sdkCallback);
            } catch (Throwable t) {
                Log.w(TAG, "unregisterCallback() failed", t);
            }
        }
        try {
            RFIDSDKManager.getInstance().release();
        } catch (Throwable t) {
            Log.w(TAG, "RFIDSDKManager.release() failed", t);
        }
        powered.set(false);
        // 留作幂等: 第二次 release 不抛异常.
        reader = null;
    }

    /**
     * Fan-out 到订阅者. 每个 listener 单独 try/catch — 一个订阅者抛异常不能让
     * 其余订阅者漏掉这条标签.
     */
    private void dispatch(String epc, String data, int rssi) {
        if (epc == null || epc.isEmpty()) return;
        // 旧 SDK 未读 TID 时 getTid() 为空; 新 SDK 用 data (memory bank) 表达同一信息,
        // 空串统一归一成 null, 保持 ScanRecord.tid 的取值域不变.
        String tid = (data == null || data.isEmpty()) ? null : data;
        String rssiStr = String.valueOf(rssi);
        for (TagListener l : listeners) {
            try {
                l.onTag(epc, tid, rssiStr);
            } catch (Throwable t) {
                Log.w(TAG, "TagListener threw", t);
            }
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.rfid.SdkRfidAdapterRaceTest --tests com.huaxia.inventory.rfid.RfidControllerTest --tests com.huaxia.inventory.rfid.SdkRfidAdapterNoTestSeamTest`

Expected: `BUILD SUCCESSFUL`，全部用例 PASS。`RfidControllerTest` 用 Mockito mock `SdkRfidAdapter`，不受实现替换影响。

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:compileDebugJavaWithJavac`

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/huaxia/inventory/rfid/SdkRfidAdapter.java \
        app/src/test/java/com/huaxia/inventory/rfid/SdkRfidAdapterRaceTest.java
git commit -m "refactor(rfid): SdkRfidAdapter 换用优博讯 UHF SDK 回调式盘存"
```

---

### Task 3: `BarcodeHardwareController` 切换到 `ScanManager`

**Files:**
- Modify (整文件替换): `app/src/main/java/com/huaxia/inventory/barcode/BarcodeHardwareController.java`
- Modify (整文件替换): `app/src/test/java/com/huaxia/inventory/barcode/BarcodeHardwareControllerDecodeCallbackTest.java`
- Modify: `app/src/main/java/com/huaxia/inventory/ui/manual/ManualScanFragment.java:209`（日志文案）
- Modify: `app/src/main/java/com/huaxia/inventory/barcode/BarcodeCameraController.java:36`（注释）
- Modify: `app/src/main/java/com/huaxia/inventory/ui/manual/ManualScanViewModel.java:220`（Javadoc）

**Interfaces:**
- Consumes: `android.device.ScanManager`（`openScanner()` / `getScannerState()` / `setUnBeep()` / `switchOutputMode(int)` / `startDecode()` / `stopDecode()` / `getParameterString(int[])`）、常量 `ScanManager.ACTION_DECODE` / `BARCODE_STRING_TAG` / `DECODE_DATA_TAG` / `BARCODE_LENGTH_TAG`、`PropertyID.WEDGE_INTENT_ACTION_NAME`
- Produces: 与旧实现完全相同的 public API —— `static void prewarm(Context)`、`static boolean isReady()`、`void startScan(Callback)`、`void stopScan()`、`void release()`、`interface Callback { void onBarcodeDetected(String); void onStartFailed(String); }`

- [ ] **Step 1: 先改写防回归测试（预期失败）**

把 `app/src/test/java/com/huaxia/inventory/barcode/BarcodeHardwareControllerDecodeCallbackTest.java` 整体替换为：

```java
package com.huaxia.inventory.barcode;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: BarcodeHardwareController.stopScan 必须按固定顺序收尾.
 *
 * <p>优博讯 ScanManager 用广播回调解码结果, 等价于旧 rscja 的 DecodeCallback:
 * 先 {@code stopDecode()} 停掉解码会话, 再注销广播接收器 —— 否则 SDK 仍可能
 * 发出一条 in-flight 广播, 落进 emit() 命中已置 null 的 activeCallback;
 * 反过来若忘了注销, Application Context 会一直持有接收器与 controller 的强引用,
 * 反复进出手动盘点页后 Fragment 视图链不被回收.</p>
 *
 * <p>同时 stopScan 必须重置 {@code lastEmitMs/lastEmitCode} 2s 去抖窗口 —— 否则
 * 重建 controller 时首条相同码被去重窗口吞掉.</p>
 */
public class BarcodeHardwareControllerDecodeCallbackTest {

    @Test
    public void stopScan_stopsDecodeThenUnregistersReceiver_andResetsDebounce()
            throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/barcode/BarcodeHardwareController.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/barcode/"
                            + "BarcodeHardwareController.java");
        }
        assertTrue("source not found: " + source.getAbsolutePath(), source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        String body = extractMethodBody(text, "public void stopScan()");
        assertTrue("stopScan body not found", body.length() > 0);

        // 必须停解码会话.
        int stopIdx = body.indexOf("stopDecode()");
        assertTrue("stopScan must call stopDecode() — else the SDK keeps producing "
                + "decode broadcasts. Body:\n" + body, stopIdx >= 0);

        // 必须注销广播接收器 (等价于旧的 setDecodeCallback(null)).
        int unregisterIdx = body.indexOf("unregisterDecodeReceiver()");
        assertTrue("stopScan must unregister the decode receiver — else Application "
                + "Context keeps the controller alive, leaking Fragment. Body:\n" + body,
                unregisterIdx >= 0);

        // 顺序: 先 stopDecode 再注销, 避免 in-flight 广播落进 emit().
        assertTrue("stopDecode() must precede unregisterDecodeReceiver(). Body:\n" + body,
                unregisterIdx > stopIdx);

        // 必须重置 2s 去抖窗口.
        assertTrue("stopScan must reset 2s debounce (lastEmitMs=0, lastEmitCode=\"\") so "
                + "a freshly rebuilt controller does not inherit stale state. Body:\n" + body,
                body.contains("lastEmitMs = 0L") && body.contains("lastEmitCode = \"\""));
    }

    private static String extractMethodBody(String text, String signature) {
        int sigStart = text.indexOf(signature);
        if (sigStart < 0) return "";
        int braceStart = text.indexOf('{', sigStart);
        if (braceStart < 0) return "";
        int depth = 1;
        int i = braceStart + 1;
        while (i < text.length() && depth > 0) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        if (depth != 0) return "";
        return text.substring(braceStart + 1, i - 1);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.barcode.BarcodeHardwareControllerDecodeCallbackTest`

Expected: FAIL —— 旧源码 `stopScan` 里没有 `stopDecode()` / `unregisterDecodeReceiver()`。

- [ ] **Step 3: 整文件替换 `BarcodeHardwareController.java`**

```java
package com.huaxia.inventory.barcode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.device.ScanManager;
import android.device.scanner.configuration.PropertyID;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;

/**
 * 优博讯 (UROVO) 硬件扫码 SDK 封装 (PDA 内置物理扫码头).
 *
 * <p>底层是 {@code android.device.ScanManager} (来自
 * {@code platform_sdk_v4.1.0326.jar}). 与旧 rscja BarcodeDecoder 的差异:
 * 结果不走 callback, 而是<b>广播</b> —— prewarm 时把输出模式切到 INTENT(0),
 * startScan 时注册解码广播接收器, stopScan 时注销.</p>
 *
 * <p>使用模式 (ManualScanFragment 无需改动):
 * <ul>
 *   <li>App 启动时 {@link #prewarm(Context)} 在后台线程 open SDK + 关闭自带成功音</li>
 *   <li>{@link #isReady()} 判断硬件 SDK 是否可用, 不可用则降级到 CameraX</li>
 *   <li>{@link #startScan(Callback)} / {@link #stopScan()} 控制解码会话</li>
 * </ul>
 *
 * <p>硬件 SDK 自带的成功音已关闭, 由 {@link com.huaxia.inventory.util.BeepPlayer}
 * 在主线程统一触发, 避免重复 beep.</p>
 *
 * <p>注意: {@code ScanManager} 与已打开的扫码头是<b>进程级</b>资源, 缓存到静态字段
 * (与旧 {@code BarcodeFactory} 单例语义一致). {@link #release()} 只停当前会话,
 * <b>不</b> closeScanner —— 否则同一次 App 运行里再次进入手动盘点页起不来.</p>
 */
public class BarcodeHardwareController {

    private static final String TAG = "BarcodeHw";
    private static final long DEBOUNCE_MS = 2000L;
    /** ScanManager.switchOutputMode: 0 = INTENT 广播模式, 1 = FOCUS 键盘模式. */
    private static final int OUTPUT_MODE_INTENT = 0;

    /** 共享状态: 预热是否成功. InventoryApp.onCreate 设, startScan 时读. */
    private static volatile boolean sPrewarmed = false;
    /** 进程级 ScanManager 实例 (prewarm 创建). */
    private static volatile ScanManager sScanManager;
    /** 注册广播用的 Application Context (prewarm 时保存). */
    private static volatile Context sAppCtx;
    /** 解码广播 action — 优先读设备配置, 读不到回退 ScanManager.ACTION_DECODE. */
    private static volatile String sDecodeAction = ScanManager.ACTION_DECODE;

    public interface Callback {
        /** 主线程回调. rawCode 是硬件 SDK 解到的字面字符串, 跟文本框输入走同一路径. */
        @MainThread void onBarcodeDetected(@NonNull String rawCode);
        /** startScan 失败时回调 (比如硬件中途断连). */
        @MainThread default void onStartFailed(@NonNull String reason) {}
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Callback activeCallback;
    private BroadcastReceiver receiver;
    private long lastEmitMs = 0L;
    private String lastEmitCode = "";

    public BarcodeHardwareController() {
        // SDK 句柄由 prewarm 建好并缓存到静态字段, 这里只持有会话状态.
    }

    /**
     * App 启动时调一次 (InventoryApp.onCreate 后台线程).
     * 同步打开硬件 SDK, 设置全局共享的 sPrewarmed 标志.
     */
    public static void prewarm(@NonNull Context appCtx) {
        sAppCtx = appCtx.getApplicationContext();
        try {
            ScanManager sm = sScanManager;
            if (sm == null) {
                sm = new ScanManager();
                sScanManager = sm;
            }
            boolean ok = sm.getScannerState();
            if (!ok) {
                ok = sm.openScanner();
            }
            if (ok) {
                // 关闭硬件 SDK 自带的成功音, 由 BeepPlayer 统一管理
                try {
                    sm.setUnBeep();
                } catch (Throwable t) {
                    Log.w(TAG, "setUnBeep() failed (ignored): " + t.getMessage());
                }
                // INTENT 模式: 解码结果走广播而不是注入聚焦的输入框 ——
                // 手动盘点页扫码时 inputEt 是聚焦的, FOCUS 模式会把条码重复写进输入框.
                try {
                    sm.switchOutputMode(OUTPUT_MODE_INTENT);
                } catch (Throwable t) {
                    Log.w(TAG, "switchOutputMode(INTENT) failed (ignored): " + t.getMessage());
                }
                String action = resolveDecodeAction(sm);
                if (action != null && !action.isEmpty()) {
                    sDecodeAction = action;
                }
            }
            sPrewarmed = ok;
            Log.i(TAG, "[BarcodeHardwareController] prewarm ok=" + ok
                    + ", action=" + sDecodeAction);
        } catch (Throwable t) {
            Log.w(TAG, "[BarcodeHardwareController] prewarm failed: " + t.getMessage(), t);
            sPrewarmed = false;
        }
    }

    /** 设备可能把 action 配成自定义值; 读不到就用 SDK 默认常量. */
    private static String resolveDecodeAction(ScanManager sm) {
        try {
            String[] values = sm.getParameterString(
                    new int[]{PropertyID.WEDGE_INTENT_ACTION_NAME});
            if (values != null && values.length > 0
                    && values[0] != null && !values[0].isEmpty()) {
                return values[0];
            }
        } catch (Throwable t) {
            Log.w(TAG, "getParameterString(WEDGE_INTENT_ACTION_NAME) failed (ignored): "
                    + t.getMessage());
        }
        return ScanManager.ACTION_DECODE;
    }

    /** 硬件 SDK 是否可用 (prewarm 成功). */
    public static boolean isReady() {
        return sPrewarmed;
    }

    @MainThread
    public void startScan(@NonNull Callback callback) {
        if (!sPrewarmed || sScanManager == null) {
            callback.onStartFailed("not prewarmed");
            return;
        }
        this.activeCallback = callback;
        registerDecodeReceiver();
        boolean ok;
        try {
            ok = sScanManager.startDecode();
        } catch (Throwable t) {
            Log.w(TAG, "startDecode() failed", t);
            ok = false;
        }
        if (!ok) {
            // 起不来就立刻收拾干净, 否则下一次 startScan 会看到 receiver != null
            // 直接跳过注册, 但广播其实没人收.
            unregisterDecodeReceiver();
            callback.onStartFailed("startDecode failed");
            return;
        }
        Log.i(TAG, "[BarcodeHardwareController] startScan issued");
    }

    @MainThread
    public void stopScan() {
        Log.i(TAG, "[BarcodeHardwareController] stopScan");
        // 顺序固定: 先停解码会话 (不再产生新广播), 再注销接收器 — 否则 in-flight
        // 广播仍可能落进 emit(), 白跑一次. 这正是旧实现 "stopScan() 先于
        // setDecodeCallback(null)" 的等价语义.
        if (sScanManager != null) {
            try {
                sScanManager.stopDecode();
            } catch (Throwable t) {
                Log.w(TAG, "stopDecode() failed", t);
            }
        }
        unregisterDecodeReceiver();
        activeCallback = null;
        // 重置 2s 去抖窗口: release 后重建 controller 时, 旧 lastEmitMs 仍生效会吞掉
        // 重启后第一个扫到的相同码, 看起来像"扫码没反应".
        synchronized (this) {
            lastEmitMs = 0L;
            lastEmitCode = "";
        }
    }

    public void release() {
        // 只停会话, 不 closeScanner: 扫码头是进程级资源, 下一次进手动盘点页还要用.
        stopScan();
    }

    private void registerDecodeReceiver() {
        if (receiver != null) return;
        final Context ctx = sAppCtx;
        if (ctx == null) {
            Log.w(TAG, "registerDecodeReceiver: app context missing");
            return;
        }
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String raw = extractBarcode(intent);
                if (raw == null || raw.isEmpty()) return;
                if (isDuplicate(raw)) return;
                emit(raw);
            }
        };
        IntentFilter filter = new IntentFilter(sDecodeAction);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // targetSdk 34: 动态注册非系统广播必须显式声明导出性.
            // RECEIVER_EXPORTED — 解码广播来自扫码服务 (另一个应用).
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, filter);
        }
    }

    private void unregisterDecodeReceiver() {
        BroadcastReceiver r = receiver;
        receiver = null;
        Context ctx = sAppCtx;
        if (r == null || ctx == null) return;
        try {
            ctx.unregisterReceiver(r);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "receiver already unregistered", e);
        }
    }

    /** 从广播里取条码字符串: 优先 string extra, 回退 byte[] extra (按 length 截断). */
    private static String extractBarcode(Intent intent) {
        if (intent == null) return null;
        String s = intent.getStringExtra(ScanManager.BARCODE_STRING_TAG);
        if (s != null && !s.isEmpty()) return s;
        byte[] data = intent.getByteArrayExtra(ScanManager.DECODE_DATA_TAG);
        if (data == null || data.length == 0) return null;
        int len = intent.getIntExtra(ScanManager.BARCODE_LENGTH_TAG, data.length);
        if (len <= 0) return null;
        try {
            return new String(data, 0, Math.min(len, data.length), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.w(TAG, "decode byte[] extra failed", t);
            return null;
        }
    }

    /** 2 秒同码去重 (用户长按扳机会多次触发). */
    private boolean isDuplicate(String raw) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (raw.equals(lastEmitCode) && now - lastEmitMs < DEBOUNCE_MS) {
                return true;
            }
            lastEmitCode = raw;
            lastEmitMs = now;
            return false;
        }
    }

    private void emit(String raw) {
        // 动态接收器已在主线程回调, 仍 post 一次: 与旧实现的主线程派发语义保持一致.
        mainHandler.post(() -> {
            Callback c = activeCallback;
            if (c != null) c.onBarcodeDetected(raw);
        });
    }
}
```

- [ ] **Step 4: 同步更新三处注释 / 日志文案**

`app/src/main/java/com/huaxia/inventory/ui/manual/ManualScanFragment.java:209`

```java
            Log.i(TAG, "[ManualScanFragment] using hardware barcode decoder (rscja)");
```
改为：
```java
            Log.i(TAG, "[ManualScanFragment] using hardware barcode decoder (urovo ScanManager)");
```

`app/src/main/java/com/huaxia/inventory/barcode/BarcodeCameraController.java:36`

```java
 * <p>Drop-in replacement for the rscja barcode SDK path. Lifecycle is bound
```
改为：
```java
 * <p>Fallback when the Urovo hardware scanner is unavailable. Lifecycle is bound
```

`app/src/main/java/com/huaxia/inventory/ui/manual/ManualScanViewModel.java:220`

```java
     * <p>SDK callback (rscja DeviceAPI barcode module, TBD) is expected to call
     * this directly. For now nothing in the app invokes it — the manual-input path
     * still goes through {@link #queryByEpc(String)} + {@link #confirmCheck}.</p>
```
改为：
```java
     * <p>Called by {@code ManualScanFragment} for both sources — the Urovo
     * hardware scanner ({@code ScanManager} broadcast) and the CameraX + ML Kit
     * fallback. The manual-input path still goes through
     * {@link #queryByEpc(String)} + {@link #confirmCheck}.</p>
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.barcode.BarcodeHardwareControllerDecodeCallbackTest`

Expected: PASS。

- [ ] **Step 6: 编译验证**

Run: `./gradlew :app:compileDebugJavaWithJavac`

Expected: `BUILD SUCCESSFUL`。若报 `RECEIVER_EXPORTED` 找不到，说明 compileSdk < 33 —— 本项目 compileSdk 34，正常不会出现。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/huaxia/inventory/barcode/BarcodeHardwareController.java \
        app/src/main/java/com/huaxia/inventory/barcode/BarcodeCameraController.java \
        app/src/main/java/com/huaxia/inventory/ui/manual/ManualScanFragment.java \
        app/src/main/java/com/huaxia/inventory/ui/manual/ManualScanViewModel.java \
        app/src/test/java/com/huaxia/inventory/barcode/BarcodeHardwareControllerDecodeCallbackTest.java
git commit -m "refactor(barcode): 硬件扫码换用优博讯 ScanManager 广播模式"
```

---

### Task 4: ProGuard keep 规则与 R8 诊断探针

**Files:**
- Modify: `app/proguard-rules.pro:8-50`（rscja 整段 → Urovo 段）
- Modify: `app/src/main/java/com/huaxia/inventory/InventoryApp.java:101-165`
- Modify: `app/src/test/java/com/huaxia/inventory/ProguardR8KeepCommentTest.java`（整文件替换）
- Modify: `app/build.gradle:74-75`（release 注释）

**Interfaces:**
- Consumes: Task 2 / Task 3 产出的类名
- Produces: release 构建下 `Class.forName` 可加载 7 个 SDK 类；`ProguardR8KeepCommentTest` 守护 keep 段

- [ ] **Step 1: 先改写守卫测试（预期失败）**

把 `app/src/test/java/com/huaxia/inventory/ProguardR8KeepCommentTest.java` 整体替换为：

```java
package com.huaxia.inventory;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: proguard-rules.pro 的 Urovo keep 段必须标注是 release-only, 与
 * {@code build.gradle: debug { minifyEnabled false }} 配套 — debug 不走 R8,
 * 不要误以为"-keep 在 debug 也需要".
 *
 * <p>另外必须含验证指引, 让维护者知道 release 出问题时如何用
 * {@code InventoryApp.checkRfidClasses} + logcat 排查.</p>
 */
public class ProguardR8KeepCommentTest {

    @Test
    public void urovoKeepSection_documentsReleaseOnly_andVerificationPath() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir, "app/proguard-rules.pro");
        if (!source.exists()) {
            source = new File(moduleDir, "proguard-rules.pro");
        }
        assertTrue("proguard-rules.pro not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 段标题必须含 "Urovo" (确认是同一段)
        assertTrue("proguard-rules.pro must contain a Urovo SDK keep section. Source:\n"
                + text, text.contains("Urovo UHF/Barcode SDK"));

        // 段必须标记 release-only (避免误认为 debug 也要走 R8)
        assertTrue("Urovo keep section must document release-only intent (debug has "
                + "minifyEnabled false). Source:\n" + text,
                text.contains("Release-only")
                        || text.contains("release-only")
                        || text.contains("release build"));
        assertFalse("Urovo keep section MUST NOT mislead to think debug is minified. "
                + "Source:\n" + text,
                text.contains("debug 也走 R8")
                        || text.contains("debug must minify"));

        // 必须有验证路径 (ClassCheck / logcat / [ClassCheck])
        assertTrue("Urovo keep section must document verification path (logcat / "
                + "ClassCheck) so release-time issues can be diagnosed. Source:\n"
                + text,
                text.contains("ClassCheck") || text.contains("[ClassCheck]"));

        // 必须保持 Urovo keep 规则不被破坏
        for (String keep : new String[]{
                "-keep class android.device.** { *; }",
                "-keep class com.ubx.usdk.** { *; }"
        }) {
            assertTrue("Required keep rule must remain: " + keep + ". Source:\n"
                    + text, text.contains(keep));
        }

        // 旧 rscja keep 规则不得残留 — 旧 AAR 已从依赖摘除, 留着是误导
        assertFalse("rscja keep rules must be gone after the migration. Source:\n" + text,
                text.contains("com.rscja."));
    }

    @Test
    public void inventoryApp_checkRfidClasses_pointsToProguardDoc() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/InventoryApp.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/InventoryApp.java");
        }
        assertTrue("InventoryApp.java not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // checkRfidClasses 方法上的注释必须指向 proguard-rules.pro
        int idx = text.indexOf("private void checkRfidClasses()");
        assertTrue("checkRfidClasses must exist. Source:\n" + text, idx >= 0);
        int javadocStart = text.lastIndexOf("/**", idx);
        if (javadocStart < 0) {
            int lineStart = text.lastIndexOf('\n', idx - 1);
            String line = text.substring(lineStart + 1, idx).trim();
            if (!line.startsWith("//")) {
                line = text.substring(text.lastIndexOf("        // ", idx - 1) + 1, idx).trim();
            }
            assertTrue("checkRfidClasses must have a comment referencing proguard-rules.pro. "
                    + "Line:\n" + line, line.contains("proguard-rules.pro"));
        } else {
            String javadoc = text.substring(javadocStart, idx);
            assertTrue("checkRfidClasses javadoc must reference proguard-rules.pro for "
                    + "verification step. Javadoc:\n" + javadoc,
                    javadoc.contains("proguard-rules.pro"));
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.ProguardR8KeepCommentTest`

Expected: FAIL —— `proguard-rules.pro` 里还是 rscja 段。

- [ ] **Step 3: 替换 proguard-rules.pro 的 SDK 段**

把 `app/proguard-rules.pro` 中从下面这行开始：

```proguard
# ============================================================================
# rscja UHF/Barcode SDK (libs/DeviceAPI_ver20230301_release.aar)
```

到这段结束（即 `-dontwarn vendor.mediatek.hardware.zebra.**` 这一行为止）的整块内容：

```proguard
-dontwarn com.datalogic.iptech.evl.**
-dontwarn com.imagealgorithmlab.barcode.**
-dontwarn com.rscja.cwq.decodeapi.**
-dontwarn vendor.mediatek.hardware.zebra.**
```

整体替换为：

```proguard
# ============================================================================
# Urovo UHF/Barcode SDK
#   libs/platform_sdk_v4.1.0326.jar    → android.device.*
#   libs/URFIDLibrary-v2.5.0718.aar    → com.ubx.usdk.*
# ============================================================================
# Release-only keep — debug build 不走 R8 (build.gradle debug { minifyEnabled false }),
# 但 release build 必须 keep 这些类, 漏一个 = 启动期 Class.forName 抛
# ClassNotFoundException, 现场只看到"SDK 初始化失败"无具体堆栈.
#
# 验证流程 (release APK):
#   1. ./gradlew assembleRelease 安装到测试机
#   2. 启动 App, adb logcat | grep 'ClassCheck'
#   3. 期望 7 行 "[ClassCheck] OK    ..." — 任一 MISS 证明本文件缺对应 -keep
#
# 错误信息格式约定 (InventoryApp.checkRfidClasses):
#   [ClassCheck] MISS  com.ubx.usdk.xxx : ClassNotFoundException ...
#   → 补 -keep class com.ubx.usdk.xxx { *; } 重打 release
# ============================================================================

# Urovo 平台 SDK — ScanManager / DeviceManager 等平台能力
-keep class android.device.** { *; }
-dontwarn android.device.**

# Urovo UHF SDK — RFIDSDKManager / RfidManager 用 DeclaredMethodUtils 反射调方法,
# R8 看不到直接引用; 漏 keep 会让 release 包运行时 ClassNotFoundException.
# InventoryApp.checkRfidClasses() 启动时用 Class.forName 探针验证这些类都在.
-keep class com.ubx.usdk.** { *; }
-dontwarn com.ubx.usdk.**
```

- [ ] **Step 4: 更新 InventoryApp 的预热注释 + 诊断探针**

`app/src/main/java/com/huaxia/inventory/InventoryApp.java` 中：

把：

```java
        // 预热硬件扫码 SDK (rscja BarcodeDecoder).
```

改为：

```java
        // 预热硬件扫码 SDK (Urovo ScanManager).
```

再把 `checkRfidClasses()` 上方的整段注释 + 方法体里的类名单：

```java
        // 诊断: R8 minify 在 release 开启 (debug 已关), com.rscja.team.mtk.* 等
        // 平台实现类会被 R8 误判为"不可达"剥掉 — proguard-rules.pro 已 keep 但偶尔
        // 会因 SDK 升级引入未覆盖的类. 用 Class.forName(false, classLoader) 探一下,
        // 找出哪些类丢了. 仅 release 才有意义, debug 是 no-op 但仍跑消耗 ≈100ms.
        checkRfidClasses();
```

改为：

```java
        // 诊断: R8 minify 只在 release 开启 (debug 已关), com.ubx.usdk.* 等
        // SDK 内部类会被 R8 误判为"不可达"剥掉 — proguard-rules.pro 已 keep 但偶尔
        // 会因 SDK 升级引入未覆盖的类. 用 Class.forName(false, classLoader) 探一下,
        // 找出哪些类丢了. 仅 release 才有意义, debug 是 no-op 但仍跑消耗 ≈100ms.
        checkRfidClasses();
```

然后把方法整体替换为：

```java
    private void checkRfidClasses() {
        // 这些是 Urovo SDK 内可能被 R8 误剥的类 — release build R8 minify 后的诊断,
        // debug build 是 no-op (R8 disabled).
        //
        // 验证流程 (release APK):
        //   1. ./gradlew assembleRelease 安装到测试机
        //   2. adb logcat -s InventoryApp | grep ClassCheck
        //   3. 期望 7 行 "[ClassCheck] OK    ..." — 任一 MISS 证明
        //      proguard-rules.pro 缺对应 -keep, 补一行重打 release.
        // 完整 keep 列表见 app/proguard-rules.pro 的 "Urovo UHF/Barcode SDK" 块.
        String[] classes = {
            "com.ubx.usdk.RFIDSDKManager",
            "com.ubx.usdk.rfid.RfidManager",
            "com.ubx.usdk.rfid.aidl.IRfidCallback",
            "com.ubx.usdk.bean.Tag6C",
            "android.device.ScanManager",
            "android.device.DeviceManager",
            "android.device.scanner.configuration.PropertyID",
        };
        ClassLoader cl = getClassLoader();
        for (String name : classes) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                Log.i(TAG, "[ClassCheck] OK    " + name + " -> " + c.getName());
            } catch (Throwable t) {
                Log.e(TAG, "[ClassCheck] MISS  " + name + " : " + t.getClass().getSimpleName()
                        + " " + t.getMessage());
            }
        }
    }
```

- [ ] **Step 5: 更新 app/build.gradle 的 release 注释**

把：

```groovy
            // proguard-rules.pro 中保留了 rscja team.* 等可能被 R8 误剥的类,
            // 兜底 rscja 反射调用与 setRfidPower 等反射入口。
```

改为：

```groovy
            // proguard-rules.pro 中保留了 android.device.* / com.ubx.usdk.* 等
            // 可能被 R8 误剥的 SDK 类，兜底 ScanManager 与 RFID 的反射调用入口。
```

- [ ] **Step 6: 运行三个守卫测试**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.huaxia.inventory.ProguardR8KeepCommentTest \
  --tests com.huaxia.inventory.InventoryAppR8CommentTest \
  --tests com.huaxia.inventory.InventoryAppFieldsFinalTest
```

Expected: 全部 PASS。`InventoryAppR8CommentTest` 要求 `checkRfidClasses();` 上方注释含 "release" 且不含 "R8 minify 在 debug 开启后" —— 上面的新注释满足。

- [ ] **Step 7: release 构建验证 R8 规则不报错**

Run: `./gradlew :app:assembleRelease`

Expected: `BUILD SUCCESSFUL`。若出现 `Missing class com.ubx.usdk...`，说明 keep/dontwarn 写错，按报错补规则。

- [ ] **Step 8: Commit**

```bash
git add app/proguard-rules.pro app/build.gradle \
        app/src/main/java/com/huaxia/inventory/InventoryApp.java \
        app/src/test/java/com/huaxia/inventory/ProguardR8KeepCommentTest.java
git commit -m "build(r8): ProGuard keep 段与启动诊断探针改为优博讯 SDK"
```

---

### Task 5: 摘除旧 rscja 依赖、更新 README、全量验证

**Files:**
- Delete: `app/libs/DeviceAPI_ver20230301_release.aar`
- Modify: `app/build.gradle:133`（去掉旧依赖行）与 `app/build.gradle:135-136`（降级扫码注释）
- Modify: `README.md:29`

**Interfaces:**
- Consumes: Task 2 / 3 / 4 已不再引用 `com.rscja.*`
- Produces: 干净的单厂商依赖树

- [ ] **Step 1: 确认代码里不再有 rscja 引用**

Run:

```bash
grep -rn "com\.rscja" app/src || echo "no rscja references"
```

Expected: 输出 `no rscja references`。若仍有命中，回到 Task 2 / 3 补齐。

- [ ] **Step 2: 删除旧 AAR 并去掉依赖行**

```bash
rm app/libs/DeviceAPI_ver20230301_release.aar
```

`app/build.gradle` 把 Task 1 写入的三行：

```groovy
    // 旧「成为」(rscja) SDK — Task 5 摘除
    implementation files('libs/DeviceAPI_ver20230301_release.aar')
```

连同注释一起删除，最终保留：

```groovy
    // 优博讯平台 SDK — 条码 ScanManager / DeviceManager 等平台能力 (android.device.*)
    implementation files('libs/platform_sdk_v4.1.0326.jar')
    // 优博讯 UHF 读写器 SDK — com.ubx.usdk.*, 自带 arm64-v8a / armeabi-v7a / armeabi 的 .so
    implementation files('libs/URFIDLibrary-v2.5.0718.aar')
```

- [ ] **Step 3: 更新 build.gradle 里另一处 rscja 表述**

```groovy
    // 手动盘点页的降级扫码方案：摄像头预览 + ML Kit 条码识别
    // (用于没有物理扫码枪或暂不接 rscja barcode SDK 的场景)
```

改为：

```groovy
    // 手动盘点页的降级扫码方案：摄像头预览 + ML Kit 条码识别
    // (用于没有物理扫码头或优博讯 ScanManager 不可用的场景)
```

- [ ] **Step 4: 更新 README 技术栈**

`README.md:29`

```markdown
- UROVO RFID SDK (rscja `DeviceAPI_ver20230301_release.aar`，见 `app/libs/`)
```

改为：

```markdown
- UROVO 平台 SDK (`android.device.ScanManager`，`platform_sdk_v4.1.0326.jar`，见 `app/libs/`)
- UROVO UHF SDK (`com.ubx.usdk.RFIDSDKManager`，`URFIDLibrary-v2.5.0718.aar`，见 `app/libs/`)
```

- [ ] **Step 5: 全量验证**

Run:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
```

Expected: 三条命令都 `BUILD SUCCESSFUL`，测试 0 failed。

- [ ] **Step 6: 确认 APK 里没有残留旧 SDK**

Run:

```bash
./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -i "DeviceAPI\|platform_sdk\|URFID"
```

Expected: 只出现 `platform_sdk_v4.1.0326.jar` 与 `URFIDLibrary-v2.5.0718.aar` 两行（`files(...)` 依赖），无 `DeviceAPI_ver20230301_release.aar`。

- [ ] **Step 7: Commit**

```bash
git add -A app/libs app/build.gradle README.md
git commit -m "chore(sdk): 摘除 rscja AAR 并更新技术栈文档"
```

---

## 真机验证（代码之外，交付前必做）

1. `./gradlew :app:installDebug`，启动 `com.assetmanager.inventory/com.huaxia.inventory.MainActivity`。
2. `adb logcat -s UHFInit SdkRfidAdapter ClassCheck` —— 应看到 `init() done, ok=true`；release 包应看到 7 行 `[ClassCheck] OK`。
3. 扫描页点「开始盘点」，物理扳机/按钮均能读到标签，EPC 与固资编号匹配生效。
4. 功率设置弹窗改 5 / 15 / 26 dBm，`SdkRfidAdapter` 日志 `setOutputPower(x) returned 0`，重启 App 后自动沿用上次值。
5. 手动盘点页点「开始扫描」，物理扫码头扫条码 → 输入框/查询链路拿到码；无扫码头设备自动落 CameraX。
6. 若第 5 步异常而 RFID 正常：把 `app/build.gradle` 的平台 SDK 换成
   `SDK_ReleaseforAndroid-master/RFIDSDKSample-main/UHF_Demo/app/libs/platform_sdk_v3.1.221124.jar`
   重试（见设计文档 §9 风险 2）。

---

## Self-Review

- **Spec 覆盖**：§3 依赖（Task 1/5）、§4 RFID（Task 2）、§5 条码（Task 3）、§6 ProGuard+探针（Task 4）、§7 文档（Task 5）、§8 测试（Task 2/3/4 内）、§9 风险（真机验证节 + Step 6）。
- **占位符扫描**：无 TBD/TODO；每个代码步骤都给了完整可粘贴内容。
- **类型一致性**：`TagListener.onTag(String, String, String)`、`Callback.onStatus(boolean)`、`startInventory()` 返回 int、`setPower(int)` 返回 boolean 均与现有 `RfidController` 调用一致；`BarcodeHardwareController.Callback` 的两个方法签名与 `ManualScanFragment` 的匿名实现一致。

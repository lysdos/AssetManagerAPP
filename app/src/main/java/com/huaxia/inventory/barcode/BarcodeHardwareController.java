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
        // 注册失败不能继续 startDecode: 否则解码广播没人收, 用户按扳机毫无反应.
        if (!registerDecodeReceiver()) {
            callback.onStartFailed("register receiver failed");
            return;
        }
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

    private boolean registerDecodeReceiver() {
        if (receiver != null) return true;
        final Context ctx = sAppCtx;
        if (ctx == null) {
            Log.w(TAG, "registerDecodeReceiver: app context missing");
            return false;
        }
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String raw = extractBarcode(intent);
                if (raw == null || raw.isEmpty()) return;
                if (isDuplicate(raw)) return;
                emit(raw);
            }
        };
        IntentFilter filter = new IntentFilter(sDecodeAction);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // targetSdk 34: 动态注册非系统广播必须显式声明导出性.
                // RECEIVER_EXPORTED — 解码广播来自扫码服务 (另一个应用).
                ctx.registerReceiver(r, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(r, filter);
            }
        } catch (Throwable t) {
            // OEM 实现可能抛 SecurityException 等: 不能让主线程崩溃, 也不能把
            // 未注册成功的 receiver 留在字段里 (否则下一次 startScan 会误以为已注册).
            Log.w(TAG, "registerReceiver() failed", t);
            receiver = null;
            return false;
        }
        // 只有注册成功后才发布到字段, 保证 receiver != null ⇔ 已注册.
        this.receiver = r;
        return true;
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
    // package-private: 供同包单元测试直接调用, 不属于对外 API
    static String extractBarcode(Intent intent) {
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
    // package-private: 供同包单元测试直接调用, 不属于对外 API
    boolean isDuplicate(String raw) {
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

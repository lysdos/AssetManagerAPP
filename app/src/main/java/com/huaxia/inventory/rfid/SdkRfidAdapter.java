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
     * init 超时兜底 (ms). 优博讯 {@code RFIDSDKManager.init} 在内部
     * {@code catch (Throwable)} 与 {@code DeclaredMethodUtils} 反射失败路径下会
     * 静默返回、<b>永不回调</b> {@code InitListener}; 没有这个超时, 上层
     * {@link RfidController} 会永远停在 INITIALIZING (旧 rscja 实现同步返回 false
     * 会进 ERROR). 10s 足够覆盖正常初始化, 又不会让用户面对无限等待.
     */
    private static final long INIT_TIMEOUT_MS = 10_000L;

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
        // 单次结算守卫: SDK 回调、同步异常、超时 watchdog 三条路径最多只有一条
        // 能调用 cb.onStatus(...), 避免上层状态机被重复推进.
        final AtomicBoolean settled = new AtomicBoolean(false);
        try {
            // SDK init 可能静默返回、永不回调 (见 INIT_TIMEOUT_MS 注释) — 用 daemon
            // 线程兜底, 不依赖 Handler (init 不保证在主线程).
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(INIT_TIMEOUT_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (settled.compareAndSet(false, true)) {
                    Log.w(TAG, "init() timed out after " + INIT_TIMEOUT_MS
                            + "ms — Urovo SDK never called back");
                    powered.set(false);
                    if (cb != null) cb.onStatus(false);
                }
            }, "rfid-init-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            RFIDSDKManager.getInstance().init(appCtx, status -> {
                if (!settled.compareAndSet(false, true)) {
                    // 已由超时 watchdog 结算 — 迟到的 SDK 回调不再重复通知上层.
                    Log.w(TAG, "init() SDK callback arrived after settle, ignored");
                    return;
                }
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
            if (settled.compareAndSet(false, true)) {
                powered.set(false);
                if (cb != null) cb.onStatus(false);
            }
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

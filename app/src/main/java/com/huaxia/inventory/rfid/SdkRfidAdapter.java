package com.huaxia.inventory.rfid;

import android.content.Context;
import android.util.Log;

import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.UHFTAGInfo;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thin adapter over the rscja DeviceAPI UHF SDK
 * ({@code DeviceAPI_ver20230301_release.aar}).
 *
 * <p>The underlying SDK is a <b>polling</b> model — call
 * {@code startInventoryTag()} once, then drain results with
 * {@code readTagFromBuffer()} in a loop. This adapter bridges that to the
 * fan-out {@link TagListener} shape the rest of the app uses, by running
 * exactly one polling worker thread while inventory is active.</p>
 *
 * <p>Notes on the SDK (verified against
 * {@code uhf-uart-demo/uhf-uart-demo/app/src/main/}):</p>
 * <ul>
 *   <li>{@code RFIDWithUHFUART.getInstance()} returns a process-wide singleton;
 *       it may throw on non-UHF hardware.</li>
 *   <li>{@code init()} is <b>synchronous</b> and returns {@code boolean}.</li>
 *   <li>{@code startInventoryTag()} returns {@code boolean} (not an int code).</li>
 *   <li>{@code readTagFromBuffer()} returns a {@link UHFTAGInfo} or {@code null}
 *       when the internal buffer is empty — must be polled in a loop.</li>
 *   <li>{@code free()} is the teardown counterpart of {@code init()}.</li>
 *   <li>The SDK does not expose {@code power()} or {@code disConnect()}; this
 *       adapter maintains a logical "powered" flag for the call sites that
 *       still need to query it.</li>
 * </ul>
 */
public class SdkRfidAdapter {

    private static final String TAG = "SdkRfidAdapter";
    private static final long POLL_IDLE_SLEEP_MS = 20L;

    /**
     * SDK 单例. {@code volatile} 是必须的: rfid-poll 守护线程在
     * {@link #pollLoop()} 每次循环重新读这个字段, 一旦 {@link #release()}
     * 把 reader 置 null, 守护线程下次 {@code readTagFromBuffer()} 之前
     * 会拿到 null 并立即退出, 避免对已 free 的 SDK 实例调用引发的 Native 崩溃.
     * 同样在 init 阶段, main 线程先写, 守护线程后读, 也靠 volatile 拿到最新指针.
     */
    private volatile RFIDWithUHFUART reader;
    private final AtomicBoolean powered = new AtomicBoolean(false);
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final Set<TagListener> listeners = new CopyOnWriteArraySet<>();
    private volatile Thread pollThread;
    /** 串行化 start/stop — 防止并发调用各自拉起 poll 线程 / 互相清 pollThread 引用. */
    private final Object lifecycleLock = new Object();

    /** Default constructor — obtains the SDK singleton on first use. */
    public SdkRfidAdapter() {
    }

    private RFIDWithUHFUART requireReader() {
        if (reader == null) {
            try {
                reader = RFIDWithUHFUART.getInstance();
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

    /** Per-tag callback fired by the polling worker for each tag observed. */
    public interface TagListener {
        void onTag(String epc, String tid, String rssi);
    }

    /** Whether the last {@link #power(boolean)} call marked the module on. */
    public boolean isPowered() {
        return powered.get();
    }

    /**
     * Initialize the RFID SDK. The {@code Callback} is invoked exactly once,
     * on the calling thread, after {@code init()} returns.
     */
    public void init(Context appCtx, Callback cb) {
        Log.i("UHFInit", "[SdkRfidAdapter] init() entered");
        boolean ok = false;
        try {
            RFIDWithUHFUART r = requireReader();
            Log.i("UHFInit", "[SdkRfidAdapter] reader class = " + r.getClass().getName());
            Log.i("UHFInit", "[SdkRfidAdapter] calling reader.init()...");
            ok = r.init();
            Log.i("UHFInit", "[SdkRfidAdapter] reader.init() returned " + ok);
        } catch (Throwable t) {
            Log.e("UHFInit", "[SdkRfidAdapter] init() threw " + t.getClass().getName()
                    + ": " + t.getMessage(), t);
            Log.w(TAG, "init() failed", t);
        }
        powered.set(ok);
        if (cb != null) cb.onStatus(ok);
        Log.i("UHFInit", "[SdkRfidAdapter] init() done, ok=" + ok);
    }

    /** Whether the underlying RFID module is currently connected. */
    public boolean isConnected() {
        return reader != null && powered.get();
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
            RFIDWithUHFUART r = requireReader();
            boolean result = r.setPower(power);
            Log.i(TAG, "setPower(" + power + ") returned " + result);
            return result;
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
            RFIDWithUHFUART r = requireReader();
            int power = r.getPower();
            Log.i(TAG, "getPower() returned " + power);
            return power;
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
     * Begin continuous EPC inventory and start the polling worker.
     *
     * @return {@code 0} on success, non-zero on failure.
     */
    public int startInventory() {
        synchronized (lifecycleLock) {
            if (scanning.get()) {
                return 0;
            }
            boolean started;
            try {
                started = requireReader().startInventoryTag();
            } catch (Throwable t) {
                Log.w(TAG, "startInventoryTag() failed", t);
                return -1;
            }
            if (!started) {
                return -1;
            }
            scanning.set(true);
            Thread t = new Thread(this::pollLoop, "rfid-poll");
            t.setDaemon(true);
            pollThread = t;
            t.start();
            return 0;
        }
    }

    /**
     * Stop continuous EPC inventory and join the polling worker.
     *
     * <p>顺序固定: 1) {@code scanning.compareAndSet(true, false)} 把
     * {@link #pollLoop()} 退出门打开; 2) 调 SDK {@code stopInventory()};
     * 3) 等待守护线程 join 500ms; <b>4) join 之后才把
     * {@link #pollThread} 置 null</b> — 否则守护线程仍可能在 join 期间
     * 读到非 null 的 {@code pollThread} 被另一个 startInventory() 复用.</p>
     */
    public int stopInventory() {
        synchronized (lifecycleLock) {
            if (!scanning.compareAndSet(true, false)) {
                return 0;
            }
            Thread t = pollThread;
            try {
                requireReader().stopInventory();
            } catch (Throwable ex) {
                Log.w(TAG, "stopInventory() failed", ex);
            }
            if (t != null) {
                try {
                    t.join(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                // join 后才清 pollThread — 否则 join 期间下一个 startInventory()
                // 可能拿到尚未 stop 的旧 Thread 引用, 与新拉的守护线程并存.
                pollThread = null;
            }
            return 0;
        }
    }

    /** Disconnect the RFID module (no-op on the new SDK — kept for API parity). */
    public void disConnect() {
        // The rscja DeviceAPI does not expose a disconnect primitive; the
        // power/free pair covers teardown.
    }

    /**
     * Power the RFID module on/off. The new SDK has no explicit power
     * primitive, so this adapter tracks the desired state in {@link #powered}
     * and stops the polling worker on power-off.
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
     * <p>顺序固定: stopInventory → free → 把 {@link #reader} 置 null → 清
     * {@link #powered}. 后两步让守护线程 (若未及时 join) 通过 volatile 读到
     * null reader 后退出, 避免对已 free 的 SDK 单例调用.</p>
     */
    public void release() {
        stopInventory();
        try {
            requireReader().free();
        } catch (Throwable t) {
            Log.w(TAG, "free() failed", t);
        }
        powered.set(false);
        // 留作幂等: 第二次 release 不抛异常.
        reader = null;
    }

    private void pollLoop() {
        // 每次循环重新读 volatile reader — release() 把 reader 置 null 时,
        // 守护线程下一轮 (POLL_IDLE_SLEEP_MS <= 500ms 内) 立刻看到并退出,
        // 不会对已 free 的 SDK 单例调用 readTagFromBuffer().
        //
        // 循环条件还要求"自己仍是当前 pollThread": stop 把 scanning 置 false 后,
        // 若 join 超时(旧线程卡在 native readTagFromBuffer), 随后的 start 会把
        // scanning 置回 true 并换上新的 pollThread — 旧线程凭此条件 (pollThread
        // 已指向新线程 != self) 退出, 不会重新进入循环与新的 poll 线程并发调用
        // SDK. 没有这一条, 旧线程会因 scanning 被重置为 true 而"复活".
        while (scanning.get() && pollThread == Thread.currentThread()) {
            RFIDWithUHFUART r = reader;
            if (r == null) {
                return;
            }
            UHFTAGInfo info = null;
            try {
                info = r.readTagFromBuffer();
            } catch (Throwable t) {
                Log.w(TAG, "readTagFromBuffer() failed", t);
            }
            if (info != null) {
                dispatch(info);
            } else {
                try {
                    Thread.sleep(POLL_IDLE_SLEEP_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void dispatch(UHFTAGInfo info) {
        String epc = info.getEPC();
        String tid = info.getTid();
        String rssi = info.getRssi();
        for (TagListener l : listeners) {
            try {
                l.onTag(epc, tid, rssi);
            } catch (Throwable t) {
                Log.w(TAG, "TagListener threw", t);
            }
        }
    }
}

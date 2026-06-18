package com.huaxia.inventory.rfid;

import android.content.Context;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * High-level state machine that wraps {@link SdkRfidAdapter} and tracks the
 * lifecycle of the RFID module (init, ready, scanning, error).
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Idempotent {@link #init(Context, SdkRfidAdapter.Callback)} — safe to
 *       call multiple times.</li>
 *   <li>Tag-scanning callbacks are fan-out: multiple consumers (e.g. the scan
 *       ViewModel plus a status banner) can subscribe at the same time and are
 *       kept in a thread-safe {@link CopyOnWriteArraySet}.</li>
 *   <li>State transitions are guarded so that, e.g., calling
 *       {@link #startInventory()} while already scanning is a no-op.</li>
 *   <li>{@link #release()} is safe to call when not initialized (it short-
 *       circuits).</li>
 * </ul>
 *
 * <p>This class does not own the SDK singleton — it is shared per-process — so
 * instances of {@code RfidController} are cheap to create. Prefer a single
 * instance per {@code Application} or per {@code Activity}.</p>
 */
public class RfidController {

    /** High-level controller state. */
    public enum State { IDLE, INITIALIZING, READY, SCANNING, ERROR }

    private static final String TAG = "UHFInit";

    private final SdkRfidAdapter adapter;
    private volatile State state = State.IDLE;
    private final Set<SdkRfidAdapter.TagListener> callbacks = new CopyOnWriteArraySet<>();

    public RfidController() {
        this(new SdkRfidAdapter());
    }

    public RfidController(SdkRfidAdapter adapter) {
        this.adapter = adapter;
    }

    public State getState() {
        return state;
    }

    /** Whether the RFID module is powered on. */
    public boolean isPowered() {
        return adapter.isPowered();
    }

    /**
     * Initialize the SDK. If already ready or scanning, the callback is
     * invoked immediately with {@code true} on the calling thread.
     */
    public void init(Context appCtx, SdkRfidAdapter.Callback onReady) {
        android.util.Log.i(TAG, "[RfidController] init() called, state=" + state);
        if (state == State.READY || state == State.SCANNING) {
            if (onReady != null) onReady.onStatus(true);
            return;
        }
        state = State.INITIALIZING;
        android.util.Log.i(TAG, "[RfidController] state -> INITIALIZING, calling adapter.init()");
        adapter.init(appCtx, ready -> {
            android.util.Log.i(TAG, "[RfidController] adapter.init callback ready=" + ready);
            state = ready ? State.READY : State.ERROR;
            if (onReady != null) onReady.onStatus(ready);
            android.util.Log.i(TAG, "[RfidController] state -> " + state);
        });
    }

    /**
     * Register a callback. If the SDK is already initialized, the callback is
     * registered with the SDK immediately.
     */
    public void registerCallback(SdkRfidAdapter.TagListener cb) {
        if (callbacks.add(cb) && state != State.IDLE) {
            adapter.registerCallback(cb);
        }
    }

    /** Unregister a previously-registered callback. */
    public void unregisterCallback(SdkRfidAdapter.TagListener cb) {
        if (callbacks.remove(cb)) {
            adapter.unregisterCallback(cb);
        }
    }

    /**
     * Begin continuous inventory.
     *
     * @return {@code true} if the controller is now in {@link State#SCANNING}
     *         (either we just transitioned, or we were already scanning and
     *         the call is a no-op). {@code false} if the controller was not in
     *         a state from which inventory can start, or the SDK rejected the
     *         call (returned non-zero).
     */
    public boolean startInventory() {
        if (state != State.READY && state != State.SCANNING) return false;
        if (state == State.SCANNING) return true;
        int rc = adapter.startInventory();
        if (rc == 0) {
            state = State.SCANNING;
            return true;
        }
        return false;
    }

    /**
     * Stop continuous inventory.
     *
     * @return {@code true} if the controller transitioned (or was already
     *         out of) {@link State#SCANNING} as a result of this call.
     *         {@code false} if the controller was not scanning.
     */
    public boolean stopInventory() {
        if (state != State.SCANNING) return false;
        adapter.stopInventory();
        state = State.READY;
        return true;
    }

    /**
     * Whether the controller is actually scanning right now. Prefer this over
     * comparing {@link #getState()} to {@link State#SCANNING} at call sites
     * that need to drive UI: it gives the same answer with less ceremony and
     * keeps a single point of truth for "is the radio emitting?".
     */
    public boolean isActuallyScanning() {
        return state == State.SCANNING;
    }

    /** Power the RFID module off (does not release the SDK). */
    public void powerOff() {
        adapter.power(false);
    }

    /** Power the RFID module on (does not re-initialize the SDK). */
    public void powerOn() {
        adapter.power(true);
    }

    /**
     * Tear down: disconnect, power off, release SDK. Safe to call multiple
     * times — the second call short-circuits.
     */
    public void release() {
        if (state == State.IDLE) return;
        adapter.disConnect();
        adapter.power(false);
        adapter.release();
        state = State.IDLE;
    }
}

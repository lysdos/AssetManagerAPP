package com.huaxia.inventory.rfid;

import android.content.Context;

import com.ubx.usdk.rfid.aidl.IRfidCallback;

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

    private final SdkRfidAdapter adapter;
    private volatile State state = State.IDLE;
    private final Set<IRfidCallback> callbacks = new CopyOnWriteArraySet<>();

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
        if (state == State.READY || state == State.SCANNING) {
            if (onReady != null) onReady.onStatus(true);
            return;
        }
        state = State.INITIALIZING;
        adapter.init(appCtx, ready -> {
            state = ready ? State.READY : State.ERROR;
            if (onReady != null) onReady.onStatus(ready);
        });
    }

    /**
     * Register a callback. If the SDK is already initialized, the callback is
     * registered with the SDK immediately.
     */
    public void registerCallback(IRfidCallback cb) {
        if (callbacks.add(cb) && state != State.IDLE) {
            adapter.registerCallback(cb);
        }
    }

    /** Unregister a previously-registered callback. */
    public void unregisterCallback(IRfidCallback cb) {
        if (callbacks.remove(cb)) {
            adapter.unregisterCallback(cb);
        }
    }

    /**
     * Begin continuous inventory. No-op unless the controller is in
     * {@link State#READY} or {@link State#SCANNING}.
     */
    public void startInventory() {
        if (state != State.READY && state != State.SCANNING) return;
        if (state == State.SCANNING) return;
        int rc = adapter.startInventory();
        if (rc == 0) state = State.SCANNING;
    }

    /** Stop continuous inventory. No-op unless currently scanning. */
    public void stopInventory() {
        if (state != State.SCANNING) return;
        adapter.stopInventory();
        state = State.READY;
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

package com.huaxia.inventory.rfid;

import android.content.Context;

import com.huaxia.inventory.util.EpcCodec;

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

    /**
     * Write a unified asset code (16-digit decimal) to a tag's EPC memory.
     *
     * <p>No-op unless the controller is in {@link State#READY} or
     * {@link State#SCANNING}.</p>
     *
     * @param decimal16 16-digit decimal string (e.g. "3500309099027210")
     * @return {@code true} on success, {@code false} on any failure
     *         (illegal state, codec failure, or SDK non-zero return)
     */
    public boolean writeEpc(String decimal16) {
        if (state != State.READY && state != State.SCANNING) return false;
        String hex = EpcCodec.toHex(decimal16);
        if (hex == null) return false;
        try {
            int rc = adapter.writeEpc(hex);
            if (rc != 0) {
                android.util.Log.w("RfidController", "writeEpc failed rc=" + rc);
                return false;
            }
            return true;
        } catch (UnsupportedOperationException uoe) {
            // SDK 集成未完成 — 当前是占位
            android.util.Log.w("RfidController", "writeEpc not implemented");
            return false;
        }
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

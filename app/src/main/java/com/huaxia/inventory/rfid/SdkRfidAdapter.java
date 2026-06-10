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

    private RFIDWithUHFUART reader;
    private final AtomicBoolean powered = new AtomicBoolean(false);
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final Set<TagListener> listeners = new CopyOnWriteArraySet<>();
    private volatile Thread pollThread;

    /** Test seam: inject a stubbed reader (e.g. a Mockito mock). */
    SdkRfidAdapter(RFIDWithUHFUART reader) {
        this.reader = reader;
    }

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
        boolean ok = false;
        try {
            ok = requireReader().init();
        } catch (Throwable t) {
            Log.w(TAG, "init() failed", t);
        }
        powered.set(ok);
        if (cb != null) cb.onStatus(ok);
    }

    /** Whether the underlying RFID module is currently connected. */
    public boolean isConnected() {
        return reader != null && powered.get();
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

    /**
     * Stop continuous EPC inventory and join the polling worker.
     *
     * @return {@code 0} on success.
     */
    public int stopInventory() {
        if (!scanning.compareAndSet(true, false)) {
            return 0;
        }
        Thread t = pollThread;
        pollThread = null;
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
        }
        return 0;
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

    /** Release SDK resources. Call from {@code onDestroy()} of the owning component. */
    public void release() {
        stopInventory();
        try {
            requireReader().free();
        } catch (Throwable t) {
            Log.w(TAG, "free() failed", t);
        }
        powered.set(false);
    }

    /**
     * Write a new EPC to the next tag observed by the reader.
     *
     * <p>Returns {@code 0} on success, non-zero on failure. The input must be
     * exactly 16 hex characters (8 bytes) representing the asset code in
     * big-endian integer form.</p>
     *
     * <p><b>TODO:</b> real integration with the rscja DeviceAPI is pending.
     * See {@code uhf-uart-demo/uhf-uart-demo/app/src/main/} for the SDK
     * reference (e.g. {@code setEpc} / {@code writeEpc}). This placeholder
     * throws so callers (and tests) can detect the unimplemented path.</p>
     *
     * @param hex16 16-character uppercase hex string (e.g. "0C72C8A220BB2A6A")
     * @return {@code 0} on success, non-zero on failure
     */
    public int writeEpc(String hex16) {
        if (hex16 == null || hex16.length() != 16) return -1;
        if (!hex16.matches("[0-9A-Fa-f]+")) return -1;
        // TODO: integrate with rscja DeviceAPI
        throw new UnsupportedOperationException(
                "SdkRfidAdapter.writeEpc not yet implemented; see uhf-uart-demo");
    }

    private void pollLoop() {
        RFIDWithUHFUART r = requireReader();
        while (scanning.get()) {
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

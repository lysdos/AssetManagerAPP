package com.huaxia.inventory.rfid;

import android.content.Context;

import com.ubx.usdk.RFIDSDKManager;
import com.ubx.usdk.rfid.aidl.IRfidCallback;

/**
 * Thin adapter over the UROVO RFID SDK ({@code URFIDLibrary-v2.4.1226.aar}).
 *
 * <p>Exposes a small, intent-revealing surface so the rest of the app does not need
 * to know about the underlying AIDL or singleton manager. All method names and
 * signatures were verified against
 * {@code RFID 开发文档 v2.4.1228/RFID开发文档v2.13.pdf} and the AAR's
 * {@code classes.jar}.</p>
 *
 * <p>Notes on the SDK (verified):</p>
 * <ul>
 *   <li>{@code RFIDSDKManager.getInstance()} returns a process-wide singleton.</li>
 *   <li>{@code init(InitListener)} returns {@code void}; the listener's
 *       {@code onStatus(boolean)} fires when the SDK is ready ({@code true}) or
 *       failed ({@code false}). Initialization typically takes ~2s.</li>
 *   <li>{@code getRfidManager()} is a "fluent" accessor on the singleton.</li>
 *   <li>{@code power(boolean)} and {@code release()} are on
 *       {@code RFIDSDKManager}, not the RfidManager.</li>
 *   <li>{@code startInventory(int session)} takes the EPC session id (0..3) and
 *       returns {@code 0} on success.</li>
 * </ul>
 */
public class SdkRfidAdapter {

    private volatile boolean powered = false;

    /** Whether the last {@link #power(boolean)} call succeeded. */
    public boolean isPowered() {
        return powered;
    }

    /** Single-shot readiness callback used during {@link #init(Context, Callback)}. */
    public interface Callback {
        /**
         * @param ready {@code true} once the SDK has finished initializing and
         *              the RFID module is powered/connected; {@code false} on
         *              failure.
         */
        void onStatus(boolean ready);
    }

    /**
     * Initialize the RFID SDK. The {@code Callback} is invoked exactly once,
     * on the SDK's internal worker thread.
     */
    public void init(Context appCtx, Callback cb) {
        RFIDSDKManager.getInstance().init(ready -> {
            if (cb != null) cb.onStatus(ready);
        });
    }

    /** Whether the underlying RFID module is currently connected. */
    public boolean isConnected() {
        return RFIDSDKManager.getInstance().getRfidManager().isConnected();
    }

    /** Register a tag-scanning callback with the SDK. */
    public void registerCallback(IRfidCallback cb) {
        RFIDSDKManager.getInstance().getRfidManager().registerCallback(cb);
    }

    /** Unregister a previously-registered tag-scanning callback. */
    public void unregisterCallback(IRfidCallback cb) {
        RFIDSDKManager.getInstance().getRfidManager().unregisterCallback(cb);
    }

    /**
     * Begin continuous EPC inventory.
     *
     * @return SDK return code ({@code 0} on success).
     */
    public int startInventory() {
        return RFIDSDKManager.getInstance().getRfidManager().startInventory(0);
    }

    /**
     * Stop continuous EPC inventory.
     *
     * @return SDK return code ({@code 0} on success).
     */
    public int stopInventory() {
        return RFIDSDKManager.getInstance().getRfidManager().stopInventory();
    }

    /** Disconnect the RFID module (idempotent). */
    public void disConnect() {
        RFIDSDKManager.getInstance().getRfidManager().disConnect();
    }

    /** Power the RFID module on/off. */
    public void power(boolean on) {
        try {
            RFIDSDKManager.getInstance().power(on);
            powered = on;
        } catch (Throwable t) {
            powered = false;
            android.util.Log.w("SdkRfidAdapter", "power() failed", t);
        }
    }

    /** Release SDK resources. Call from {@code onDestroy()} of the owning component. */
    public void release() {
        try {
            RFIDSDKManager.getInstance().release();
        } catch (Throwable t) {
            android.util.Log.w("SdkRfidAdapter", "release() failed", t);
        }
    }
}

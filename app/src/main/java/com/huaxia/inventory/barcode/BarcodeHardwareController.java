package com.huaxia.inventory.barcode;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import com.rscja.barcode.BarcodeDecoder;
import com.rscja.barcode.BarcodeFactory;
import com.rscja.barcode.BarcodeUtility;
import com.rscja.deviceapi.entity.BarcodeEntity;

/**
 * rscja BarcodeDecoder 硬件扫码 SDK 封装 (PDA 内置物理扫码头).
 *
 * <p>对应 C72_6765 等工业 PDA, 比 CameraX + ML Kit 软件解码快 ~5x.
 * 整个 AAR ({@code DeviceAPI_ver20230301_release.aar}) 已经包含
 * {@code com.rscja.barcode.*} 类, 不用额外复制 SDK.</p>
 *
 * <p>使用模式:
 * <ul>
 *   <li>App 启动时 {@link #prewarm(Context)} 在后台线程 open SDK + 关闭自带成功音</li>
 *   <li>{@link #isReady()} 判断硬件 SDK 是否可用, 不可用则降级到 CameraX</li>
 *   <li>{@link #startScan(Callback)} / {@link #stopScan()} 控制轮询</li>
 * </ul>
 *
 * <p>硬件 SDK 自带的成功音已关闭, 由 {@link com.huaxia.inventory.util.BeepPlayer}
 * 在主线程统一触发, 避免重复 beep.</p>
 */
public class BarcodeHardwareController {

    private static final String TAG = "BarcodeHw";
    private static final long DEBOUNCE_MS = 2000L;

    /** 共享状态: 预热是否成功. InventoryApp.onCreate 设, startScan 时读. */
    private static volatile boolean sPrewarmed = false;

    public interface Callback {
        /** 主线程回调. rawCode 是硬件 SDK 解到的字面字符串, 跟文本框输入走同一路径. */
        @MainThread void onBarcodeDetected(@NonNull String rawCode);
        /** startScan 失败时回调 (比如硬件中途断连). */
        @MainThread default void onStartFailed(@NonNull String reason) {}
    }

    private final BarcodeDecoder decoder;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Callback activeCallback;
    private long lastEmitMs = 0L;
    private String lastEmitCode = "";

    public BarcodeHardwareController() {
        this.decoder = BarcodeFactory.getInstance().getBarcodeDecoder();
    }

    /**
     * App 启动时调一次 (InventoryApp.onCreate 后台线程).
     * 同步打开硬件 SDK, 设置全局共享的 sPrewarmed 标志.
     */
    public static void prewarm(@NonNull Context appCtx) {
        try {
            BarcodeDecoder d = BarcodeFactory.getInstance().getBarcodeDecoder();
            boolean ok = d.open(appCtx);
            // 关闭硬件 SDK 自带的成功音, 由 BeepPlayer 统一管理
            // 避免 SDK 回调 + vm.onBarcodeScanned 两边都响
            try {
                BarcodeUtility.getInstance().enablePlaySuccessSound(appCtx, false);
            } catch (Throwable t) {
                Log.w(TAG, "enablePlaySuccessSound(false) failed (ignored): " + t.getMessage());
            }
            sPrewarmed = ok;
            Log.i(TAG, "[BarcodeHardwareController] prewarm ok=" + ok);
        } catch (Throwable t) {
            Log.w(TAG, "[BarcodeHardwareController] prewarm failed: " + t.getMessage(), t);
            sPrewarmed = false;
        }
    }

    /** 硬件 SDK 是否可用 (prewarm 成功). */
    public static boolean isReady() {
        return sPrewarmed;
    }

    @MainThread
    public void startScan(@NonNull Callback callback) {
        if (!sPrewarmed) {
            callback.onStartFailed("not prewarmed");
            return;
        }
        this.activeCallback = callback;
        decoder.setDecodeCallback(new BarcodeDecoder.DecodeCallback() {
            @Override
            public void onDecodeComplete(BarcodeEntity barcodeEntity) {
                if (barcodeEntity.getResultCode() != BarcodeDecoder.DECODE_SUCCESS) return;
                String raw = barcodeEntity.getBarcodeData();
                if (raw == null || raw.isEmpty()) return;
                long now = System.currentTimeMillis();
                synchronized (BarcodeHardwareController.this) {
                    if (raw.equals(lastEmitCode) && now - lastEmitMs < DEBOUNCE_MS) {
                        return; // 2 秒去重 (用户长按扳机会多次触发)
                    }
                    lastEmitCode = raw;
                    lastEmitMs = now;
                }
                emit(raw);
            }
        });
        decoder.startScan();
        Log.i(TAG, "[BarcodeHardwareController] startScan issued");
    }

    @MainThread
    public void stopScan() {
        Log.i(TAG, "[BarcodeHardwareController] stopScan");
        if (decoder != null) {
            decoder.stopScan();
        }
        activeCallback = null;
    }

    public void release() {
        stopScan();
    }

    private void emit(String raw) {
        // 硬件 SDK 回调可能在 SDK 内部线程, 切到主线程避免视图访问崩溃
        mainHandler.post(() -> {
            Callback c = activeCallback;
            if (c != null) c.onBarcodeDetected(raw);
        });
    }
}
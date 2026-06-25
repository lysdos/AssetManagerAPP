package com.huaxia.inventory.barcode;

import android.annotation.SuppressLint;
import android.util.Log;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CameraX + ML Kit barcode scanner for the manual-inventory page.
 *
 * <p>Drop-in replacement for the rscja barcode SDK path. Lifecycle is bound
 * to a {@link LifecycleOwner} (the hosting Activity) so CameraX handles
 * start/stop on resume/pause automatically once bound.</p>
 *
 * <p>Detects 1D + 2D codes; debounces identical reads within 2 seconds so a
 * static label doesn't fire {@link Callback#onBarcodeDetected} dozens of
 * times per second.</p>
 */
public class BarcodeCameraController {

    private static final String TAG = "Barcode";
    private static final long DEBOUNCE_MS = 2000L;

    public interface Callback {
        /** Fires on the main thread. {@code rawCode} is the unprocessed ML Kit
         *  string — caller decides whether to normalize or reject it. */
        @MainThread void onBarcodeDetected(@NonNull String rawCode);

        /**
         * Fires on the main thread when {@link #startScan} cannot complete
         * asynchronously — either the camera provider is unavailable
         * (no camera, GMS missing) or {@code bindToLifecycle} fails
         * (camera in use by another app, owner destroyed, etc.).
         *
         * <p>Default implementation is a no-op for backwards compatibility
         * with callers that only care about successful scans. New callers
         * should override to surface an error to the user.</p>
         */
        @MainThread default void onStartFailed(@NonNull String reason) {}
    }

    private final BarcodeScanner scanner;
    private final ExecutorService analyzerExecutor;
    private final BarcodeAnalyzer analyzer;

    private ProcessCameraProvider cameraProvider;
    private Callback activeCallback;
    private long lastEmitMs = 0L;
    private String lastEmitCode = "";

    public BarcodeCameraController() {
        // 客户资产上的条码通常是 CODE_128/CODE_39 (一维) + 部分 QR. 全格式打开
        // 让 ML Kit 自己跑格式识别, 代价不大.
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                        Barcode.FORMAT_CODE_128,
                        Barcode.FORMAT_CODE_39,
                        Barcode.FORMAT_CODE_93,
                        Barcode.FORMAT_EAN_13,
                        Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_QR_CODE,
                        Barcode.FORMAT_DATA_MATRIX)
                .build();
        this.scanner = BarcodeScanning.getClient(options);
        this.analyzerExecutor = Executors.newSingleThreadExecutor();
        this.analyzer = new BarcodeAnalyzer();
        Log.i(TAG, "[BarcodeCameraController] constructed");
    }

    /**
     * Bind CameraX preview + analysis to {@code previewView} under the given
     * lifecycle. Must be called on the main thread.
     *
     * <p>Implementation note: the camera-provider future is consumed on the
     * main thread via {@code addListener} + main executor — NOT via
     * {@code future.get()}, which would block this thread (causing ANR on
     * cold camera init). The lifecycle guard skips the bind entirely if
     * {@code owner} has already reached {@link Lifecycle.State#DESTROYED}
     * by the time the listener fires, which would otherwise throw
     * {@code IllegalArgumentException} inside CameraX.</p>
     *
     * <p>Errors are surfaced asynchronously via
     * {@link Callback#onStartFailed(String)} since {@code startScan} now
     * returns {@code void}.</p>
     */
    @MainThread
    public void startScan(@NonNull LifecycleOwner owner,
                          @NonNull PreviewView previewView,
                          @NonNull Callback callback) {
        Log.i(TAG, "[BarcodeCameraController] startScan()");
        this.activeCallback = callback;
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(previewView.getContext());
        future.addListener(() -> {
            // Owner may have been destroyed while the future was pending
            // (e.g. user pressed back during cold camera init). Bind will
            // throw IllegalArgumentException in that case; skip silently.
            if (owner.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) {
                Log.w(TAG, "[BarcodeCameraController] owner destroyed before bind, skipping");
                return;
            }
            try {
                cameraProvider = future.get();
            } catch (Exception e) {
                Log.e(TAG, "[BarcodeCameraController] getInstance failed", e);
                callback.onStartFailed("camera provider unavailable: " + e.getMessage());
                return;
            }

            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
            analysis.setAnalyzer(analyzerExecutor, analyzer);

            try {
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(owner,
                        CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
                Log.i(TAG, "[BarcodeCameraController] bound to lifecycle, scanning");
            } catch (Exception e) {
                Log.e(TAG, "[BarcodeCameraController] bindToLifecycle failed", e);
                callback.onStartFailed("bind failed: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(previewView.getContext()));
    }

    @MainThread
    public void stopScan() {
        Log.i(TAG, "[BarcodeCameraController] stopScan()");
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        activeCallback = null;
    }

    /** Release ML Kit + executor. Safe to call multiple times. */
    public void release() {
        Log.i(TAG, "[BarcodeCameraController] release()");
        stopScan();
        scanner.close();
        analyzerExecutor.shutdown();
        cameraProvider = null;
    }

    /** Called from analyzer thread; posts back to main via {@link #emit}.
     *  {@code imageProxy} must be closed by the caller — we don't own it here. */
    private void onMlKitResult(@NonNull ImageProxy imageProxy,
                               @NonNull InputImage image,
                               @NonNull List<Barcode> barcodes) {
        try {
            if (barcodes.isEmpty()) return;
            // 取第一个 rawValue — 多条码场景下第一个一般就是用户对准的那个
            String raw = barcodes.get(0).getRawValue();
            if (raw == null || raw.isEmpty()) return;
            long now = System.currentTimeMillis();
            synchronized (this) {
                if (raw.equals(lastEmitCode) && now - lastEmitMs < DEBOUNCE_MS) {
                    return; // 2 秒内同一内容重复识别, 丢弃
                }
                lastEmitCode = raw;
                lastEmitMs = now;
            }
            Log.i(TAG, "[BarcodeCameraController] detected: '" + raw + "'");
            emit(raw);
        } finally {
            imageProxy.close();
        }
    }

    private void emit(String raw) {
        Callback cb = activeCallback;
        if (cb == null) return;
        // analyzer 线程 -> main 线程. 直接用 main Looper 的 Handler 不依赖 context.
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            Callback c = activeCallback;
            if (c != null) c.onBarcodeDetected(raw);
        });
    }

    /** CameraX analyzer wrapper. */
    private class BarcodeAnalyzer implements ImageAnalysis.Analyzer {
        @SuppressLint("UnsafeOptInUsageError")
        @Override
        public void analyze(@NonNull ImageProxy imageProxy) {
            @androidx.camera.core.ExperimentalGetImage
            android.media.Image mediaImage = imageProxy.getImage();
            if (mediaImage == null) {
                imageProxy.close();
                return;
            }
            InputImage input = InputImage.fromMediaImage(
                    mediaImage, imageProxy.getImageInfo().getRotationDegrees());
            scanner.process(input)
                    .addOnSuccessListener(barcodes -> onMlKitResult(imageProxy, input, barcodes))
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "[BarcodeCameraController] ML Kit failure", e);
                        imageProxy.close();
                    });
        }
    }
}
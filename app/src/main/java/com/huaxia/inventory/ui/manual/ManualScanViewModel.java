package com.huaxia.inventory.ui.manual;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.util.EpcNormalizer;
import java.util.Collections;
import java.util.List;

/**
 * Backs the manual-inventory page. Differs from {@link com.huaxia.inventory.ui.scan.ScanViewModel}
 * in that:
 * <ul>
 *   <li>Input comes from a text field (16/17/24-digit decimal), not from the RFID SDK,
 *       so we go through {@link EpcNormalizer} directly without the hex→fromHex step.</li>
 *   <li>The user explicitly confirms each check (no debounced bulk writes).</li>
 *   <li>History is the recent N ScanRecords of the current task, sourced from DB.</li>
 * </ul>
 */
public class ManualScanViewModel extends AndroidViewModel {

    public static final int HISTORY_LIMIT = 10;

    /** Single-shot UI event after a check attempt. Null when nothing pending. */
    public enum CheckOutcome {
        CHECKED,         // 新盘点成功
        DUPLICATE,       // 该 EPC 已盘点过
        INVALID_FORMAT,  // 16/17/24 位纯数字校验失败
        ASSET_NOT_FOUND, // 该 EPC 在 DB 中无对应 Asset
        NO_TASK          // 当前无任务（用户没导入）
    }

    /**
     * Outcomes for the auto-scan path (barcode gun → onBarcodeScanned). Different
     * from {@link CheckOutcome} because the UI doesn't show a detail card — it
     * just toasts the result.
     */
    public enum ScanOutcome {
        SCANNED_FOUND,     // 找到 Asset + 写了 ScanRecord(matched)
        SCANNED_EXTRA,     // 没找到 Asset + 写了 ScanRecord(matchedAssetId=null, 盘盈)
        SCANNED_DUPLICATE, // 已存在 ScanRecord, 没写
        SCANNED_INVALID,   // 格式错 (非 16/17/24 位纯数字)
        SCANNED_NO_TASK    // 当前无任务
    }

    public static class CheckEvent {
        public final CheckOutcome outcome;
        @Nullable public final String epc;       // 归一化后的 24 位
        @Nullable public final Long previousAt;   // DUPLICATE 时: 已盘点时间

        public CheckEvent(CheckOutcome outcome, @Nullable String epc, @Nullable Long previousAt) {
            this.outcome = outcome;
            this.epc = epc;
            this.previousAt = previousAt;
        }
    }

    public static class ScanEvent {
        public final ScanOutcome outcome;
        @Nullable public final String epc;       // 归一化后的 24 位 (INVALID 时为 null)
        @Nullable public final String rawCode;   // 原始扫码字符串 (INVALID 时显示)
        @Nullable public final Long previousAt;   // DUPLICATE 时: 已盘点时间

        public ScanEvent(ScanOutcome outcome, @Nullable String epc,
                         @Nullable String rawCode, @Nullable Long previousAt) {
            this.outcome = outcome;
            this.epc = epc;
            this.rawCode = rawCode;
            this.previousAt = previousAt;
        }
    }

    public static class LookupResult {
        @Nullable public final Asset asset;
        @Nullable public final String normalizedEpc;  // 24 位, 错误时为 null
        public final boolean invalid;
        public final boolean notFound;
        public LookupResult(@Nullable Asset asset, @Nullable String normalizedEpc,
                             boolean invalid, boolean notFound) {
            this.asset = asset;
            this.normalizedEpc = normalizedEpc;
            this.invalid = invalid;
            this.notFound = notFound;
        }
    }

    private final InventoryApp app;
    private final MutableLiveData<LookupResult> lookup = new MutableLiveData<>();
    private final MutableLiveData<CheckEvent> checkEvent = new MutableLiveData<>();
    private final MutableLiveData<ScanEvent> scanEvent = new MutableLiveData<>();
    private final MutableLiveData<List<ScanRecord>> history = new MutableLiveData<>(Collections.emptyList());

    public ManualScanViewModel(@NonNull Application application) {
        super(application);
        this.app = (InventoryApp) application;
    }

    public LiveData<LookupResult> lookup() { return lookup; }
    public LiveData<CheckEvent> checkEvent() { return checkEvent; }
    public LiveData<ScanEvent> scanEvent() { return scanEvent; }
    public LiveData<List<ScanRecord>> history() { return history; }

    /**
     * Validate and look up the asset by raw user input. Runs on diskIO; result posted
     * to {@link #lookup()}.
     */
    public void queryByEpc(@Nullable String raw) {
        Long taskId = app.session.getCurrentTaskId();
        if (taskId == null) {
            lookup.postValue(new LookupResult(null, null, false, true));
            return;
        }
        String normalized = EpcNormalizer.normalize(raw);
        if (normalized == null) {
            lookup.postValue(new LookupResult(null, null, true, false));
            return;
        }
        long tid = taskId;
        app.executors.diskIO.execute(() -> {
            Asset a = app.db.assetDao().findByEpc(tid, normalized);
            if (a == null) {
                lookup.postValue(new LookupResult(null, normalized, false, true));
            } else {
                lookup.postValue(new LookupResult(a, normalized, false, false));
            }
        });
    }

    /**
     * Persist a manual check: write a matched ScanRecord + bump the task counter.
     * Posts a {@link CheckEvent} describing the outcome (success, duplicate, no task).
     */
    public void confirmCheck(@NonNull Asset asset, @Nullable String normalizedEpc) {
        Long taskId = app.session.getCurrentTaskId();
        if (taskId == null) {
            checkEvent.postValue(new CheckEvent(CheckOutcome.NO_TASK, null, null));
            return;
        }
        long tid = taskId;
        String epc = normalizedEpc != null ? normalizedEpc : asset.epc;
        long assetId = asset.id;
        app.executors.diskIO.execute(() -> {
            ScanRecord existing = app.db.scanRecordDao().findByEpc(tid, epc);
            if (existing != null) {
                checkEvent.postValue(new CheckEvent(CheckOutcome.DUPLICATE, epc, existing.scannedAt));
                return;
            }
            ScanRecord rec = new ScanRecord();
            rec.taskId = tid;
            rec.epc = epc;
            rec.tid = null;
            rec.rssi = "manual";
            rec.scannedAt = System.currentTimeMillis();
            rec.matchedAssetId = assetId;
            app.db.scanRecordDao().insert(rec);
            app.db.inventoryTaskDao().recomputeScannedCount(tid);
            checkEvent.postValue(new CheckEvent(CheckOutcome.CHECKED, epc, null));
            // Refresh local lookup/history so the UI reflects the new state without
            // waiting for the user to navigate away and back.
            lookup.postValue(new LookupResult(asset, epc, false, false));
            refreshHistory();
        });
    }

    /** Re-fetch the most recent N ScanRecords of the current task. */
    public void refreshHistory() {
        Long taskId = app.session.getCurrentTaskId();
        if (taskId == null) {
            history.postValue(Collections.emptyList());
            return;
        }
        long tid = taskId;
        app.executors.diskIO.execute(() ->
                history.postValue(app.db.scanRecordDao().pageRecent(tid, HISTORY_LIMIT)));
    }

    /**
     * Auto-scan path: barcode gun (or any non-blocking source) hands us a raw code,
     * we normalize → match → write ScanRecord in one shot. No detail card, no user
     * confirmation. Posts a {@link ScanEvent} so the UI can toast the result and
     * refresh history.
     *
     * <p>SDK callback (rscja DeviceAPI barcode module, TBD) is expected to call
     * this directly. For now nothing in the app invokes it — the manual-input path
     * still goes through {@link #queryByEpc(String)} + {@link #confirmCheck}.</p>
     */
    public void onBarcodeScanned(@Nullable String rawCode) {
        Long taskId = app.session.getCurrentTaskId();
        if (taskId == null) {
            scanEvent.postValue(new ScanEvent(ScanOutcome.SCANNED_NO_TASK, null, rawCode, null));
            return;
        }
        String normalized = EpcNormalizer.normalize(rawCode);
        if (normalized == null) {
            scanEvent.postValue(new ScanEvent(ScanOutcome.SCANNED_INVALID, null, rawCode, null));
            return;
        }
        long tid = taskId;
        app.executors.diskIO.execute(() -> {
            ScanRecord existing = app.db.scanRecordDao().findByEpc(tid, normalized);
            if (existing != null) {
                scanEvent.postValue(new ScanEvent(ScanOutcome.SCANNED_DUPLICATE,
                        normalized, rawCode, existing.scannedAt));
                return;
            }
            Asset matched = app.db.assetDao().findByEpc(tid, normalized);
            ScanRecord rec = new ScanRecord();
            rec.taskId = tid;
            rec.epc = normalized;
            rec.tid = null;
            rec.rssi = "scan";
            rec.scannedAt = System.currentTimeMillis();
            rec.matchedAssetId = matched == null ? null : matched.id;
            app.db.scanRecordDao().insert(rec);
            app.db.inventoryTaskDao().recomputeScannedCount(tid);
            scanEvent.postValue(new ScanEvent(
                    matched == null ? ScanOutcome.SCANNED_EXTRA : ScanOutcome.SCANNED_FOUND,
                    normalized, rawCode, null));
            refreshHistory();
        });
    }
}
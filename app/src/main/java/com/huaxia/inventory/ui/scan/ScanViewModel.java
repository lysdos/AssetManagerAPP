package com.huaxia.inventory.ui.scan;

import android.app.Application;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.StatusDerivation;
import com.huaxia.inventory.data.dao.CategoryCount;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ScanRecord;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

public class ScanViewModel extends AndroidViewModel {

    private static final String TAG = "ScanViewModel";

    public static class Stats {
        public final int inStock;
        public final int scanned;
        public final int anomaly;
        public final int todayAdded;
        public final int found;
        public final int missing;
        public final int extra;
        public final List<CategoryCount> categories;
        public final long lastInventoryAt;
        public Stats(int inStock, int scanned, int anomaly, int todayAdded,
                     int found, int missing, int extra,
                     List<CategoryCount> categories, long lastInventoryAt) {
            this.inStock = inStock; this.scanned = scanned; this.anomaly = anomaly;
            this.todayAdded = todayAdded;
            this.found = found; this.missing = missing; this.extra = extra;
            this.categories = categories;
            this.lastInventoryAt = lastInventoryAt;
        }
    }

    private final InventoryApp app;
    private final AppDatabase db;
    private final Executor diskIO;
    private final Set<String> scannedEpcSet = new HashSet<>();
    private final MutableLiveData<Stats> stats = new MutableLiveData<>();
    private final MutableLiveData<ScanRecord> latestScan = new MutableLiveData<>();
    private final MutableLiveData<ClearResult> clearResult = new MutableLiveData<>();
    private long currentTaskId = -1L;
    private int expectedCount = 0;

    public ScanViewModel(@NonNull Application application) {
        super(application);
        this.app = (InventoryApp) application;
        this.db = app.db;
        this.diskIO = app.executors.diskIO;
    }

    public LiveData<Stats> stats() { return stats; }
    public LiveData<ScanRecord> latestScan() { return latestScan; }
    public LiveData<ClearResult> clearResult() { return clearResult; }

    /** 清除任务结果，assets=被删 Asset 行数，scans=被删 ScanRecord 行数，error=异常消息。 */
    public static class ClearResult {
        public final int assets;
        public final int scans;
        public final String error;
        public ClearResult(int assets, int scans) { this.assets = assets; this.scans = scans; this.error = null; }
        public ClearResult(String error) { this.assets = 0; this.scans = 0; this.error = error; }
        public boolean isError() { return error != null; }
    }

    public void bindToTask(long taskId) {
        this.currentTaskId = taskId;
        diskIO.execute(() -> {
            scannedEpcSet.clear();
            scannedEpcSet.addAll(db.scanRecordDao().findAllEpcsByTask(taskId));
            expectedCount = db.inventoryTaskDao().findById(taskId).expectedCount;
            recomputeStats();
        });
    }

    public void onEpcScanned(String rawCode, String tid, String rssi) {
        if (rawCode == null || rawCode.isEmpty()) {
            Log.w(TAG, "onEpcScanned called with null/empty code");
            return;
        }
        if (scannedEpcSet.contains(rawCode)) {
            latestScan.postValue(makeUnmatchedRecord(rawCode, tid, rssi));
            return;
        }
        scannedEpcSet.add(rawCode);

        long taskId = currentTaskId;
        diskIO.execute(() -> {
            Asset matched = db.assetDao().findByEpc(taskId, rawCode);
            ScanRecord rec = new ScanRecord();
            rec.taskId = taskId;
            rec.epc = rawCode;
            rec.tid = tid;
            rec.rssi = rssi;
            rec.scannedAt = System.currentTimeMillis();
            rec.matchedAssetId = matched == null ? null : matched.id;
            long newId = db.scanRecordDao().insert(rec);
            rec.id = newId;
            db.inventoryTaskDao().recomputeScannedCount(taskId);
            recomputeStats();
            latestScan.postValue(rec);
        });
    }

    private void recomputeStats() {
        long taskId = currentTaskId;
        diskIO.execute(() -> {
            Set<Long> matched = new HashSet<>();
            List<ScanRecord> recs = db.scanRecordDao().findAllByTask(taskId);
            for (ScanRecord r : recs) {
                if (r.matchedAssetId != null) matched.add(r.matchedAssetId);
            }
            int extras = db.scanRecordDao().findExtras(taskId).size();
            StatusDerivation.Stats s = StatusDerivation.compute(expectedCount, matched, extras);

            int scanned = matched.size();
            int anomaly = Math.max(0, expectedCount - scanned);
            int todayAdded = 0; // 首版占位 — Asset 实体无 createdAt 字段
            List<CategoryCount> categories = db.assetDao().countByCategory(taskId);
            if (categories.size() > 3) categories = categories.subList(0, 3);
            long lastAt = db.inventoryTaskDao().findById(taskId).importedAt;

            stats.postValue(new Stats(expectedCount, scanned, anomaly, todayAdded,
                    s.found, s.missing, s.extra, categories, lastAt));
        });
    }

    private ScanRecord makeUnmatchedRecord(String epc, String tid, String rssi) {
        ScanRecord r = new ScanRecord();
        r.epc = epc; r.tid = tid; r.rssi = rssi;
        r.scannedAt = System.currentTimeMillis();
        return r;
    }

    /**
     * 删除当前任务下全部 Asset + ScanRecord 记录，并重置内存中的扫描状态。
     * 结果通过 {@link #clearResult()} 回调给 UI。
     */
    public void clearCurrentTask() {
        if (currentTaskId == -1L) {
            clearResult.postValue(new ClearResult("当前无任务"));
            return;
        }
        long taskId = currentTaskId;
        diskIO.execute(() -> {
            try {
                // 先删 ScanRecord（依赖 Asset 外键语义），再删 Asset
                int scans = db.scanRecordDao().deleteByTask(taskId);
                int assets = db.assetDao().deleteByTask(taskId);
                db.inventoryTaskDao().recomputeScannedCount(taskId);
                scannedEpcSet.clear();
                recomputeStats();
                clearResult.postValue(new ClearResult(assets, scans));
            } catch (Exception e) {
                Log.e(TAG, "clearCurrentTask failed", e);
                clearResult.postValue(new ClearResult(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        });
    }
}

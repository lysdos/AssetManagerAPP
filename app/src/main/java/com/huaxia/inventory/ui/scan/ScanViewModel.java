package com.huaxia.inventory.ui.scan;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.StatusDerivation;
import com.huaxia.inventory.data.dao.CategoryCount;
import com.huaxia.inventory.data.dao.ScanAggregate;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.data.entity.ScanRecord;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

public class ScanViewModel extends AndroidViewModel {

    private static final String TAG = "ScanViewModel";

    /** 多次连续扫描的统计合并窗口（毫秒）。 */
    private static final long STATS_DEBOUNCE_MS = 300L;

    public static class Stats {
        public final int inStock;
        public final int scanned;
        public final int anomaly;
        public final int todayAdded;
        public final int found;
        public final int missing;
        public final int extra;
        public final List<CategoryCount> categories;
        /** 最后一次扫描时间戳（毫秒），无扫描时为 0。语义从"导入时间"修正为"最后扫描时间"。 */
        public final long lastScannedAt;
        public Stats(int inStock, int scanned, int anomaly, int todayAdded,
                     int found, int missing, int extra,
                     List<CategoryCount> categories, long lastScannedAt) {
            this.inStock = inStock; this.scanned = scanned; this.anomaly = anomaly;
            this.todayAdded = todayAdded;
            this.found = found; this.missing = missing; this.extra = extra;
            this.categories = categories;
            this.lastScannedAt = lastScannedAt;
        }
    }

    private final InventoryApp app;
    private final AppDatabase db;
    private final Executor diskIO;
    // RFID 回调线程（前台）与 diskIO 后台线程都会读写 — 必须并发安全
    private final Set<String> scannedEpcSet = ConcurrentHashMap.newKeySet();
    private final MutableLiveData<Stats> stats = new MutableLiveData<>();
    private final MutableLiveData<ScanRecord> latestScan = new MutableLiveData<>();
    private final MutableLiveData<ClearResult> clearResult = new MutableLiveData<>();
    /** 主线程 Handler，用于把高频扫描触发的统计重算合并到 STATS_DEBOUNCE_MS 窗口。 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable recomputeRunnable = this::recomputeStats;
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
        // 切换任务时取消任何待执行的去抖重算，避免旧 taskId 的重算污染新任务
        mainHandler.removeCallbacks(recomputeRunnable);
        diskIO.execute(() -> {
            scannedEpcSet.clear();
            scannedEpcSet.addAll(db.scanRecordDao().findAllEpcsByTask(taskId));
            InventoryTask t = db.inventoryTaskDao().findById(taskId);
            expectedCount = t == null ? 0 : t.expectedCount;
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
            latestScan.postValue(rec);
            // 用去抖合并：300ms 内的多次扫描只触发一次 SQL 聚合，5/秒节奏下负载降 1500×
            scheduleRecomputeStats();
        });
    }

    /**
     * 把"重算统计"投递到主线程 Handler，300ms 内多次调用会被合并成一次真实执行。
     * 适合 {@link #onEpcScanned(String, String, String)} 这种高频入口。
     */
    private void scheduleRecomputeStats() {
        mainHandler.removeCallbacks(recomputeRunnable);
        mainHandler.postDelayed(recomputeRunnable, STATS_DEBOUNCE_MS);
    }

    /**
     * 立即重算统计：单次 SQL 聚合拿到 matched/extra/lastScannedAt，
     * 复杂度从原来的 O(n) 全表扫描降到 O(1)。
     */
    private void recomputeStats() {
        long taskId = currentTaskId;
        diskIO.execute(() -> {
            ScanAggregate agg = db.scanRecordDao().aggregateStats(taskId);
            int matched = agg.matchedCount;
            int extras = agg.extraCount;
            StatusDerivation.Stats s = StatusDerivation.compute(expectedCount, matched, extras);

            int scanned = matched;
            int anomaly = Math.max(0, expectedCount - scanned);
            int todayAdded = 0; // 首版占位 — Asset 实体无 createdAt 字段
            List<CategoryCount> categories = db.assetDao().countByCategory(taskId);
            if (categories.size() > 3) categories = categories.subList(0, 3);

            stats.postValue(new Stats(expectedCount, scanned, anomaly, todayAdded,
                    s.found, s.missing, s.extra, categories, agg.lastScannedAt));
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
        // 立即执行的重算不需要走 300ms 去抖
        mainHandler.removeCallbacks(recomputeRunnable);
        diskIO.execute(() -> {
            try {
                // 先删 ScanRecord（依赖 Asset 外键语义），再删 Asset
                int scans = db.scanRecordDao().deleteByTask(taskId);
                int assets = db.assetDao().deleteByTask(taskId);
                db.inventoryTaskDao().recomputeScannedCount(taskId);
                scannedEpcSet.clear();
                expectedCount = 0;
                recomputeStats();
                clearResult.postValue(new ClearResult(assets, scans));
            } catch (Exception e) {
                Log.e(TAG, "clearCurrentTask failed", e);
                clearResult.postValue(new ClearResult(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        });
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        // ViewModel 销毁时取消待执行的去抖任务，避免泄漏到下一个任务的 ViewModel
        mainHandler.removeCallbacks(recomputeRunnable);
    }
}

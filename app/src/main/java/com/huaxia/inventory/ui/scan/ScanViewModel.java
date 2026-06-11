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
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ScanRecord;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

public class ScanViewModel extends AndroidViewModel {

    private static final String TAG = "ScanViewModel";

    public static class Stats {
        public final int expected;
        public final int found;
        public final int missing;
        public final int extra;
        public Stats(int e, int f, int m, int x) {
            this.expected = e; this.found = f; this.missing = m; this.extra = x;
        }
    }

    private final InventoryApp app;
    private final AppDatabase db;
    private final Executor diskIO;
    private final Set<String> scannedEpcSet = new HashSet<>();
    private final MutableLiveData<Stats> stats = new MutableLiveData<>();
    private final MutableLiveData<ScanRecord> latestScan = new MutableLiveData<>();
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

    public void bindToTask(long taskId) {
        this.currentTaskId = taskId;
        diskIO.execute(() -> {
            scannedEpcSet.clear();
            scannedEpcSet.addAll(db.scanRecordDao().findAllEpcsByTask(taskId));
            expectedCount = db.inventoryTaskDao().findById(taskId).expectedCount;
            recomputeStats();
        });
    }

    /**
     * 处理一个扫描到的标签, 用归一化后的 16 位十进制固资编号匹配资产。
     *
     * @param rawCode 已归一化的 16 位十进制字符串 (由 ScanFragment 从 SDK hex 转换得到)
     * @param tid     SDK 报告的 TID, 透传存入 ScanRecord
     * @param rssi    SDK 报告的 RSSI, 透传存入 ScanRecord
     */
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
            stats.postValue(new Stats(expectedCount, s.found, s.missing, s.extra));
        });
    }

    private ScanRecord makeUnmatchedRecord(String epc, String tid, String rssi) {
        ScanRecord r = new ScanRecord();
        r.epc = epc; r.tid = tid; r.rssi = rssi;
        r.scannedAt = System.currentTimeMillis();
        return r;
    }
}

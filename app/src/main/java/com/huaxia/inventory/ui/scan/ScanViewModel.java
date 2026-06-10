package com.huaxia.inventory.ui.scan;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.StatusDerivation;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.util.EpcNormalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

public class ScanViewModel extends AndroidViewModel {

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

    public void onEpcScanned(String rawEpc, String tid, String rssi) {
        String epc = EpcNormalizer.normalize(rawEpc);
        if (epc == null) return;
        if (scannedEpcSet.contains(epc)) {
            latestScan.postValue(makeUnmatchedRecord(epc, tid, rssi));
            return;
        }
        scannedEpcSet.add(epc);

        long taskId = currentTaskId;
        diskIO.execute(() -> {
            Asset matched = db.assetDao().findByEpc(taskId, epc);
            ScanRecord rec = new ScanRecord();
            rec.taskId = taskId;
            rec.epc = epc;
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

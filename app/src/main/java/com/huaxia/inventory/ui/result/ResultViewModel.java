package com.huaxia.inventory.ui.result;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ScanRecord;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

public class ResultViewModel extends AndroidViewModel {

    public static final int PAGE_SIZE = 6;

    public static class Stats {
        public final int total, found, missing, extra;
        public Stats(int total, int found, int missing, int extra) {
            this.total = total; this.found = found; this.missing = missing; this.extra = extra;
        }
    }

    private final AppDatabase db;
    private final Executor diskIO;
    private final MutableLiveData<List<Asset>> assets = new MutableLiveData<>();
    private final MutableLiveData<List<ScanRecord>> extras = new MutableLiveData<>();
    private final MutableLiveData<Stats> stats = new MutableLiveData<>();
    private final MutableLiveData<ResultTab> activeTab = new MutableLiveData<>(ResultTab.FOUND);
    private final MutableLiveData<Integer> currentPage = new MutableLiveData<>(0);
    private long currentTaskId = -1L;

    public ResultViewModel(@NonNull Application application) {
        super(application);
        InventoryApp app = (InventoryApp) application;
        this.db = app.db;
        this.diskIO = app.executors.diskIO;
    }

    public LiveData<List<Asset>> assets() { return assets; }
    public LiveData<List<ScanRecord>> extras() { return extras; }
    public LiveData<Stats> stats() { return stats; }
    public LiveData<ResultTab> activeTab() { return activeTab; }
    public LiveData<Integer> currentPage() { return currentPage; }

    public void bindToTask(long taskId) {
        this.currentTaskId = taskId;
        refresh();
    }

    public void setTab(ResultTab tab) {
        activeTab.setValue(tab);
        currentPage.setValue(0);
        refresh();
    }

    public void nextPage() {
        Integer p = currentPage.getValue();
        currentPage.setValue((p == null ? 0 : p) + 1);
        refresh();
    }

    public void prevPage() {
        Integer p = currentPage.getValue();
        if (p != null && p > 0) currentPage.setValue(p - 1);
        refresh();
    }

    private void refresh() {
        long taskId = currentTaskId;
        ResultTab tab = activeTab.getValue() == null ? ResultTab.FOUND : activeTab.getValue();
        Integer pageObj = currentPage.getValue();
        int page = pageObj == null ? 0 : pageObj;
        int offset = page * PAGE_SIZE;
        diskIO.execute(() -> {
            int total = db.assetDao().countByTask(taskId);
            int found = db.assetDao().countFound(taskId);
            int missing = db.assetDao().countMissing(taskId);
            int extra = db.scanRecordDao().countExtras(taskId);
            stats.postValue(new Stats(total, found, missing, extra));

            List<Asset> assetResult;
            switch (tab) {
                case MISSING:
                    assetResult = db.assetDao().pageMissing(taskId, PAGE_SIZE, offset);
                    extras.postValue(Collections.emptyList());
                    break;
                case EXTRA:
                    assetResult = Collections.emptyList();
                    extras.postValue(db.scanRecordDao().pageExtra(taskId, PAGE_SIZE, offset));
                    break;
                case ALL:
                    assetResult = db.assetDao().pageAll(taskId, PAGE_SIZE, offset);
                    extras.postValue(Collections.emptyList());
                    break;
                case FOUND:
                default:
                    assetResult = db.assetDao().pageFound(taskId, PAGE_SIZE, offset);
                    extras.postValue(Collections.emptyList());
                    break;
            }
            assets.postValue(assetResult);
        });
    }
}

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

    private final AppDatabase db;
    private final Executor diskIO;
    private final MutableLiveData<List<Asset>> assets = new MutableLiveData<>();
    private final MutableLiveData<List<ScanRecord>> extras = new MutableLiveData<>();
    private final MutableLiveData<ResultTab> activeTab = new MutableLiveData<>(ResultTab.ALL);
    private final MutableLiveData<String> searchQuery = new MutableLiveData<>("");
    private long currentTaskId = -1L;

    public ResultViewModel(@NonNull Application application) {
        super(application);
        InventoryApp app = (InventoryApp) application;
        this.db = app.db;
        this.diskIO = app.executors.diskIO;
    }

    public LiveData<List<Asset>> assets() { return assets; }
    public LiveData<List<ScanRecord>> extras() { return extras; }
    public LiveData<ResultTab> activeTab() { return activeTab; }
    public LiveData<String> searchQuery() { return searchQuery; }

    public void bindToTask(long taskId) {
        this.currentTaskId = taskId;
        refresh();
    }

    public void setTab(ResultTab tab) {
        activeTab.setValue(tab);
        refresh();
    }

    public void setSearchQuery(String q) {
        searchQuery.setValue(q == null ? "" : q);
        refresh();
    }

    private void refresh() {
        long taskId = currentTaskId;
        ResultTab tab = activeTab.getValue();
        String q = searchQuery.getValue();
        diskIO.execute(() -> {
            List<Asset> result;
            switch (tab == null ? ResultTab.ALL : tab) {
                case FOUND:   result = db.assetDao().findFound(taskId); break;
                case MISSING: result = db.assetDao().findMissing(taskId); break;
                case EXTRA:   result = Collections.emptyList(); break;
                default:      result = db.assetDao().findAllByTask(taskId);
            }
            if (q != null && !q.isEmpty()) {
                String lower = q.toLowerCase();
                result.removeIf(a ->
                    !(contains(a.assetName, lower) || contains(a.epc, lower)
                      || contains(a.detailedLocation, lower)));
            }
            assets.postValue(result);

            List<ScanRecord> extraRecs = db.scanRecordDao().findExtras(taskId);
            if (q != null && !q.isEmpty()) {
                String lower = q.toLowerCase();
                extraRecs.removeIf(r -> !contains(r.epc, lower));
            }
            extras.postValue(extraRecs);
        });
    }

    private static boolean contains(String s, String lower) {
        return s != null && s.toLowerCase().contains(lower);
    }
}

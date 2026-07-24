package com.huaxia.inventory.ui.result;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ExtraClaim;
import com.huaxia.inventory.data.entity.ScanRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final MutableLiveData<List<ExtraWithClaim>> extras = new MutableLiveData<>();
    private final MutableLiveData<Stats> stats = new MutableLiveData<>(new Stats(0, 0, 0, 0));
    /**
     * 当前任务的 found 资产 id 集合. 只在 {@link #bindToTask} 时一次性拉取,
     * 不随翻页/列表刷新重算. UI (AssetResultAdapter + AssetDetailDialog)
     * 通过 observe 取, 不再各自在主线程/UI observer 内调
     * {@code assetDao().findFound(taskId)} 全量加载.
     */
    private final MutableLiveData<java.util.Set<Long>> matchedIds = new MutableLiveData<>(new java.util.HashSet<>());
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
    public LiveData<List<ExtraWithClaim>> extras() { return extras; }
    public LiveData<Stats> stats() { return stats; }
    public LiveData<java.util.Set<Long>> matchedIds() { return matchedIds; }
    public LiveData<ResultTab> activeTab() { return activeTab; }
    public LiveData<Integer> currentPage() { return currentPage; }

    public void bindToTask(long taskId) {
        this.currentTaskId = taskId;
        // 进结果页前先把所有 found 资产的 inventoryTime 回填一次 (幂等, 已有值的不动).
        // 然后一次性拉 found id 集合到 matchedIds — 不再让 UI 在 observer 内各自调
        // assetDao().findFound(taskId) 全表加载.
        diskIO.execute(() -> {
            db.assetDao().backfillFoundInventoryTime(taskId);
            java.util.Set<Long> foundIds = new java.util.HashSet<>();
            for (Asset a : db.assetDao().findFound(taskId)) foundIds.add(a.id);
            matchedIds.postValue(foundIds);
            refresh();
        });
    }

    public void setTab(ResultTab tab) {
        activeTab.setValue(tab);
        currentPage.setValue(0);
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

    /**
     * 重新拉取当前页的资产/扫描数据。供弹窗保存后调用，让列表显示最新的 rawRowJson 内容。
     */
    public void refresh() {
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
                    extras.postValue(loadExtrasWithClaim(taskId, PAGE_SIZE, offset));
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

    /**
     * 拉一页盘盈 + 对应的 ExtraClaim 补全信息。
     * 两步查: 先 pageExtra 拿 ScanRecord 列表, 再批量 findByScanRecordIds 拿补全信息。
     */
    private List<ExtraWithClaim> loadExtrasWithClaim(long taskId, int limit, int offset) {
        List<ScanRecord> records = db.scanRecordDao().pageExtra(taskId, limit, offset);
        if (records.isEmpty()) return Collections.emptyList();
        List<Long> ids = new ArrayList<>(records.size());
        for (ScanRecord r : records) ids.add(r.id);
        List<ExtraClaim> claims = db.extraClaimDao().findByScanRecordIds(ids);
        Map<Long, ExtraClaim> map = new HashMap<>();
        for (ExtraClaim c : claims) map.put(c.scanRecordId, c);
        List<ExtraWithClaim> out = new ArrayList<>(records.size());
        for (ScanRecord r : records) {
            out.add(new ExtraWithClaim(r, map.get(r.id)));
        }
        return out;
    }

    /**
     * 保存/更新盘盈补全信息。ScanRecord 行不动(matchedAssetId 保持 NULL),
     * 通过 unique(scanRecordId) 实现 UPSERT 语义。
     */
    public void saveExtraClaim(long scanRecordId, ExtraClaim claim, Runnable onSaved) {
        diskIO.execute(() -> {
            claim.scanRecordId = scanRecordId;
            claim.claimedAt = System.currentTimeMillis();
            db.extraClaimDao().insert(claim);
            if (onSaved != null) onSaved.run();
        });
    }
}

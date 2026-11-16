package com.huaxia.inventory.ui.result;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.ResultRepository;
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

    private final InventoryApp app;
    private final AppDatabase db;
    private final ResultRepository repo;
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
    // volatile: 主线程 bindToTask 写, diskIO 线程 refresh()/deleteExtra() 读 —
    // 与 ScanViewModel 用 AtomicLong 同理, 保证跨线程可见性 (executor 提交虽有
    // happens-before, 显式 volatile 更稳且对齐全项目风格).
    private volatile long currentTaskId = -1L;

    public ResultViewModel(@NonNull Application application) {
        super(application);
        InventoryApp app = (InventoryApp) application;
        this.app = app;
        this.db = app.getDb();
        this.repo = new ResultRepository(this.db);
        this.diskIO = app.getExecutors().diskIO;
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
            String status = statusOf(taskId);
            if (!"COMPLETED".equals(status)) {
                db.assetDao().backfillFoundInventoryTime(taskId);
            }
            // 已盘 id 集合。进行中用 id-only 投影（findFoundIds），不要用 page() ——
            // 后者会把整行 Asset（含 rawRowJson ~0.5-1KB）拉进内存，30w 任务约 150MB。
            java.util.Set<Long> foundIds;
            if ("COMPLETED".equals(status)) {
                foundIds = new java.util.HashSet<>();
                for (ResultRepository.ResultRow r : repo.page(taskId, status,
                        ResultRepository.Tab.FOUND, Integer.MAX_VALUE, 0)) {
                    foundIds.add(r.id);
                }
            } else {
                foundIds = new java.util.HashSet<>(db.assetDao().findFoundIds(taskId));
            }
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
        diskIO.execute(() -> {
            String status = statusOf(taskId);
            ResultRepository.Counts c = repo.counts(taskId, status);
            int total = c.expected;
            int found = c.found;
            int missing = c.missing;
            int extra = c.extra;
            stats.postValue(new Stats(total, found, missing, extra));

            // 夹页码: 删除末页最后一条后 currentPage 可能越过最后一页 → 列表会显示
            // "暂无数据"但前面还有行. 按当前 tab 的行数回退到最后一页, 并同步回
            // currentPage, 让分页控件 (上一页/下一页) 与列表一致.
            int tabCount;
            switch (tab) {
                case MISSING: tabCount = missing; break;
                case EXTRA:   tabCount = extra;   break;
                case ALL:     tabCount = total;   break;
                case FOUND:
                default:      tabCount = found;   break;
            }
            int lastPage = tabCount == 0 ? 0 : (tabCount - 1) / PAGE_SIZE;
            int effectivePage = Math.min(page, lastPage);
            if (effectivePage != page) currentPage.postValue(effectivePage);
            int offset = effectivePage * PAGE_SIZE;

            ResultRepository.Tab which;
            switch (tab) {
                case MISSING: which = ResultRepository.Tab.MISSING; break;
                case EXTRA:   which = ResultRepository.Tab.EXTRA;   break;
                case ALL:     which = ResultRepository.Tab.ALL;     break;
                case FOUND:
                default:      which = ResultRepository.Tab.FOUND;   break;
            }
            List<ResultRepository.ResultRow> rows =
                    repo.page(taskId, status, which, PAGE_SIZE, offset);
            if (which == ResultRepository.Tab.EXTRA) {
                assets.postValue(Collections.emptyList());
                extras.postValue(toExtras(rows));
            } else {
                assets.postValue(toAssets(rows));
                extras.postValue(Collections.emptyList());
            }
        });
    }

    /** 任务状态；任务不存在时按进行中处理（结果页对未知任务显示空列表）。 */
    private String statusOf(long taskId) {
        com.huaxia.inventory.data.entity.InventoryTask t =
                db.inventoryTaskDao().findById(taskId);
        return t == null ? "IN_PROGRESS" : t.status;
    }

    /**
     * ResultRow → Asset。
     *
     * <p>UI 层（适配器/详情弹窗）沿用 {@code Asset} 类型不变，这里只做字段搬运：
     * 已完成任务的快照行用 {@code -rowIndex} 作为 id（见 {@code ResultRepository}），
     * 只用于列表标记，不会再写库。</p>
     */
    private static List<Asset> toAssets(List<ResultRepository.ResultRow> rows) {
        List<Asset> out = new ArrayList<>(rows.size());
        for (ResultRepository.ResultRow r : rows) {
            // 进行中任务直接返回原始 Asset —— 详情弹窗保存是整行 @Update，
            // 手工拼的对象会把 taskId 写成 0（资产被踢出任务）并清空 orig_* 快照列。
            if (r.asset != null) {
                out.add(r.asset);
                continue;
            }
            Asset a = new Asset();
            a.id = r.id;
            a.epc = r.epc;
            a.assetType = r.assetType;
            a.assetModel = r.assetModel;
            a.serialNumber = r.serialNumber;
            a.custodian = r.custodian;
            a.dept = r.dept;
            a.inventoryTime = r.inventoryTime;
            a.rawRowJson = r.rawRowJson;
            out.add(a);
        }
        return out;
    }

    /**
     * ResultRow → ExtraWithClaim。
     *
     * <p>只有确实存在补全信息（任一业务字段非空）时才构造 claim，
     * 否则适配器会把未补全的盘盈误标成「已补全」。</p>
     */
    private static List<ExtraWithClaim> toExtras(List<ResultRepository.ResultRow> rows) {
        List<ExtraWithClaim> out = new ArrayList<>(rows.size());
        for (ResultRepository.ResultRow r : rows) {
            ScanRecord s = new ScanRecord();
            s.id = r.id;   // 补全弹窗用 scan.id 作为 scanRecordId 落库
            s.epc = r.epc;
            s.scannedAt = r.scannedAt == null ? 0L : r.scannedAt;
            s.rssi = r.rssi;

            boolean claimed = r.assetType != null || r.assetModel != null
                    || r.serialNumber != null || r.custodian != null || r.dept != null;
            ExtraClaim c = null;
            if (claimed) {
                c = new ExtraClaim();
                c.epc = r.epc;
                c.assetType = r.assetType;
                c.assetModel = r.assetModel;
                c.serialNumber = r.serialNumber;
                c.custodian = r.custodian;
                c.dept = r.dept;
            }
            out.add(new ExtraWithClaim(s, c));
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

    /**
     * 删除单条盘盈记录 (ScanRecord + 其 ExtraClaim).
     * <p>只影响 EXTRA tab — {@code ScanRecordDao.deleteExtraById} 带
     * {@code matchedAssetId IS NULL} 守卫, 不碰任何 Asset. 单事务: 先删
     * ExtraClaim 再删 ScanRecord, 任一步失败回滚, 不留"扫描记录删了但补全还在"。</p>
     * <p>删除后经 {@link InventoryApp#getSession()} 广播 EPC, 让 {@code ScanViewModel}
     * 从内存去重集驱逐 — 否则同标签重扫被"已见过"挡住、不落库。</p>
     */
    public void deleteExtra(long scanRecordId, String epc, Runnable onDeleted) {
        long taskId = currentTaskId;
        diskIO.execute(() -> {
            db.runInTransaction(() -> {
                db.extraClaimDao().deleteByScanRecordId(scanRecordId);
                db.scanRecordDao().deleteExtraById(scanRecordId);
            });
            app.getSession().notifyScanRemoved(taskId, epc);
            if (onDeleted != null) onDeleted.run();
        });
    }
}

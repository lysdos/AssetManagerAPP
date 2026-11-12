package com.huaxia.inventory.ui.asset;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.dao.AssetDao;
import com.huaxia.inventory.data.dao.CategoryCount;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.excel.AssetLedgerExporter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局资产台账 (资产 tab) 的状态.
 *
 * <p>一次刷新把总数 / 部门分布 / 类型分布 / 当前页放进同一个 {@link OverviewState},
 * 避免多个 LiveData 分别到达时 UI 出现"数字和列表不是同一快照"的闪烁.</p>
 *
 * <p>并发要点:</p>
 * <ul>
 *   <li>过滤条件只在主线程改; 查询在 diskIO.</li>
 *   <li>{@link #requestSeq} 在每次输入/筛选/翻页时自增, 旧查询完成时发现 token
 *       过期就丢弃 —— 防止快速输入时 "AB" 的结果晚于 "ABC" 到达并覆盖.</li>
 *   <li>一次刷新内的 5 个查询包在只读事务里, 拿到一致快照.</li>
 *   <li>监听 {@link AssetDao#countAllLive()} 作为 Asset 表失效信号; 导入、编辑、
 *       任务划拨/释放后自动刷新 (300ms 防抖吸收批量写入).</li>
 * </ul>
 */
public class AssetOverviewViewModel extends AndroidViewModel {

    private static final String TAG = "AssetOverviewVM";
    public static final int PAGE_SIZE = 20;
    private static final long DEBOUNCE_MS = 300L;

    /** 一次刷新的完整快照. 列表/分布/页码都来自同一次查询. */
    public static final class OverviewState {
        public final int totalAll;
        /** 全量台账中去重后的非空领用部门数，不受筛选影响. */
        public final int realDeptCount;
        public final int filteredTotal;
        public final int page;          // 0-based
        public final int pageCount;     // 0 when empty
        public final List<Asset> assets;
        public final List<CategoryCount> deptBuckets;
        public final List<CategoryCount> typeBuckets;
        public final String keyword;    // trimmed, "" = no keyword
        @Nullable public final String deptBucket; // null = all; "" = 未填写部门
        @Nullable public final String typeBucket; // null = all; "" = 未分类
        public final boolean typeExpanded;

        public OverviewState(int totalAll, int realDeptCount, int filteredTotal,
                             int page, int pageCount,
                             List<Asset> assets, List<CategoryCount> deptBuckets,
                             List<CategoryCount> typeBuckets, String keyword,
                             @Nullable String deptBucket, @Nullable String typeBucket,
                             boolean typeExpanded) {
            this.totalAll = totalAll;
            this.realDeptCount = realDeptCount;
            this.filteredTotal = filteredTotal;
            this.page = page;
            this.pageCount = pageCount;
            this.assets = assets;
            this.deptBuckets = deptBuckets;
            this.typeBuckets = typeBuckets;
            this.keyword = keyword;
            this.deptBucket = deptBucket;
            this.typeBucket = typeBucket;
            this.typeExpanded = typeExpanded;
        }

        public OverviewState withTypeExpanded(boolean expanded) {
            return new OverviewState(totalAll, realDeptCount, filteredTotal, page, pageCount,
                    assets, deptBuckets, typeBuckets, keyword, deptBucket, typeBucket, expanded);
        }
    }

    public interface LedgerLoadCallback {
        void onLoaded(@NonNull Asset asset, @Nullable InventoryTask task);
        void onMissing();
    }

    public enum SaveResult { SUCCESS, FROZEN, MISSING, ERROR }

    public interface LedgerSaveCallback {
        void onResult(@NonNull SaveResult result);
    }

    /** 资产台账导出结果，经 LiveData 投递到主线程. */
    public static final class ExportResult {
        public final boolean success;
        public final int count;
        /** 发起导出的序号；用于丢弃旧导出晚到的结果. */
        public final long token;

        public ExportResult(boolean success, int count, long token) {
            this.success = success;
            this.count = count;
            this.token = token;
        }
    }

    private final AppDatabase db;
    private final AssetDao dao;
    private final Executor diskIO;
    private final Executor excelIO;
    @Nullable private final Handler handler;

    private final MediatorLiveData<OverviewState> state = new MediatorLiveData<>();
    private final MutableLiveData<ExportResult> exportResult = new MutableLiveData<>();
    private final AtomicInteger requestSeq = new AtomicInteger();
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final AtomicBoolean exportRunning = new AtomicBoolean(false);
    private final AtomicLong exportToken = new AtomicLong();
    private final Runnable refreshRunnable = () -> refresh(requestSeq.get());

    // 仅主线程读写
    private String keyword = "";
    @Nullable private String deptBucket;
    @Nullable private String typeBucket;
    private int requestedPage = 0;
    // 仅主线程改写; volatile 让后台刷新读到用户最近一次展开/收起操作,
    // 避免旧查询结果 postValue 时把 typeExpanded 覆盖回旧值.
    private volatile boolean typeExpanded = false;

    public AssetOverviewViewModel(@NonNull Application app) {
        this(app, null, null, new Handler(Looper.getMainLooper()));
    }

    /**
     * @param handlerOverride null = 立即刷新 (测试用, 便于同步断言); 生产传主线程 Handler.
     */
    @VisibleForTesting
    public AssetOverviewViewModel(@NonNull Application app,
                                  @Nullable AppDatabase dbOverride,
                                  @Nullable Executor executorOverride,
                                  @Nullable Handler handlerOverride) {
        super(app);
        InventoryApp inventoryApp = app instanceof InventoryApp ? (InventoryApp) app : null;
        if (dbOverride != null) {
            this.db = dbOverride;
        } else {
            if (inventoryApp == null) {
                throw new IllegalArgumentException("InventoryApp required without db override");
            }
            this.db = inventoryApp.getDb();
        }
        this.dao = db.assetDao();
        if (executorOverride != null) {
            this.diskIO = executorOverride;
            this.excelIO = executorOverride;
        } else {
            if (inventoryApp == null) {
                throw new IllegalArgumentException("InventoryApp required without executor override");
            }
            this.diskIO = inventoryApp.getExecutors().diskIO;
            this.excelIO = inventoryApp.getExecutors().excelIO;
        }
        this.handler = handlerOverride;

        // Asset 表任意变更 (导入/编辑/任务划拨) 都触发一次防抖刷新.
        state.addSource(dao.countAllLive(), count -> scheduleRefresh());
        refreshNow();
    }

    public LiveData<OverviewState> state() {
        return state;
    }

    /** 导出完成事件；没有导出进行时可能保留上一次结果，由弹窗消费/清空. */
    public LiveData<ExportResult> exportResult() {
        return exportResult;
    }

    public boolean isExportRunning() {
        return exportRunning.get();
    }

    public long currentExportToken() {
        return exportToken.get();
    }

    public void clearExportResult() {
        exportResult.setValue(null);
    }

    public void setKeyword(@Nullable String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.equals(keyword)) return;
        keyword = trimmed;
        requestedPage = 0;
        scheduleRefresh();
    }

    public void toggleDeptBucket(@Nullable String bucket) {
        deptBucket = Objects.equals(deptBucket, bucket) ? null : bucket;
        requestedPage = 0;
        refreshNow();
    }

    public void toggleTypeBucket(@Nullable String bucket) {
        typeBucket = Objects.equals(typeBucket, bucket) ? null : bucket;
        requestedPage = 0;
        refreshNow();
    }

    public void clearFilters() {
        keyword = "";
        deptBucket = null;
        typeBucket = null;
        requestedPage = 0;
        refreshNow();
    }

    public void nextPage() {
        OverviewState s = state.getValue();
        if (s == null || s.page + 1 >= s.pageCount) return;
        requestedPage = s.page + 1;
        refreshNow();
    }

    public void prevPage() {
        OverviewState s = state.getValue();
        if (s == null || s.page <= 0) return;
        requestedPage = s.page - 1;
        refreshNow();
    }

    public void toggleTypeExpanded() {
        typeExpanded = !typeExpanded;
        OverviewState s = state.getValue();
        if (s != null) state.setValue(s.withTypeExpanded(typeExpanded));
    }

    private void scheduleRefresh() {
        int token = requestSeq.incrementAndGet();
        if (handler == null) {
            refresh(token);
            return;
        }
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, DEBOUNCE_MS);
    }

    private void refreshNow() {
        if (handler != null) handler.removeCallbacks(refreshRunnable);
        int token = requestSeq.incrementAndGet();
        refresh(token);
    }

    private void refresh(final int token) {
        if (disposed.get()) return;
        final String kw = keyword;
        final String dept = deptBucket;
        final String type = typeBucket;
        final int pageReq = requestedPage;
        final String pattern = likePattern(kw);

        diskIO.execute(() -> {
            if (disposed.get() || token != requestSeq.get()) return;
            final OverviewState[] out = new OverviewState[1];
            try {
                db.runInTransaction(() -> {
                    int totalAll = dao.countAll();
                    int realDeptCount = dao.countRealDepts();
                    int filtered = dao.countFiltered(pattern, dept, type);
                    int pageCount = filtered == 0 ? 0 : (filtered - 1) / PAGE_SIZE + 1;
                    int page = pageCount == 0 ? 0 : Math.min(pageReq, pageCount - 1);
                    List<Asset> assets = filtered == 0
                            ? Collections.emptyList()
                            : dao.pageFiltered(pattern, dept, type, PAGE_SIZE, page * PAGE_SIZE);
                    List<CategoryCount> depts = dao.deptDistribution(pattern, type);
                    List<CategoryCount> types = dao.typeDistribution(pattern, dept);
                    out[0] = new OverviewState(totalAll, realDeptCount, filtered, page, pageCount,
                            assets, depts, types, kw, dept, type, typeExpanded);
                });
            } catch (RuntimeException e) {
                Log.e(TAG, "refresh failed", e);
                return;
            }
            if (disposed.get() || token != requestSeq.get()) return;
            state.postValue(out[0]);
        });
    }

    public void loadLedgerAsset(long assetId, @NonNull LedgerLoadCallback cb) {
        diskIO.execute(() -> {
            if (disposed.get()) return;
            final Asset asset = dao.findById(assetId);
            InventoryTask task = null;
            if (asset != null && asset.taskId != 0L) {
                task = db.inventoryTaskDao().findById(asset.taskId);
            }
            final InventoryTask finalTask = task;
            postToMain(() -> {
                if (disposed.get()) return;
                if (asset == null) cb.onMissing();
                else cb.onLoaded(asset, finalTask);
            });
        });
    }

    /**
     * 台账编辑写入口. 只更新 6 个业务字段 + raw_row_json; 事务内重新校验任务状态,
     * 防止弹窗打开后任务被完成/删除的竞态.
     */
    public void saveLedger(@NonNull Asset pending, @NonNull LedgerSaveCallback cb) {
        final Asset p = pending;
        diskIO.execute(() -> {
            if (disposed.get()) return;
            final SaveResult[] result = {SaveResult.ERROR};
            try {
                db.runInTransaction(() -> {
                    Asset current = dao.findById(p.id);
                    if (current == null) {
                        result[0] = SaveResult.MISSING;
                        return;
                    }
                    if (current.taskId != 0L) {
                        InventoryTask task = db.inventoryTaskDao().findById(current.taskId);
                        if (task == null || "COMPLETED".equals(task.status)) {
                            result[0] = SaveResult.FROZEN;
                            return;
                        }
                    }
                    int rows = dao.updateBusinessFieldsById(p.id, p.assetType, p.assetModel,
                            p.serialNumber, p.custodian, p.dept, p.rawRowJson);
                    result[0] = rows > 0 ? SaveResult.SUCCESS : SaveResult.MISSING;
                });
            } catch (RuntimeException e) {
                // 不打印 EPC 明文; 异常类型足够定位
                Log.e(TAG, "saveLedger failed", e);
                result[0] = SaveResult.ERROR;
            }
            final SaveResult r = result[0];
            postToMain(() -> {
                if (!disposed.get()) cb.onResult(r);
            });
        });
    }

    /**
     * 导出资产台账. {@code filtered=true} 使用调用方传入的筛选快照（可从
     * savedInstanceState 恢复，避免进程重建后静默退化成全量），否则导出全量台账.
     *
     * <p>先写入 cache 临时文件，成功后再以 {@code "wt"} 模式覆盖目标 SAF 文档；
     * 避免导出中途失败时在用户选择的位置留下一个"能打开但缺行"的半成品 xlsx，
     * 也避免部分 Provider 的 {@code "w"} 模式不截断旧文件尾部。</p>
     */
    public void exportLedger(boolean filtered,
                             @Nullable String keyword,
                             @Nullable String deptBucket,
                             @Nullable String typeBucket,
                             @NonNull Uri uri) {
        if (disposed.get()) return;
        if (!exportRunning.compareAndSet(false, true)) {
            exportResult.postValue(new ExportResult(false, 0, exportToken.get()));
            return;
        }
        final long token = exportToken.incrementAndGet();
        exportResult.setValue(null);

        final String pattern = likePattern(filtered ? keyword : "");
        final String dept = filtered ? deptBucket : null;
        final String type = filtered ? typeBucket : null;
        final Context ctx = getApplication().getApplicationContext();
        final File cacheDir = ctx.getCacheDir();

        excelIO.execute(() -> {
            int count = -1;
            File tmp = null;
            try {
                // 1) 先完整生成到 cache 临时文件；失败不会碰目标文档。
                tmp = File.createTempFile("asset_ledger_", ".xlsx", cacheDir);
                try (OutputStream fileOut = new FileOutputStream(tmp)) {
                    count = AssetLedgerExporter.export(fileOut, dao, pattern, dept, type, cacheDir);
                }
                // 2) 成功后再覆盖目标；"wt" 明确要求截断，避免旧文件尾部残留。
                try (InputStream in = new FileInputStream(tmp);
                     OutputStream out = ctx.getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) {
                        throw new IllegalStateException("openOutputStream returned null");
                    }
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                }
            } catch (Exception e) {
                // 不打印 URI / 文件路径; 异常类型足够定位
                Log.e(TAG, "exportLedger failed: " + e.getClass().getSimpleName());
                count = -1;
            } finally {
                if (tmp != null) {
                    tmp.delete();
                }
                exportRunning.set(false);
                exportResult.postValue(new ExportResult(count >= 0, Math.max(count, 0), token));
            }
        });
    }

    private void postToMain(@NonNull Runnable r) {
        if (handler == null) {
            r.run();
        } else {
            handler.post(r);
        }
    }

    private static String likePattern(@Nullable String keyword) {
        return com.huaxia.inventory.util.SqlLikeEscaper.likePattern(keyword);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        disposed.set(true);
        requestSeq.incrementAndGet();
        if (handler != null) handler.removeCallbacks(refreshRunnable);
    }
}

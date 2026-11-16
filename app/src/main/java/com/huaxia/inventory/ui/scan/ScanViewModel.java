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
import com.huaxia.inventory.session.InventorySession;
import com.huaxia.inventory.util.DateUtil;
import com.huaxia.inventory.util.Event;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.Observer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
    private final MutableLiveData<Event<ClearResult>> clearResult = new MutableLiveData<>();
    /** 主线程 Handler，用于把高频扫描触发的统计重算合并到 STATS_DEBOUNCE_MS 窗口。 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable recomputeRunnable = this::recomputeStats;
    /**
     * diskIO 4 线程池里多条 runnable 共享读写, 改 {@link AtomicLong} 替代普通
     * long 字段 — long 在 32-bit JVM 上非原子, 跨线程读写还缺乏 happens-before.
     * AtomicLong 同时保证原子读写 + 提供 volatile 等价 happens-before.
     * 当前用 {@code currentTaskId.get()} 读, {@code currentTaskId.set(id)} 写.
     */
    @VisibleForTesting
    private final AtomicLong currentTaskId = new AtomicLong(-1L);
    /**
     * 当前任务的预期资产数。跨 diskIO 4 线程池读写 (bindToTask / onSessionCleared /
     * recomputeStats 可能落在不同线程), 故用 {@link AtomicInteger} 保证可见性 ——
     * 与 {@link #currentTaskId} 同理; 普通 int 会让 recomputeStats 读到上一任务的旧值,
     * 统计出错的盘亏/盘盈。
     */
    private final AtomicInteger expectedCount = new AtomicInteger(0);
    /**
     * 外部 bind 时锁定的 taskId. {@link AtomicLong} 是为了 SessionObserver
     * 在 diskIO 线程 race-bind 设置与主线程重置时不被撕裂. 默认 -1 表示未绑定.
     */
    private final AtomicLong boundTaskId = new AtomicLong(-1L);
    /**
     * VM 是否已被 {@link #onCleared()} 反初始化 — 用 AtomicBoolean 标记.
     * 在高频扫描路径 {@link #onEpcScanned}, {@link #resetScanProgress},
     * {@link #recomputeStats} 等 {@code diskIO.execute} 入口检查, 防止 VM 销毁
     * 后仍有 in-flight runnable 在 diskIO 线程池持 this 强引用、做完工作才
     * 释放. 否则高频扫描 + 频繁切换 task (旋转屏幕 / 任务页切换) 会累积
     * 10MB+ heap 残留 — 因每个未完成 runnable 都连带持有的
     * {@code scannedEpcSet} (10w+ EPC ~5MB) / lambda 闭包不下沉.
     */
    private final java.util.concurrent.atomic.AtomicBoolean disposed =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /**
     * 显式 Observer 实例, 以便 {@link #onCleared()} 能 removeObserver. lambda
     * 形式 observer 无法 remove, 会让 ScanViewModel 与 InventorySession 互相持有
     * 引用, 越过 ViewModel 生命周期仍存活.
     */
    private final Observer<Long> sessionObserver = this::onSessionTaskIdChanged;
    /**
     * 单条扫描记录删除广播的 observer: 结果页删除盘盈行后, 把该 EPC 从
     * {@link #scannedEpcSet} 移除, 否则同标签重扫被"已见过"挡住、不落库.
     * 同样用显式实例以便 {@link #onCleared()} 反注册.
     */
    private final Observer<InventorySession.ScanRemoval> scanRemovalObserver =
            this::onScanRemoved;

    public ScanViewModel(@NonNull Application application) {
        super(application);
        this.app = (InventoryApp) application;
        this.db = app.getDb();
        this.diskIO = app.getExecutors().diskIO;
        // observeForever: ViewModel 生命周期长于 Fragment/Activity, 让 ScanViewModel
        // 持续监听 session.taskId 变化. 删除/暂停/切换任务路径下, 外部把 session 清空
        // 或改到其他 taskId, 此 observer 立刻感知并同步 vm 内部状态 (scannedEpcSet /
        // currentTaskId / rfid), 避免边界不一致写出孤儿 ScanRecord.
        app.getSession().currentTaskId().observeForever(sessionObserver);
        // 订阅单条删除广播 — 结果页删盘盈行时驱逐内存去重集对应 EPC.
        app.getSession().scanRemovals().observeForever(scanRemovalObserver);
    }

    public LiveData<Stats> stats() { return stats; }
    public LiveData<ScanRecord> latestScan() { return latestScan; }
    public LiveData<Event<ClearResult>> clearResult() { return clearResult; }

    /** 一次性事件: 包成 {@link Event} 投递, 防旋转后 observer 重放 (重复 Snackbar). */
    private void postClearResult(ClearResult result) {
        clearResult.postValue(new Event<>(result));
    }

    /** 重置扫描进度的结果: scans=被删 ScanRecord 行数, error=异常消息。 */
    public static class ClearResult {
        public final int scans;
        public final String error;
        public ClearResult(int scans) { this.scans = scans; this.error = null; }
        public ClearResult(String error) { this.scans = 0; this.error = error; }
        public boolean isError() { return error != null; }
    }

    public void bindToTask(long taskId) {
        if (disposed.get()) return;
        this.currentTaskId.set(taskId);
        this.boundTaskId.set(taskId);
        // 切换任务时取消任何待执行的去抖重算，避免旧 taskId 的重算污染新任务
        mainHandler.removeCallbacks(recomputeRunnable);
        diskIO.execute(() -> {
            if (disposed.get()) return;
            scannedEpcSet.clear();
            java.util.List<String> existingEpcs =
                    db.scanRecordDao().findAllEpcsByTask(taskId);
            // 给资产超大的任务一个明显的进度点 — 10w+ EPC 单次加载 ≈ 5-10MB 堆,
            // 100w+ 时单 Fragment 会吃到 50MB+; 此处不强制截断 (截断会漏检),
            // 由 Log 让运维/QA 在真机观察. 如真出现 OOM, 后续切到 BloomFilter.
            // 不打印 taskId — 当前 inventory_task.id 属业务关联标识, 暴露给
            // logcat/采集通道有过 PII 面; 只报告 EPC 数量用于排障足够.
            Log.i(TAG, "bindToTask: loaded " + existingEpcs.size()
                    + " existing EPCs into in-memory dedup set");
            scannedEpcSet.addAll(existingEpcs);
            InventoryTask t = db.inventoryTaskDao().findById(taskId);
            if (t == null) {
                // 任务在 bindToTask 同步设值与异步 findById 之间被其他线程 deleteTask
                // 释放回池 (TaskListViewModel.deleteTask 先 release 再 deleteById),
                // findById 返 null, 但 currentTaskId 已是已删 taskId — 写 ScanRecord
                // 会变孤儿. 同步把 vm 状态回滚到 -1L, 通知 UI 空数据, 避免误导.
                Log.w(TAG, "bindToTask: task no longer exists, rolling back VM state");
                currentTaskId.set(-1L);
                boundTaskId.set(-1L);
                expectedCount.set(0);
                scannedEpcSet.clear();
                app.getSession().clear();
                app.getRfid().stopInventory();
                return;
            }
            expectedCount.set(t.expectedCount);
            recomputeStats();
        });
    }

    /**
     * Session 全局 taskId 变化的回调. 触发场景:
     * <ul>
     *   <li>TaskListViewModel.deleteTask 释放当前任务 → session.clear()</li>
     *   <li>TaskListViewModel.switchToTask / pauseTask / resumeTask 切到别的 taskId</li>
     * </ul>
     * 若新值与 vm 当前绑定值不一致, 视为外部把绑定任务换走, 重置 vm 内部状态
     * (不删 DB 行 — deleteTask 那侧已经在自身事务里完成), 防止 rfid-poll 继续 emit
     * 把 ScanRecord 写到已删 taskId.
     * <p>注: {@link #resetScanProgress()} 不改 session (只清扫描记录), 故不触发本回调。</p>
     */
    private void onSessionTaskIdChanged(Long sessionId) {
        if (sessionId == null) sessionId = -1L;
        long bound = boundTaskId.get();
        if (bound == sessionId) {
            return; // session 值与 vm 当前绑定一致 — 无需处理
        }
        if (bound == -1L && sessionId == -1L) {
            return; // 已经 unbound
        }
        // bound 不为 -1 但被外部改成别的 (-1 或新 taskId)
        Log.i(TAG, "session taskId changed while bound (bound=" + bound
                + ", session=" + sessionId + ") — clearing VM state without DB delete");
        onSessionCleared();
    }

    /**
     * 仅清 vm 内部状态 + 停 RFID, 不删 DB 行 (外部 deleteTask / 切任务 那侧已落地).
     * 与 {@link #resetScanProgress()} 的差异: 后者保留任务与资产、只删扫描记录, 且不重置
     * VM (taskId 不变); 这里假定外部已改/删该任务, 故整机复位。
     * 由 {@link #onSessionTaskIdChanged(Long)} 触发.
     */
    private void onSessionCleared() {
        // 用 CAS 避免与 bindToTask 同一帧的二次触发的双重重置 (清一次足够).
        long prev = boundTaskId.getAndSet(-1L);
        if (prev == -1L) {
            return;
        }
        mainHandler.removeCallbacks(recomputeRunnable);
        diskIO.execute(() -> {
            scannedEpcSet.clear();
            expectedCount.set(0);
            currentTaskId.set(-1L);
            app.getRfid().stopInventory();
            stats.postValue(new Stats(0, 0, 0, 0, 0, 0, 0,
                    java.util.Collections.emptyList(), 0L));
        });
    }

    /**
     * 结果页单条删除盘盈记录后, 把该 EPC 从内存去重集移除.
     * <p>不驱逐的后果: {@link #onEpcScanned} 的内存 dedup 命中该 EPC, 直接 return 不写库 —
     * 用户删掉误扫记录后重扫同一标签会"无反应"、计数不涨. 仅处理当前绑定任务,
     * 其它任务 / 无绑定 (taskId=-1) 直接忽略.</p>
     */
    private void onScanRemoved(InventorySession.ScanRemoval r) {
        if (r == null || r.epc == null) return;
        if (r.taskId != currentTaskId.get()) return;
        scannedEpcSet.remove(r.epc);
    }

    /**
     * 处理一次 RFID 标签读数. 调用方: {@code ScanFragment.rfidCallback},
     * 由 SDK poll thread 直接 dispatch. 按绑定状态分三条互斥路径, 行为不同:
     *
     * <ol>
     *   <li><b>未绑定任务（读码模式）</b> ({@code currentTaskId == -1L}):
     *       扫描页无任务时进入读码模式. 只把归一化编码发到 {@code latestScan}
     *       占位 record ({@link #makeUnmatchedRecord}) 供 UI 显示, <b>不</b>写 DB —
     *       避免落 {@code taskId=-1L} 的孤儿 ScanRecord
     *       ({@code deleteOrphans()} 只清 {@code matchedAssetId} 非空的行, 这类行永不回收).</li>
     *   <li><b>内存层去重命中</b> ({@link #scannedEpcSet}):
     *       该 EPC 在当前 ViewModel 生命周期内已见过. 仍发 {@code latestScan}
     *       占位 record ({@link #makeUnmatchedRecord}, 字段无 taskId /
     *       matchedAssetId) 让 UI 滚动显示"已盘", 但**不**写 DB —
     *       这是设计选择: 早期 inventory 阶段用户反复长按扳机会高频触发同 EPC,
     *       内存 dedup 避免 DB IO; 但因为没真落库, 切页面回来 latestScan 不会
     *       再看到那条记录. 若后续产品需求是"重复也要历史留痕", 这条路径要改为
     *       调 {@code insert} (SQLite UNIQUE IGNORE 兜底).</li>
     *   <li><b>DB 层去重命中</b> ({@link ScanRecordDao#insert} 返回 {@code -1L}):
     *       这条路径只在"内存 dedup 已经 add 但 DB 还没 commit 前另一线程同 EPC
     *       抢占" 的极短窗口出现. 当前直接 {@code return}, 不发 latestScan
     *       也不重算统计 — 因为如果并发路径都 postValue, UI 会看到同一 EPC 两条,
     *       统计会多计.</li>
     * </ol>
     *
     * <p>各路径的语义差异在 UI 表面看不出来, 但维护者改写时容易踩坑:
     * 修改前请确认改动是否会破坏 latestScan LiveData 的"每次扫描一条"不变量.</p>
     */
    public void onEpcScanned(String rawCode, String tid, String rssi) {
        if (rawCode == null || rawCode.isEmpty()) {
            Log.w(TAG, "onEpcScanned called with null/empty code");
            return;
        }
        if (disposed.get()) {
            // VM 已 onCleared — 还在走 rfid poll callback 的话, 直接丢弃;
            // 否则会落到 taskId (这时已经被 bindToTask/resetScanProgress 推到 -1L)
            // 写 taskId=-1L 孤儿 ScanRecord.
            return;
        }
        long taskId = currentTaskId.get();
        if (taskId == -1L) {
            // 路径 0: 未绑定任务(扫描页"读码模式") — 不落库, 仅把最近读取的编码
            // 广播给 UI (latestScan). 避免写 taskId=-1L 的孤儿 ScanRecord —
            // deleteOrphans() 只清 matchedAssetId 非空的行, 这类行不会被回收.
            latestScan.postValue(makeUnmatchedRecord(rawCode, tid, rssi));
            return;
        }
        if (scannedEpcSet.contains(rawCode)) {
            // 路径 1: 内存 dedup 命中 — 发 latestScan 占位, 不写 DB.
            // 占位 record 不带 taskId / matchedAssetId, UI 须按纯 "已扫过" 展示.
            latestScan.postValue(makeUnmatchedRecord(rawCode, tid, rssi));
            return;
        }
        scannedEpcSet.add(rawCode);

        diskIO.execute(() -> {
            if (disposed.get()) return;
            // findByEpc + insert 包在单一事务内 — 避免并发同 EPC 路径下
            // (a) matchedAssetId 落到一个 stale 的 Asset.id; (b) ScanRecord 唯一索引
            // 约束在事务边界外被另一线程先占. 同 epc 的多线程并发仍由
            // ScanRecordDao.insert 的 OnConflictStrategy.IGNORE 兜底.
            ScanRecord rec = new ScanRecord();
            rec.taskId = taskId;
            rec.epc = rawCode;
            rec.tid = tid;
            rec.rssi = rssi;
            rec.scannedAt = System.currentTimeMillis();
            long[] newIdHolder = {-1L};
            db.runInTransaction(() -> {
                Asset matched = db.assetDao().findByEpc(taskId, rawCode);
                rec.matchedAssetId = matched == null ? null : matched.id;
                newIdHolder[0] = db.scanRecordDao().insert(rec);
                // 命中即在写入路径上盖盘点时间（幂等，只填 NULL）。这样所有读入口
                // 都不必再为了「盘点时间」列做补写 —— 之前是在 GET 接口里补，30w
                // 任务每翻一页就触发一次全量 UPDATE。
                if (matched != null) {
                    db.assetDao().setInventoryTimeIfNull(matched.id, rec.scannedAt);
                }
            });
            long newId = newIdHolder[0];
            // 路径 2: DB UNIQUE IGNORE 命中 — 不发 latestScan 不调统计, 避免显示两条.
            // 注意: 这里不打 rawCode 明文, 见 {@code LogRedactionTest}.
            if (newId == -1L) {
                Log.d(TAG, "duplicate scan ignored by IGNORE: epc(len="
                        + rawCode.length() + ")");
                return;
            }
            rec.id = newId;
            latestScan.postValue(rec);
            // recomputeScannedCount 已并入 scheduleRecomputeStats 内部的 recomputeStats,
            // 避免每次新 EPC 都跑一次 COUNT(DISTINCT matchedAssetId) 全表子查询.
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
     * Public refresh hook so other fragments (e.g. ManualScanFragment writing
     * a ScanRecord directly via dao) can nudge the scan-page stats without
     * owning the ViewModel. Cheap (one SQL aggregate) — safe to call on
     * resume or after an external write.
     */
    public void refreshStats() {
        mainHandler.removeCallbacks(recomputeRunnable);
        recomputeStats();
    }

    /**
     * 立即重算统计：单次 SQL 聚合拿到 matched/extra/lastScannedAt，
     * 复杂度从原来的 O(n) 全表扫描降到 O(1)。
     */
    private void recomputeStats() {
        long taskId = currentTaskId.get();
        diskIO.execute(() -> {
            if (disposed.get()) return;

            // 已完成任务的资产已回库存池、扫描记录已删 —— 实时查询会全部返回 0，
            // 必须改读冻结快照（与结果页同一数据源，见 ResultRepository）。
            com.huaxia.inventory.data.entity.InventoryTask task =
                    db.inventoryTaskDao().findById(taskId);
            if (task != null && "COMPLETED".equals(task.status)) {
                com.huaxia.inventory.data.ResultRepository.Counts c =
                        new com.huaxia.inventory.data.ResultRepository(db)
                                .counts(taskId, task.status);
                // 注意：绝不能调 recomputeScannedCount —— 扫描记录已删，它会把任务行上的
                // scannedUniqueCount 冲成 0，污染任务列表的进度显示。
                long lastScanned = db.taskResultDao().maxInventoryTime(taskId);
                stats.postValue(new Stats(c.expected, c.found, c.missing, 0,
                        c.found, c.missing, c.extra,
                        java.util.Collections.emptyList(), lastScanned));
                return;
            }

            // 300ms 去抖合并: 一次 recomputeStats 同时刷新"已盘去重计数"
            // (SQL UPDATE+COUNT(DISTINCT)) 和"统计聚合 (matched/extra/lastScannedAt)",
            // 避免每个新 EPC 都触发全表子查询.
            db.inventoryTaskDao().recomputeScannedCount(taskId);
            ScanAggregate agg = db.scanRecordDao().aggregateStats(taskId);
            int matched = agg.matchedCount;
            int extras = agg.extraCount;
            // 快照一次, 让本次统计用同一个 expectedCount 值 (AtomicInteger 保证可见性)
            int expected = expectedCount.get();
            StatusDerivation.Stats s = StatusDerivation.compute(expected, matched, extras);

            int scanned = matched;
            int anomaly = Math.max(0, expected - scanned);
            // 本任务内"今天首次导入"的资产数 — 自然日窗口从本地 00:00 起算.
            int todayAdded = db.assetDao().countCreatedAfter(taskId, DateUtil.startOfToday());
            List<CategoryCount> categories = db.assetDao().countByCategory(taskId);
            if (categories.size() > 3) categories = categories.subList(0, 3);

            stats.postValue(new Stats(expected, scanned, anomaly, todayAdded,
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
     * 重置当前任务的扫描进度: 删本任务的 ScanRecord + 盘盈补全 (ExtraClaim),
     * 并把编辑过的资产字段回滚到划拨快照、清盘点时间; <b>保留任务、资产与快照</b>,
     * 并清空内存去重集 → 可原地重新扫描 (不必重建任务 / 重导 Excel)。
     * 结果通过 {@link #clearResult()} 回调给 UI。
     *
     * <p>关键: 必须清 {@link #scannedEpcSet} —— 否则重扫同样的标签会被"已见过"的内存
     * dedup 挡掉、不写库、计数不涨。</p>
     * <p>不停 RFID: 任务未变, 不会产生 {@code taskId=-1L} 孤儿记录; 重置后用户可接着扫。</p>
     */
    public void resetScanProgress() {
        if (disposed.get()) return;
        long taskId = currentTaskId.get();
        if (taskId == -1L) {
            postClearResult(new ClearResult("当前无任务"));
            return;
        }
        // 立即执行的重算不需要走 300ms 去抖
        mainHandler.removeCallbacks(recomputeRunnable);
        diskIO.execute(() -> {
            if (disposed.get()) return;
            final int[] scansHolder = {0};
            try {
                // 单事务: 删盘盈补全 + ScanRecord — 任一步失败回滚, 不留"扫描删了但补全还在"。
                // 顺序不可颠倒: ExtraClaim.deleteByTask 的谓词是
                // `scanRecordId IN (SELECT id FROM ScanRecord WHERE taskId=:taskId)`,
                // 若先删 ScanRecord, 子查询变空 → 补全一条都删不掉, 且残留下次重扫若
                // 复用同 id 会把旧补全挂到新标签. 与 deleteTask/clearCompleted 同序。
                app.getDb().runInTransaction(() -> {
                    db.extraClaimDao().deleteByTask(taskId);
                    scansHolder[0] = db.scanRecordDao().deleteByTask(taskId);
                    // 重置 = 放弃本次盘点重来: 回滚编辑过的字段到划拨快照 + 清盘点时间;
                    // 保留任务与快照 (任务还在, 之后若删除任务仍能回滚).
                    db.assetDao().revertEditedFields(taskId);
                    db.assetDao().clearInventoryTimeForTask(taskId);
                });
            } catch (Exception e) {
                Log.e(TAG, "resetScanProgress db transaction failed", e);
                postClearResult(new ClearResult(e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage()));
                return;
            }
            // 清内存去重集 — 否则重扫同样标签被挡、不写库 (见方法 javadoc)。
            scannedEpcSet.clear();
            // 刷新统计 (scannedUniqueCount + 已盘/盘盈聚合) 为归零后的值。
            recomputeStats();
            postClearResult(new ClearResult(scansHolder[0]));
        });
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        // 标记 disposed — 高频 diskIO 路径入口检查, 防止 in-flight runnable
        // 在 VM 销毁后继续持有 this 引用并做完整个流程 (scannedEpcSet +
        // lambda closures 累积).
        disposed.set(true);
        // ViewModel 销毁时取消待执行的去抖任务，避免泄漏到下一个任务的 ViewModel
        mainHandler.removeCallbacks(recomputeRunnable);
        // 反注册 session observer: 否则 observeForever 仍持有 VM, 下次重建同
        // InventorySession 会拿到旧 VM 引用, 跨 ViewModel 实例相互锚定.
        app.getSession().currentTaskId().removeObserver(sessionObserver);
        app.getSession().scanRemovals().removeObserver(scanRemovalObserver);
    }
}

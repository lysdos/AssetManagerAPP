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
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.Observer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
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
    private final MutableLiveData<ClearResult> clearResult = new MutableLiveData<>();
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
    private int expectedCount = 0;
    /**
     * 外部 bind 时锁定的 taskId. {@link AtomicLong} 是为了 SessionObserver
     * 在 diskIO 线程 race-bind 设置与主线程重置时不被撕裂. 默认 -1 表示未绑定.
     */
    private final AtomicLong boundTaskId = new AtomicLong(-1L);
    /**
     * VM 是否已被 {@link #onCleared()} 反初始化 — 用 AtomicBoolean 标记.
     * 在高频扫描路径 {@link #onEpcScanned}, {@link #clearCurrentTask},
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

    public ScanViewModel(@NonNull Application application) {
        super(application);
        this.app = (InventoryApp) application;
        this.db = app.db;
        this.diskIO = app.executors.diskIO;
        // observeForever: ViewModel 生命周期长于 Fragment/Activity, 让 ScanViewModel
        // 持续监听 session.taskId 变化. 删除/暂停/切换任务路径下, 外部把 session 清空
        // 或改到其他 taskId, 此 observer 立刻感知并同步 vm 内部状态 (scannedEpcSet /
        // currentTaskId / rfid), 避免边界不一致写出孤儿 ScanRecord.
        app.session.currentTaskId().observeForever(sessionObserver);
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
                expectedCount = 0;
                scannedEpcSet.clear();
                app.session.clear();
                app.rfid.stopInventory();
                return;
            }
            expectedCount = t.expectedCount;
            recomputeStats();
        });
    }

    /**
     * Session 全局 taskId 变化的回调. 触发场景:
     * <ul>
     *   <li>TaskListViewModel.deleteTask 释放当前任务 → session.clear()</li>
     *   <li>TaskListViewModel.switchToTask / pauseTask / resumeTask 切到别的 taskId</li>
     *   <li>同 ViewModel 内 clearCurrentTask 主动清 session (跳过 — 此时 sessionObserver
     *       仍触发, 但 boundTaskId 已被 onSessionCleared 推到 -1L, 早 return)</li>
     * </ul>
     * 若新值与 vm 当前绑定值不一致, 视为外部把绑定任务换走, 重置 vm 内部状态
     * (不删 DB 行 — deleteTask 那侧已经在自身事务里完成), 防止 rfid-poll 继续 emit
     * 把 ScanRecord 写到已删 taskId.
     */
    private void onSessionTaskIdChanged(Long sessionId) {
        if (sessionId == null) sessionId = -1L;
        long bound = boundTaskId.get();
        if (bound == sessionId) {
            return; // 一致, 可能是 clearCurrentTask 自己清 -> 但 bound 已被清 -> see below
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
     * 仅清 vm 内部状态 + 停 RFID, 不删 DB 三表 (deleteTask 那侧已落地).
     * 与 {@link #clearCurrentTask()} 的差异: 它在事务中删三表行然后 reset,
     * 这里假定外部已完成 delete/迁移, 仅做 RFID 停止 + 内存状态复位.
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
            expectedCount = 0;
            currentTaskId.set(-1L);
            app.rfid.stopInventory();
            stats.postValue(new Stats(0, 0, 0, 0, 0, 0, 0,
                    java.util.Collections.emptyList(), 0L));
        });
    }

    /**
     * 处理一次 RFID 标签读数. 调用方: {@code ScanFragment.rfidCallback},
     * 由 SDK poll thread 直接 dispatch. 同一 EPC 重复到达时有两条互斥路径,
     * 行为不同:
     *
     * <ol>
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
     * <p>两条路径的语义差异在 UI 表面看不出来, 但维护者改写时容易踩坑:
     * 修改前请确认改动是否会破坏 latestScan LiveData 的"每次扫描一条"不变量.</p>
     */
    public void onEpcScanned(String rawCode, String tid, String rssi) {
        if (rawCode == null || rawCode.isEmpty()) {
            Log.w(TAG, "onEpcScanned called with null/empty code");
            return;
        }
        if (disposed.get()) {
            // VM 已 onCleared — 还在走 rfid poll callback 的话, 直接丢弃;
            // 否则会落到 taskId (这时已经被 bindToTask/clearCurrentTask 推到 -1L)
            // 写 taskId=-1L 孤儿 ScanRecord.
            return;
        }
        if (scannedEpcSet.contains(rawCode)) {
            // 路径 1: 内存 dedup 命中 — 发 latestScan 占位, 不写 DB.
            // 占位 record 不带 taskId / matchedAssetId, UI 须按纯 "已扫过" 展示.
            latestScan.postValue(makeUnmatchedRecord(rawCode, tid, rssi));
            return;
        }
        scannedEpcSet.add(rawCode);

        long taskId = currentTaskId.get();
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
            // 300ms 去抖合并: 一次 recomputeStats 同时刷新"已盘去重计数"
            // (SQL UPDATE+COUNT(DISTINCT)) 和"统计聚合 (matched/extra/lastScannedAt)",
            // 避免每个新 EPC 都触发全表子查询.
            db.inventoryTaskDao().recomputeScannedCount(taskId);
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
     * 清当前任务: 删 ScanRecord + Asset + InventoryTask 三表的当前任务行,
     * 并清掉 session.currentTaskId. 重置后必须重新导入才能扫.
     * 结果通过 {@link #clearResult()} 回调给 UI.
     *
     * <p>同步停 RFID: 删三表后若不停 {@code rfid.stopInventory}, rfid-poll 守护线程
     * 持续 emit onEpcScanned, 读到本方法新写的 {@code currentTaskId=-1L} →
     * findByEpc + insert 写入 {@code taskId=-1L} 孤儿 ScanRecord. 也不被任何
     * {@code WHERE taskId=} 视图汇总. 同步在事务后立即调 stopInventory,
     * 防止 daemon 继续 emit (严格说 join 500ms 内 in-flight 部分仍能写 —
     * 看 {@code app.db.scanRecordDao().insert} 的 UNIQUE 兜底 + IGNORE 三态完成,
     * 写到 taskId=-1L 的仍会污染 DB).</p>
     */
    public void clearCurrentTask() {
        if (disposed.get()) return;
        if (currentTaskId.get() == -1L) {
            clearResult.postValue(new ClearResult("当前无任务"));
            return;
        }
        long taskId = currentTaskId.get();
        // 立即执行的重算不需要走 300ms 去抖
        mainHandler.removeCallbacks(recomputeRunnable);
        diskIO.execute(() -> {
            if (disposed.get()) return;
            final int[] scansHolder = {0};
            final int[] assetsHolder = {0};
            try {
                // 先删 ScanRecord, 再删 Asset, 最后删 InventoryTask 行 (按外键依赖顺序)
                // — 整体包 runInTransaction, 任一步失败回滚, 不留"孤儿 Asset / ScanRecord"
                app.db.runInTransaction(() -> {
                    scansHolder[0] = db.scanRecordDao().deleteByTask(taskId);
                    assetsHolder[0] = db.assetDao().deleteByTask(taskId);
                    db.inventoryTaskDao().deleteById(taskId);
                });
            } catch (Exception e) {
                Log.e(TAG, "clearCurrentTask db transaction failed", e);
                clearResult.postValue(new ClearResult(e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage()));
                return;
            }
            scannedEpcSet.clear();
            expectedCount = 0;
            currentTaskId.set(-1L);
            // 清掉全局 session 的 currentTaskId, 让其他页面 (扫描/结果/导入) 也感知到
            // 主线程调用 setValue (同步) 而非 postValue (异步), 否则后续
            // getCurrentTaskId() 读到旧值会再次被错认为"还有任务".
            app.session.clear();
            // 停 RFID: rfid-poll daemon 已挂的 in-flight emit 在 join(500) 内
            // 仍可能跑出 onEpcScanned; clearedEpcSet.clear() + currentTaskId=-1L
            // 在 lambda 末尾已生效, in-flight 路径会走 diskIO.findByEpc(-1L, ...) +
            // insert 到 taskId=-1L. 这些是已知残留 — 但比"持续 emit 一直写"短 1-2s.
            app.rfid.stopInventory();
            // 显式 postValue 一份空 stats, 让 UI 立即归零 (不依赖 onResume/bindToTask 重算)
            stats.postValue(new Stats(0, 0, 0, 0, 0, 0, 0,
                    java.util.Collections.emptyList(), 0L));
            clearResult.postValue(new ClearResult(assetsHolder[0], scansHolder[0]));
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
        app.session.currentTaskId().removeObserver(sessionObserver);
    }
}

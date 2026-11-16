package com.huaxia.inventory.service;

import androidx.annotation.Nullable;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ExtraClaim;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.data.entity.TaskResult;
import com.huaxia.inventory.session.InventorySession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 盘点任务的写操作 —— 事务体的唯一实现。
 *
 * <p>App 端 {@code TaskListViewModel} 与 Web 端 {@code web/api/TaskApi} 共用本类，
 * 避免「删除任务」这类有严格顺序语义的操作被复制成两份后漂移。
 *
 * <p>所有方法<b>同步执行、直接抛异常</b>；线程调度与异常转回调由调用方负责。
 * 调用方必须保证不在主线程调用（Room 同步查询禁止主线程）。
 */
public class TaskService {

    private final AppDatabase db;
    private final InventorySession session;

    public TaskService(AppDatabase db, InventorySession session) {
        this.db = db;
        this.session = session;
    }

    /**
     * 创建任务并把库存池资产划拨过去。
     * <p>单事务: 插入任务 + 划拨 + 修正 expectedCount —— 任一步失败整体回滚，
     * 防止进程被杀留下「任务壳 + 0 划拨」孤儿。
     *
     * @param dept null / 空串 = 不限部门（整个库存池划过去）
     * @return 新任务 id
     */
    public long createTask(String name, String dept, @Nullable String remark) {
        String effectiveDept = (dept == null || dept.isEmpty()) ? null : dept;
        InventoryTask t = new InventoryTask();
        t.sourceFileName = name;
        t.importedAt = System.currentTimeMillis();
        t.status = "IN_PROGRESS";
        t.dept = effectiveDept;
        t.remark = (remark == null || remark.isEmpty()) ? null : remark;
        t.expectedCount = countPoolAssetsForDept(effectiveDept);

        final long[] idHolder = {0L};
        final int[] assignedHolder = {0};
        db.runInTransaction(() -> {
            idHolder[0] = db.inventoryTaskDao().insert(t);
            assignedHolder[0] = db.assetDao().assignPoolToTask(idHolder[0], effectiveDept);
        });
        // 不在事务内 update 是有意的: 让划拨先 commit，再独立 UPDATE 让
        // expectedCount 与实际划拨一致。t.id 仍是 0，必须重新 findById 拿 fresh row。
        if (assignedHolder[0] != t.expectedCount) {
            InventoryTask fresh = db.inventoryTaskDao().findById(idHolder[0]);
            if (fresh != null) {
                fresh.expectedCount = assignedHolder[0];
                db.inventoryTaskDao().update(fresh);
            }
        }
        return idHolder[0];
    }

    /** 库存池(taskId=0)中资产数；dept 为空则全库存池。 */
    public int countPoolAssetsForDept(@Nullable String dept) {
        if (dept == null || dept.isEmpty()) return db.assetDao().countPool();
        return db.assetDao().countPoolByDept(dept);
    }

    /** 库存池中所有不重复的领用部门（建任务下拉选项）。 */
    public List<String> distinctPoolDepts() {
        return db.assetDao().distinctDeptPool();
    }

    /**
     * 删除任务 = 释放资产回库存池 + 清扫描记录 + 删任务壳 + 清 session。
     * <p>未提交(进行中/暂停)先回滚编辑过的字段；已提交(COMPLETED)保留编辑值。
     * 两种情况都清盘点时间并丢弃快照。
     *
     * @return 退回库存池的资产数
     */
    public int deleteTask(long taskId) {
        final int[] releasedHolder = {0};
        db.runInTransaction(() -> {
            InventoryTask t = db.inventoryTaskDao().findById(taskId);
            boolean completed = t != null && "COMPLETED".equals(t.status);
            if (completed) {
                // 已完成: 资产在完成时已回池, 这里只需丢弃历史快照
                db.taskResultDao().deleteByTask(taskId);
            } else {
                db.assetDao().revertEditedFields(taskId);
                db.assetDao().clearInventoryTimeForTask(taskId);
                // 丢弃快照必须在 release 之前: release 把 taskId 归零后按 taskId 过滤会匹配 0 行。
                db.assetDao().clearSnapshotForTask(taskId);
                releasedHolder[0] = db.assetDao().releaseTaskToPool(taskId);
            }
            db.extraClaimDao().deleteByTask(taskId);
            db.scanRecordDao().deleteByTask(taskId);
            db.inventoryTaskDao().deleteById(taskId);
        });
        Long cur = session.getCurrentTaskId();
        if (cur != null && cur == taskId) {
            session.clear();
        }
        return releasedHolder[0];
    }

    /**
     * 清除已完成: 删冻结快照 + 删任务壳。
     *
     * <p>资产在完成任务时就已经回池了, 这里不再 release。历史任务(v1 时代完成的)
     * 可能还留着扫描记录, 一并清掉。</p>
     */
    public void clearCompleted() {
        db.runInTransaction(() -> {
            // 批量删除，不再在循环里逐任务调 DAO（CLAUDE.md：循环内禁止调用数据库）。
            // SQLite 的 IN 子句按 500 一批，避免超过绑定上限。
            List<Long> ids = db.inventoryTaskDao().findAllCompletedIds();
            for (int i = 0; i < ids.size(); i += SNAPSHOT_BATCH) {
                List<Long> chunk = ids.subList(i, Math.min(i + SNAPSHOT_BATCH, ids.size()));
                db.taskResultDao().deleteByTasks(chunk);
                db.extraClaimDao().deleteByTasks(chunk);
                db.scanRecordDao().deleteByTasks(chunk);
            }
            db.inventoryTaskDao().deleteAllCompleted();
        });
    }

    /** 切换当前任务；任务不存在返回 false。 */
    public boolean switchToTask(long taskId) {
        InventoryTask t = db.inventoryTaskDao().findById(taskId);
        if (t != null) {
            session.setCurrentTaskId(taskId);
        }
        return t != null;
    }

    /** 暂停: IN_PROGRESS -> PAUSED；命中当前任务时清 session。 */
    public void pauseTask(long taskId) {
        InventoryTask t = db.inventoryTaskDao().findById(taskId);
        if (t != null && "IN_PROGRESS".equals(t.status)) {
            t.status = "PAUSED";
            db.inventoryTaskDao().update(t);
            Long cur = session.getCurrentTaskId();
            if (cur != null && cur == taskId) {
                session.clear();
            }
        }
    }

    /**
     * 完成任务 = 提交 + 释放资产。
     *
     * <p>单事务六步，顺序不可调换：</p>
     * <ol>
     *   <li>把该次盘点结果冻结进 {@code TaskResult}（必须先于任何清理）</li>
     *   <li>资产退回库存池</li>
     *   <li>清盘点时间（资产离开任务，时间即失效）</li>
     *   <li>丢弃划拨时的快照列（编辑值已成主数据，且已进 TaskResult）</li>
     *   <li>删扫描/补全记录（历史已在 TaskResult，留着只会让库膨胀）</li>
     *   <li>状态翻 COMPLETED</li>
     * </ol>
     *
     * <p>幂等：已是 COMPLETED 直接返回，不重复写快照。</p>
     */
    public void completeTask(long taskId) {
        db.runInTransaction(() -> {
            InventoryTask t = db.inventoryTaskDao().findById(taskId);
            if (t == null || "COMPLETED".equals(t.status)) return;

            // 写快照前先把"已盘资产"的盘点时间补上。
            // inventory_time 平时只在打开结果页时才回填（ResultViewModel.bindToTask）；
            // 若用户扫完直接点完成、没进过结果页，快照会拿到 null —— 而完成后扫描记录
            // 已删、资产已回池，再也没机会补，导出的「盘点时间」列就永久空白。
            // 该方法是幂等的（只填 inventory_time IS NULL 的行）。
            db.assetDao().backfillFoundInventoryTime(taskId);

            insertSnapshot(taskId);

            db.assetDao().clearInventoryTimeForTask(taskId);
            db.assetDao().clearSnapshotForTask(taskId);
            db.assetDao().releaseTaskToPool(taskId);

            db.extraClaimDao().deleteByTask(taskId);
            db.scanRecordDao().deleteByTask(taskId);

            t.status = "COMPLETED";
            db.inventoryTaskDao().update(t);
        });
        Long cur = session.getCurrentTaskId();
        if (cur != null && cur == taskId) {
            session.clear();
        }
    }

    /** 快照分批写入的页大小 —— 与导出流式分页一致，避免整任务物化。 */
    private static final int SNAPSHOT_BATCH = 500;

    /**
     * 把任务当前结果分批冻结成快照。调用方必须在事务内、且在清理之前调用。
     *
     * <p><b>必须分批</b>：30w 资产的盘点任务若一次性 {@code findAllByTask} + 一次性
     * {@code insertAll}，光 Asset 行（含 rawRowJson ~0.5-1KB）就要 150-300MB，
     * PDA 上直接 OOM。导出侧早就为同样的量级做了 500 行分页，这里对齐。</p>
     */
    private void insertSnapshot(long taskId) {
        Set<Long> foundIds = new HashSet<>(db.assetDao().findFoundIds(taskId));

        int offset = 0;
        while (true) {
            List<Asset> page = db.assetDao().pageAll(taskId, SNAPSHOT_BATCH, offset);
            if (page.isEmpty()) break;
            List<TaskResult> rows = new ArrayList<>(page.size());
            for (Asset a : page) {
                TaskResult r = new TaskResult();
                r.taskId = taskId;
                r.rowIndex = a.excelRowIndex;
                r.assetId = a.id;
                r.epc = a.epc;
                r.assetType = a.assetType;
                r.assetModel = a.assetModel;
                r.serialNumber = a.serialNumber;
                r.custodian = a.custodian;
                r.dept = a.dept;
                r.status = foundIds.contains(a.id) ? "FOUND" : "MISSING";
                r.inventoryTime = a.inventoryTime;
                r.rawRowJson = a.rawRowJson;
                rows.add(r);
            }
            db.taskResultDao().insertAll(rows);
            offset += page.size();
        }

        offset = 0;
        while (true) {
            List<ScanRecord> extras = db.scanRecordDao().pageExtra(taskId, SNAPSHOT_BATCH, offset);
            if (extras.isEmpty()) break;
            List<Long> ids = new ArrayList<>(extras.size());
            for (ScanRecord s : extras) ids.add(s.id);
            Map<Long, ExtraClaim> claims = new HashMap<>();
            for (ExtraClaim c : db.extraClaimDao().findByScanRecordIds(ids)) {
                claims.put(c.scanRecordId, c);
            }
            List<TaskResult> rows = new ArrayList<>(extras.size());
            for (ScanRecord s : extras) {
                ExtraClaim c = claims.get(s.id);
                TaskResult r = new TaskResult();
                r.taskId = taskId;
                r.rowIndex = 0;
                r.assetId = null;
                r.epc = c != null && c.epc != null ? c.epc : s.epc;
                r.assetType = c == null ? null : c.assetType;
                r.assetModel = c == null ? null : c.assetModel;
                r.serialNumber = c == null ? null : c.serialNumber;
                r.custodian = c == null ? null : c.custodian;
                r.dept = c == null ? null : c.dept;
                r.status = "EXTRA";
                r.scannedAt = s.scannedAt;
                r.rssi = s.rssi;
                r.tid = s.tid;
                rows.add(r);
            }
            db.taskResultDao().insertAll(rows);
            offset += extras.size();
        }
    }

    /** 恢复: PAUSED -> IN_PROGRESS，并设为当前任务让 PDA 接着扫。 */
    public void resumeTask(long taskId) {
        InventoryTask t = db.inventoryTaskDao().findById(taskId);
        if (t != null && "PAUSED".equals(t.status)) {
            t.status = "IN_PROGRESS";
            db.inventoryTaskDao().update(t);
            session.setCurrentTaskId(taskId);
        }
    }
}

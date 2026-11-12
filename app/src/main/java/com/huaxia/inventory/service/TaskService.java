package com.huaxia.inventory.service;

import androidx.annotation.Nullable;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.session.InventorySession;

import java.util.List;

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
            if (!completed) {
                db.assetDao().revertEditedFields(taskId);
            }
            db.assetDao().clearInventoryTimeForTask(taskId);
            // 丢弃快照必须在 release 之前: release 把 taskId 归零后按 taskId 过滤会匹配 0 行。
            db.assetDao().clearSnapshotForTask(taskId);
            releasedHolder[0] = db.assetDao().releaseTaskToPool(taskId);
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

    /** 清除已完成: 资产退回库存池(保留提交值) + 清盘点时间 + 清扫描/补全 + 删任务壳。 */
    public void clearCompleted() {
        db.runInTransaction(() -> {
            List<InventoryTask> done = db.inventoryTaskDao().findAllCompleted();
            for (InventoryTask t : done) {
                db.extraClaimDao().deleteByTask(t.id);
                db.scanRecordDao().deleteByTask(t.id);
                db.assetDao().clearInventoryTimeForTask(t.id);
                db.assetDao().releaseTaskToPool(t.id);
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

    /** 完成 = 提交: 翻 COMPLETED + 丢弃快照（编辑值从此成为主数据）。 */
    public void completeTask(long taskId) {
        db.runInTransaction(() -> {
            InventoryTask t = db.inventoryTaskDao().findById(taskId);
            if (t != null && !"COMPLETED".equals(t.status)) {
                t.status = "COMPLETED";
                db.inventoryTaskDao().update(t);
                db.assetDao().clearSnapshotForTask(taskId);
            }
        });
        Long cur = session.getCurrentTaskId();
        if (cur != null && cur == taskId) {
            session.clear();
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

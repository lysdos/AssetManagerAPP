package com.huaxia.inventory.ui.task;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.data.entity.InventoryTask;
import java.util.List;
import java.util.concurrent.Executor;

public class TaskListViewModel extends AndroidViewModel {

    private final InventoryApp app;
    private final Executor diskIO;
    private final LiveData<List<InventoryTask>> tasks;

    public TaskListViewModel(@NonNull Application application) {
        super(application);
        this.app = (InventoryApp) application;
        this.diskIO = app.executors.diskIO;
        this.tasks = app.db.inventoryTaskDao().findAllLive();
    }

    public LiveData<List<InventoryTask>> tasks() { return tasks; }

    public void createTask(String name, String dept, String remark, CreateCallback cb) {
        diskIO.execute(() -> {
            String effectiveDept = (dept == null || dept.isEmpty()) ? null : dept;
            InventoryTask t = new InventoryTask();
            t.sourceFileName = name;
            t.importedAt = System.currentTimeMillis();
            t.status = "IN_PROGRESS";
            t.dept = effectiveDept;
            t.expectedCount = countPoolAssetsForDept(effectiveDept);
            // 单事务: 插入任务 + 划拨库存池资产 + 修正 expectedCount —
            // 任一步失败整体回滚, 防止进程被杀留下"任务壳 + 0 划拨"孤儿.
            // 与 deleteTask / clearCompleted 风格一致.
            final long[] idHolder = {0L};
            app.db.runInTransaction(() -> {
                idHolder[0] = app.db.inventoryTaskDao().insert(t);
                int assigned = app.db.assetDao().assignPoolToTask(idHolder[0], effectiveDept);
                if (assigned != t.expectedCount) {
                    t.expectedCount = assigned;
                    app.db.inventoryTaskDao().update(t);
                }
            });
            if (cb != null) cb.onCreated(idHolder[0]);
        });
    }

    /**
     * 统计库存池(taskId=0)中资产数：dept 为空则全库存池，否则按部门过滤。
     * 创建任务前先预览，匹配实际可划拨的资产数。
     */
    public int countPoolAssetsForDept(String dept) {
        if (dept == null || dept.isEmpty()) return app.db.assetDao().countPool();
        // 库存池 + 部门过滤 — 用一个专门的 SQL 走索引, 不再用全表扫描.
        return app.db.assetDao().countPoolByDept(dept);
    }

    /**
     * 旧版 API — 保留兼容 (countAssetsForDept 等价于 countPoolAssetsForDept,
     * 因为 import 不再写非 pool 资产, 全表 = 库存池).
     * @deprecated use countPoolAssetsForDept — 语义更清晰
     */
    @Deprecated
    public int countAssetsForDept(String dept) {
        return countPoolAssetsForDept(dept);
    }

    public void previewAssetCount(String dept, PreviewCallback cb) {
        diskIO.execute(() -> cb.onCount(countPoolAssetsForDept(dept)));
    }

    /**
     * 库存池 (taskId=0) 中所有不重复的领用部门 — Spinner 选项来源。
     * 不查整张 Asset 表 (可能含有历史任务的资产), 只看"待划拨"的库存池.
     */
    public void loadDistinctDeptAll(LoadDeptCallback cb) {
        diskIO.execute(() -> cb.onLoaded(app.db.assetDao().distinctDeptPool()));
    }

    /**
     * 删除任务 = 释放资产回库存池 + 清扫描记录 + 删任务壳 + 清 session.
     * 业务口径: ⋮ → "删除任务" 是"放弃这个任务"的唯一入口, 资产不丢 (留在库存池
     * 供下一个任务分配), 扫描/补全记录因为 taskId 上下文失效一并清掉.
     * <p>单事务: 任一步失败整体回滚, 无孤儿 ScanRecord.
     * <p>若是当前正在盘点的任务 (session.currentTaskId == taskId), 清 session
     * 让扫描页归零, 否则会指向已删任务壳导致 UI 空指针.
     */
    public void deleteTask(long taskId, DeleteCallback cb) {
        diskIO.execute(() -> {
            final int[] releasedHolder = {0};
            app.db.runInTransaction(() -> {
                releasedHolder[0] = app.db.assetDao().releaseTaskToPool(taskId);
                app.db.extraClaimDao().deleteByTask(taskId);
                app.db.scanRecordDao().deleteByTask(taskId);
                app.db.inventoryTaskDao().deleteById(taskId);
            });
            Long cur = app.session.getCurrentTaskId();
            if (cur != null && cur == taskId) {
                app.session.clear();
            }
            if (cb != null) cb.onDeleted(taskId, releasedHolder[0]);
        });
    }

    public void clearCompleted() {
        diskIO.execute(() -> {
            // 已完成任务关联的资产 + 扫描记录一并清除, 不再回流库存池
            // (任务已完成 → 资产/扫描记录的"任务上下文"已无意义)
            app.db.runInTransaction(() -> {
                List<InventoryTask> done = app.db.inventoryTaskDao().findAllCompleted();
                for (InventoryTask t : done) {
                    app.db.extraClaimDao().deleteByTask(t.id);
                    app.db.scanRecordDao().deleteByTask(t.id);
                    app.db.assetDao().deleteByTask(t.id);
                }
                app.db.inventoryTaskDao().deleteAllCompleted();
            });
        });
    }

    public void switchToTask(long taskId, SwitchCallback cb) {
        diskIO.execute(() -> {
            InventoryTask t = app.db.inventoryTaskDao().findById(taskId);
            if (t != null) {
                app.session.setCurrentTaskId(taskId);
            }
            if (cb != null) cb.onSwitched(taskId, t != null);
        });
    }

    /**
     * 暂停任务: status -> PAUSED。如果当前正在盘点这个任务, 同时清空 currentTaskId。
     * Runnable cb 在 diskIO 线程触发, UI 调用方需自行 runOnUiThread 切回主线程。
     */
    public void pauseTask(long taskId, Runnable cb) {
        diskIO.execute(() -> {
            InventoryTask t = app.db.inventoryTaskDao().findById(taskId);
            if (t != null && "IN_PROGRESS".equals(t.status)) {
                t.status = "PAUSED";
                app.db.inventoryTaskDao().update(t);
                Long cur = app.session.getCurrentTaskId();
                if (cur != null && cur == taskId) {
                    app.session.clear();
                }
            }
            if (cb != null) cb.run();
        });
    }

    /**
     * 恢复任务: status -> IN_PROGRESS (并设为 currentTaskId, 让 scan 接着扫)。
     */
    public void resumeTask(long taskId, Runnable cb) {
        diskIO.execute(() -> {
            InventoryTask t = app.db.inventoryTaskDao().findById(taskId);
            if (t != null && "PAUSED".equals(t.status)) {
                t.status = "IN_PROGRESS";
                app.db.inventoryTaskDao().update(t);
                app.session.setCurrentTaskId(taskId);
            }
            if (cb != null) cb.run();
        });
    }

    public interface CreateCallback { void onCreated(long taskId); }
    public interface PreviewCallback { void onCount(int count); }
    public interface LoadDeptCallback { void onLoaded(List<String> depts); }
    public interface SwitchCallback { void onSwitched(long taskId, boolean ok); }
    public interface DeleteCallback { void onDeleted(long taskId, int releasedAssetCount); }
}
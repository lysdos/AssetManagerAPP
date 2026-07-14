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
            long id = app.db.inventoryTaskDao().insert(t);
            // 把库存池(taskId=0)中匹配部门的资产划拨到新任务.
            // 不限部门 → 把整个库存池划过去; 否则只划匹配部门的.
            int assigned = app.db.assetDao().assignPoolToTask(id, effectiveDept);
            // 若划拨条数与预计不符(可能是历史数据导致), 修正 expectedCount 为实际数.
            if (assigned != t.expectedCount) {
                t.expectedCount = assigned;
                app.db.inventoryTaskDao().update(t);
            }
            if (cb != null) cb.onCreated(id);
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

    public void deleteTask(long taskId) {
        diskIO.execute(() -> {
            // 整体进单事务: ExtraClaim → ScanRecord → Asset → InventoryTask 任一失败都回滚,
            // 避免孤儿 ScanRecord (matchedAssetId 指向已删 Asset) 残留.
            app.db.runInTransaction(() -> {
                app.db.extraClaimDao().deleteByTask(taskId);
                app.db.scanRecordDao().deleteByTask(taskId);
                app.db.assetDao().deleteByTask(taskId);
                app.db.inventoryTaskDao().deleteById(taskId);
            });
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

    /**
     * 「释放资产回库存池」：把任务下资产释放回库存池 (taskId=0), 清扫描记录 + 补全记录,
     * <b>同时删除 InventoryTask 行</b> — 任务壳子本身没意义了 (资产已交还, 没有
     * "任务上下文"可保留), 一并删掉避免「释放→恢复→扫到空任务全盘盈」的诡异链路.
     * <p>与 {@link #deleteTask} 的区别: 释放保留资产在 pool (供新任务分配), 删除连带删资产.
     * 仅对 PAUSED / COMPLETED 任务有意义 — IN_PROGRESS 提示先暂停.
     */
    public void releaseTaskAssetsToPool(long taskId, ReleaseCallback cb) {
        diskIO.execute(() -> {
            final int[] releasedHolder = {0};
            app.db.runInTransaction(() -> {
                releasedHolder[0] = app.db.assetDao().releaseTaskToPool(taskId);
                app.db.extraClaimDao().deleteByTask(taskId);
                app.db.scanRecordDao().deleteByTask(taskId);
                app.db.inventoryTaskDao().deleteById(taskId);
            });
            // 释放后任务壳子没了, session 不能继续指向已删 taskId —
            // 否则扫描页还显示空数据, UI 端找不到任务卡也走不到正常路径.
            Long cur = app.session.getCurrentTaskId();
            if (cur != null && cur == taskId) {
                app.session.clear();
            }
            if (cb != null) cb.onReleased(taskId, releasedHolder[0]);
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
    public interface ReleaseCallback { void onReleased(long taskId, int assetCount); }
}
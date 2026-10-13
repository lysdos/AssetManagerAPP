package com.huaxia.inventory.ui.task;

import android.app.Application;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.data.entity.InventoryTask;
import java.util.List;
import java.util.concurrent.Executor;

public class TaskListViewModel extends AndroidViewModel {

    private static final String TAG = "TaskListVM";

    private final InventoryApp app;
    private final Executor diskIO;
    private final LiveData<List<InventoryTask>> tasks;

    public TaskListViewModel(@NonNull Application application) {
        super(application);
        this.app = (InventoryApp) application;
        this.diskIO = app.getExecutors().diskIO;
        this.tasks = app.getDb().inventoryTaskDao().findAllLive();
    }

    public LiveData<List<InventoryTask>> tasks() { return tasks; }

    public void createTask(String name, String dept, String remark, CreateCallback cb) {
        diskIO.execute(() -> {
            try {
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
                //
                // ⚠ t.id 不能用作 update 主键: Room @Insert 返回 autoincrement rowid
                // 但不会回写到 POJO 字段 — 若调 app.getDb().inventoryTaskDao().update(t),
                // WHERE id=0 命中 0 行, expectedCount 修正丢失. 必须从 holder id 重新
                // findById 拿 fresh row 再 update.
                final long[] idHolder = {0L};
                final int[] assignedHolder = {0};
                app.getDb().runInTransaction(() -> {
                    idHolder[0] = app.getDb().inventoryTaskDao().insert(t);
                    assignedHolder[0] = app.getDb().assetDao().assignPoolToTask(
                            idHolder[0], effectiveDept);
                });
                if (assignedHolder[0] != t.expectedCount) {
                    // 不在事务内 update 是有意的: 让 assignPoolToTask 的写先 commit,
                    // 再独立 UPDATE 让 task.expectedCount 与实际划拨一致; 万一 update
                    // 失败也不影响划拨已落地的事实. 用 fresh row 而非 t —
                    // t.id 还停在 0, update WHERE id=0 命中 0 行.
                    InventoryTask fresh = app.getDb().inventoryTaskDao().findById(idHolder[0]);
                    if (fresh != null) {
                        fresh.expectedCount = assignedHolder[0];
                        app.getDb().inventoryTaskDao().update(fresh);
                    }
                }
                if (cb != null) cb.onCreated(idHolder[0]);
            } catch (Exception e) {
                // P2#21: 异常路径 callback 永不触发, UI 黑屏.
                // catch 兜底 + onError 通道让 dialog 能给用户提示.
                Log.e(TAG, "createTask failed", e);
                if (cb != null) cb.onError(e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
    }

    /**
     * 统计库存池(taskId=0)中资产数：dept 为空则全库存池，否则按部门过滤。
     * 创建任务前先预览，匹配实际可划拨的资产数。
     */
    public int countPoolAssetsForDept(String dept) {
        if (dept == null || dept.isEmpty()) return app.getDb().assetDao().countPool();
        // 库存池 + 部门过滤 — 用一个专门的 SQL 走索引, 不再用全表扫描.
        return app.getDb().assetDao().countPoolByDept(dept);
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
        diskIO.execute(() -> cb.onLoaded(app.getDb().assetDao().distinctDeptPool()));
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
            try {
                final int[] releasedHolder = {0};
                app.getDb().runInTransaction(() -> {
                    releasedHolder[0] = app.getDb().assetDao().releaseTaskToPool(taskId);
                    app.getDb().extraClaimDao().deleteByTask(taskId);
                    app.getDb().scanRecordDao().deleteByTask(taskId);
                    app.getDb().inventoryTaskDao().deleteById(taskId);
                });
                Long cur = app.getSession().getCurrentTaskId();
                if (cur != null && cur == taskId) {
                    app.getSession().clear();
                }
                if (cb != null) cb.onDeleted(taskId, releasedHolder[0]);
            } catch (Exception e) {
                // P2#21: 异常路径 UI 黑屏.
                Log.e(TAG, "deleteTask failed", e);
                if (cb != null) cb.onError(taskId, e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
    }

    public void clearCompleted() {
        diskIO.execute(() -> {
            try {
                // 已完成任务关联的资产 + 扫描记录一并清除, 不再回流库存池
                // (任务已完成 → 资产/扫描记录的"任务上下文"已无意义)
                app.getDb().runInTransaction(() -> {
                    List<InventoryTask> done = app.getDb().inventoryTaskDao().findAllCompleted();
                    for (InventoryTask t : done) {
                        app.getDb().extraClaimDao().deleteByTask(t.id);
                        app.getDb().scanRecordDao().deleteByTask(t.id);
                        app.getDb().assetDao().deleteByTask(t.id);
                    }
                    app.getDb().inventoryTaskDao().deleteAllCompleted();
                });
            } catch (Exception e) {
                // P2#21: clearCompleted 没有 callback, 异常时只能 Log.e —
                // 若需要 UI 反馈 (例如提示用户重试) 应加 ClearCallback 接口.
                Log.e(TAG, "clearCompleted failed", e);
            }
        });
    }

    public void switchToTask(long taskId, SwitchCallback cb) {
        diskIO.execute(() -> {
            try {
                InventoryTask t = app.getDb().inventoryTaskDao().findById(taskId);
                if (t != null) {
                    app.getSession().setCurrentTaskId(taskId);
                }
                if (cb != null) cb.onSwitched(taskId, t != null);
            } catch (Exception e) {
                Log.e(TAG, "switchToTask failed", e);
                if (cb != null) cb.onError(e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
    }

    /**
     * 暂停任务: status -> PAUSED。如果当前正在盘点这个任务, 同时清空 currentTaskId。
     * Runnable cb 在 diskIO 线程触发, UI 调用方需自行 runOnUiThread 切回主线程。
     */
    public void pauseTask(long taskId, Runnable cb) {
        diskIO.execute(() -> {
            try {
                InventoryTask t = app.getDb().inventoryTaskDao().findById(taskId);
                if (t != null && "IN_PROGRESS".equals(t.status)) {
                    t.status = "PAUSED";
                    app.getDb().inventoryTaskDao().update(t);
                    Long cur = app.getSession().getCurrentTaskId();
                    if (cur != null && cur == taskId) {
                        app.getSession().clear();
                    }
                }
                if (cb != null) cb.run();
            } catch (Exception e) {
                Log.e(TAG, "pauseTask failed", e);
                if (cb != null) cb.run();
            }
        });
    }

    /**
     * 完成任务: status -> {@code COMPLETED} (不可逆)。若该任务是当前任务, 同时清空
     * currentTaskId, 让扫描页复位并停止 RFID。完成后的任务才能被"清除已完成"清理
     * (在此之前没有入口把任务置为 COMPLETED)。
     * Runnable cb 在 diskIO 线程触发, UI 调用方需自行 runOnUiThread 切回主线程。
     */
    public void completeTask(long taskId, Runnable cb) {
        diskIO.execute(() -> {
            try {
                InventoryTask t = app.getDb().inventoryTaskDao().findById(taskId);
                if (t != null && !"COMPLETED".equals(t.status)) {
                    t.status = "COMPLETED";
                    app.getDb().inventoryTaskDao().update(t);
                    Long cur = app.getSession().getCurrentTaskId();
                    if (cur != null && cur == taskId) {
                        app.getSession().clear();
                    }
                }
                if (cb != null) cb.run();
            } catch (Exception e) {
                Log.e(TAG, "completeTask failed", e);
                if (cb != null) cb.run();
            }
        });
    }

    /**
     * 恢复任务: status -> IN_PROGRESS (并设为 currentTaskId, 让 scan 接着扫)。
     */
    public void resumeTask(long taskId, Runnable cb) {
        diskIO.execute(() -> {
            try {
                InventoryTask t = app.getDb().inventoryTaskDao().findById(taskId);
                if (t != null && "PAUSED".equals(t.status)) {
                    t.status = "IN_PROGRESS";
                    app.getDb().inventoryTaskDao().update(t);
                    app.getSession().setCurrentTaskId(taskId);
                }
                if (cb != null) cb.run();
            } catch (Exception e) {
                Log.e(TAG, "resumeTask failed", e);
                if (cb != null) cb.run();
            }
        });
    }

    /** 任务入口通用回调 — P2#21 加 onError 通道, 让异常路径不再沉默. */
    public interface CreateCallback {
        void onCreated(long taskId);
        default void onError(String reason) {}
    }
    public interface PreviewCallback { void onCount(int count); }
    public interface LoadDeptCallback { void onLoaded(List<String> depts); }
    public interface SwitchCallback {
        void onSwitched(long taskId, boolean ok);
        default void onError(String reason) {}
    }
    public interface DeleteCallback {
        void onDeleted(long taskId, int releasedAssetCount);
        default void onError(long taskId, String reason) {}
    }
}
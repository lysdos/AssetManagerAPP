package com.huaxia.inventory.ui.task;

import android.app.Application;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.service.TaskService;
import java.util.List;
import java.util.concurrent.Executor;

public class TaskListViewModel extends AndroidViewModel {

    private static final String TAG = "TaskListVM";

    private final InventoryApp app;
    private final Executor diskIO;
    private final TaskService taskService;
    private final LiveData<List<InventoryTask>> tasks;

    public TaskListViewModel(@NonNull Application application) {
        super(application);
        this.app = (InventoryApp) application;
        this.diskIO = app.getExecutors().diskIO;
        this.taskService = new TaskService(app.getDb(), app.getSession());
        this.tasks = app.getDb().inventoryTaskDao().findAllLive();
    }

    public LiveData<List<InventoryTask>> tasks() { return tasks; }

    public void createTask(String name, String dept, String remark, CreateCallback cb) {
        diskIO.execute(() -> {
            try {
                long taskId = taskService.createTask(name, dept, remark);
                if (cb != null) cb.onCreated(taskId);
            } catch (Exception e) {
                // 异常路径必须走 onError, 否则 dialog 黑屏无提示.
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
        return taskService.countPoolAssetsForDept(dept);
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
        diskIO.execute(() -> cb.onLoaded(taskService.distinctPoolDepts()));
    }

    /**
     * 删除任务 = 释放资产回库存池 + 清扫描记录 + 删任务壳 + 清 session.
     * 业务口径: ⋮ → "删除任务" 是"放弃这个任务"的唯一入口, 资产不丢 (留在库存池
     * 供下一个任务分配), 扫描/补全记录因为 taskId 上下文失效一并清掉.
     * <p>回滚口径: 若任务是<b>未提交</b>(进行中/暂停), 先把编辑过的业务字段还原成
     * 划拨时的快照 (revertEditedFields); 已提交(COMPLETED)则保留编辑值 (等同"清除已完成").
     * 两种情况都清盘点时间 (资产离开任务回池, 时间即失效) 并丢弃快照。
     * <p>单事务: 任一步失败整体回滚, 无孤儿 ScanRecord.
     * <p>若是当前正在盘点的任务 (session.currentTaskId == taskId), 清 session
     * 让扫描页归零, 否则会指向已删任务壳导致 UI 空指针.
     */
    public void deleteTask(long taskId, DeleteCallback cb) {
        diskIO.execute(() -> {
            try {
                int released = taskService.deleteTask(taskId);
                if (cb != null) cb.onDeleted(taskId, released);
            } catch (Exception e) {
                Log.e(TAG, "deleteTask failed", e);
                if (cb != null) cb.onError(taskId, e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
    }

    public void clearCompleted() {
        diskIO.execute(() -> {
            try {
                taskService.clearCompleted();
            } catch (Exception e) {
                // clearCompleted 没有 callback, 异常时只能 Log.e —
                // 若需要 UI 反馈 (例如提示用户重试) 应加 ClearCallback 接口.
                Log.e(TAG, "clearCompleted failed", e);
            }
        });
    }

    public void switchToTask(long taskId, SwitchCallback cb) {
        diskIO.execute(() -> {
            try {
                boolean ok = taskService.switchToTask(taskId);
                if (cb != null) cb.onSwitched(taskId, ok);
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
                taskService.pauseTask(taskId);
            } catch (Exception e) {
                Log.e(TAG, "pauseTask failed", e);
            } finally {
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
                taskService.completeTask(taskId);
            } catch (Exception e) {
                Log.e(TAG, "completeTask failed", e);
            } finally {
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
                taskService.resumeTask(taskId);
            } catch (Exception e) {
                Log.e(TAG, "resumeTask failed", e);
            } finally {
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
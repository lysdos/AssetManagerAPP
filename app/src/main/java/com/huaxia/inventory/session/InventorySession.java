package com.huaxia.inventory.session;

import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * 进程级共享当前活动任务的 taskId.
 *
 * <p><b>调用线程与可见性</b>:</p>
 * <ul>
 *   <li><b>主线程</b>: {@link #setCurrentTaskId(long)} 与 {@link #clear()}
 *       走 {@link MutableLiveData#setValue} (同步), 紧随其后的
 *       {@link #getCurrentTaskId()} 立即看到新值. 这是大多数
 *       触发场景 (用户点 "开始盘点" / "暂停" / "删除" 等) 的路径.</li>
 *   <li><b>后台线程</b> (例如 {@code diskIO.execute}): 走
 *       {@link MutableLiveData#postValue} (异步), 必须经一个 Looper 周期
 *       才能被 {@link #getCurrentTaskId()} 看到. <b>不要</b> 在后台线程
 *       调用方紧跟读 {@link #getCurrentTaskId()} 并依此决策 —
 *       需要"立即看到"时, 后台线程要么 {@code postValue} 后用
 *       参数传递, 要么 {@code new Handler(Looper.getMainLooper()).post(...)}
 *       把后续决策切回主线程.</li>
 * </ul>
 *
 * <p>主线程统一 {@code setValue}、后台线程统一 {@code postValue},
 * 是为了让大多数触发场景 (主线程) 拿到同步可见性, 而不强制后台路径走
 * 异步. 如果后台调用方需要立即读, 应该改用参数 / 局部变量, 而不是
 * 在 {@code postValue} 后接着 {@code getCurrentTaskId()}.</p>
 */
public class InventorySession {

    /**
     * 单条扫描记录被删除后的广播载荷: 哪个任务下的哪个 EPC 被删了.
     * 结果页"盘盈"行删除 → {@code ScanViewModel} 需把该 EPC 从内存去重集
     * {@code scannedEpcSet} 移除, 否则同标签重扫会被"已见过"挡住、不落库.
     */
    public static final class ScanRemoval {
        public final long taskId;
        public final String epc;
        public ScanRemoval(long taskId, String epc) {
            this.taskId = taskId;
            this.epc = epc;
        }
    }

    private final MutableLiveData<Long> currentTaskId = new MutableLiveData<>();
    /** 单条扫描记录删除广播 (见 {@link ScanRemoval}). */
    private final MutableLiveData<ScanRemoval> lastScanRemoval = new MutableLiveData<>();

    /**
     * 正在"只读查看"的已完成任务 id; null = 未在查看。
     * 由任务页"查看详情"设置、进入任务页时清除。扫描页 / 结果页在自身 ARG 缺省时
     * 回落到它 —— 这样底部 tab 切换 (新建无参 fragment) 也能保持在该任务的查看上下文,
     * 不必回到任务页重新点"查看详情"。主线程读写, volatile 保可见性。
     */
    @Nullable
    private volatile Long viewingTaskId;

    @Nullable
    public Long getCurrentTaskId() {
        return currentTaskId.getValue();
    }

    public LiveData<Long> currentTaskId() {
        return currentTaskId;
    }

    /** 单条扫描记录删除事件流, 供 {@code ScanViewModel} 驱逐内存去重集. */
    public LiveData<ScanRemoval> scanRemovals() {
        return lastScanRemoval;
    }

    @Nullable
    public Long getViewingTaskId() {
        return viewingTaskId;
    }

    public void setViewingTaskId(@Nullable Long id) {
        this.viewingTaskId = id;
    }

    /**
     * 广播"任务 taskId 下的 epc 记录已被删除". 主线程走 {@code setValue},
     * 后台线程 (diskIO) 走 {@code postValue} — 与 {@link #setCurrentTaskId} 同约定.
     */
    public void notifyScanRemoved(long taskId, String epc) {
        ScanRemoval event = new ScanRemoval(taskId, epc);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            lastScanRemoval.setValue(event);
        } else {
            lastScanRemoval.postValue(event);
        }
    }

    public void setCurrentTaskId(long id) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            currentTaskId.setValue(id);
        } else {
            currentTaskId.postValue(id);
        }
    }

    public void clear() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            currentTaskId.setValue(null);
        } else {
            currentTaskId.postValue(null);
        }
    }
}

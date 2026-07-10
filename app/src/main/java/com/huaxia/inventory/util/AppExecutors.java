package com.huaxia.inventory.util;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppExecutors {
    public final ExecutorService diskIO;
    public final ExecutorService excelIO;
    public final Handler mainThread;

    public AppExecutors() {
        this.diskIO = Executors.newFixedThreadPool(4);
        this.excelIO = Executors.newSingleThreadExecutor();
        this.mainThread = new Handler(Looper.getMainLooper());
    }

    /**
     * @deprecated 这是进程级单例, 跟 JVM 同生死. 进程退出时由系统回收, 不需要手动 shutdown.
     *             历史上的 Bug: 曾在 MainActivity.onDestroy 调用此方法, 导致 Activity
     *             重建 (config change / 任务栈切换) 时所有 ViewModel 的 diskIO.execute()
     *             命中已 Terminated 的线程池 → RejectedExecutionException 闪退.
     *             仅供测试或极端的强制清理场景使用.
     */
    @Deprecated
    public void shutdown() {
        if (!diskIO.isShutdown()) diskIO.shutdown();
        if (!excelIO.isShutdown()) excelIO.shutdown();
    }
}

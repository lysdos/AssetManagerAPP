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

    public void shutdown() {
        diskIO.shutdown();
        excelIO.shutdown();
    }
}

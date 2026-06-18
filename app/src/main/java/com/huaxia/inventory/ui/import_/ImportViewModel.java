package com.huaxia.inventory.ui.import_;

import android.app.Application;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.excel.ExcelImporter;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

public class ImportViewModel extends AndroidViewModel {

    /** 流式解析时，每个 batch 攒多少条再回调。建议 500-1000。 */
    private static final int STREAM_BATCH = 500;
    /** preview 列表最大长度 — 远小于 STREAM_BATCH 时也能立即拿到。 */
    private static final int PREVIEW_MAX = 10;
    /** 错误采样上限，避免错误信息本身也 OOM。 */
    private static final int ERROR_SAMPLE_MAX = 100;

    public static class ParsedFile {
        public final String fileName;
        public final int successCount;
        public final int errorCount;
        public final List<String> errorSample;
        public final List<Asset> preview;
        /** 提交时需要重新打开文件。 */
        public final Uri sourceUri;
        public ParsedFile(String n, int s, int eCount, List<String> eSample,
                          List<Asset> p, Uri src) {
            this.fileName = n; this.successCount = s;
            this.errorCount = eCount; this.errorSample = eSample;
            this.preview = p; this.sourceUri = src;
        }
    }

    private static final String TAG = "ImportVM";

    public static class ImportProgress {
        public final int imported;   // 已写入数据库条数
        public final int total;      // 总条数（来自预期值或解析结果）
        public final long taskId;    // 第一次回调时为 0，commit 创建任务后才有值
        public ImportProgress(int imported, int total, long taskId) {
            this.imported = imported; this.total = total; this.taskId = taskId;
        }
    }

    private final Executor diskIO;
    private final AppDatabase db;
    private final InventoryApp app;
    private final MutableLiveData<ParsedFile> parsedFile = new MutableLiveData<>();
    private final MutableLiveData<String> parseError = new MutableLiveData<>();
    private final MutableLiveData<ImportProgress> importProgress = new MutableLiveData<>();
    private final MutableLiveData<Long> importDone = new MutableLiveData<>();
    private final MutableLiveData<String> importError = new MutableLiveData<>();

    public ImportViewModel(@NonNull Application app) {
        super(app);
        this.app = (InventoryApp) app;
        this.diskIO = this.app.executors.diskIO;
        this.db = this.app.db;
    }

    public LiveData<ParsedFile> parsedFile() { return parsedFile; }
    public LiveData<String> parseError() { return parseError; }
    public LiveData<ImportProgress> importProgress() { return importProgress; }
    public LiveData<Long> importDone() { return importDone; }
    public LiveData<String> importError() { return importError; }

    /**
     * 流式解析：不再把全表 Asset 留在堆里。保留前 10 条预览 + 错误采样 + 源 Uri。
     */
    public void parseFile(Uri uri, String displayName) {
        Log.i(TAG, "parseFile start uri=" + uri + " name=" + displayName);
        diskIO.execute(() -> {
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                Log.i(TAG, "openInputStream returned " + (in == null ? "null" : "ok"));
                int[] counters = {0, 0};
                List<Asset> preview = new ArrayList<>(PREVIEW_MAX);
                List<String> errors = new ArrayList<>(ERROR_SAMPLE_MAX);
                ExcelImporter.stream(in, 0L, STREAM_BATCH,
                        new ExcelImporter.BatchHandler() {
                            @Override public void onBatch(List<Asset> batch) {
                                counters[0] += batch.size();
                                for (Asset a : batch) {
                                    if (preview.size() < PREVIEW_MAX) preview.add(a);
                                    else break;
                                }
                            }
                            @Override public void onError(String message) {
                                counters[1]++;
                                Log.w(TAG, "row onError: " + message);
                                if (errors.size() < ERROR_SAMPLE_MAX) errors.add(message);
                            }
                        });
                Log.i(TAG, "stream() returned success=" + counters[0] + " error=" + counters[1]);
                parsedFile.postValue(new ParsedFile(
                        displayName, counters[0], counters[1], errors, preview, uri));
                Log.i(TAG, "parsedFile.postValue success=" + counters[0]);
            } catch (Exception e) {
                Log.e(TAG, "parseFile FAILED", e);
                String msg = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                parseError.postValue(msg);
                parsedFile.postValue(new ParsedFile(
                        displayName, 0, 0,
                        Collections.singletonList("Parse error: " + msg),
                        Collections.emptyList(), uri));
                Log.w(TAG, "posted parsedFile(success=0) due to exception");
            }
        });
    }

    /**
     * 真正写入：重新打开 Uri 流式读 + 每批 500 条 insertAll。
     * 通过 {@link #importProgress} 回调进度，完成后通过 {@link #importDone} 回调 taskId。
     */
    public void commitToTask(String fileName, Uri sourceUri, int expectedTotal,
                             ImportCallback cb) {
        if (sourceUri == null) {
            importError.postValue("sourceUri 为空");
            return;
        }
        diskIO.execute(() -> {
            try {
                InventoryTask task = new InventoryTask();
                task.sourceFileName = fileName;
                task.importedAt = System.currentTimeMillis();
                task.status = "IN_PROGRESS";
                task.expectedCount = expectedTotal;
                long taskId = db.inventoryTaskDao().insert(task);
                importProgress.postValue(new ImportProgress(0, expectedTotal, taskId));

                int[] imported = {0};
                try (InputStream in = app.getContentResolver().openInputStream(sourceUri)) {
                    ExcelImporter.stream(in, taskId, STREAM_BATCH,
                            new ExcelImporter.BatchHandler() {
                                @Override public void onBatch(List<Asset> batch) {
                                    // insertAll 内部单事务，单批 500 条 SQLite 写入 ≈ 5-15ms
                                    db.assetDao().insertAll(batch);
                                    imported[0] += batch.size();
                                    importProgress.postValue(new ImportProgress(
                                            imported[0], expectedTotal, taskId));
                                }
                                @Override public void onError(String message) {
                                    // 提交阶段不再处理行错误（解析阶段已统计）
                                }
                            });
                }
                importDone.postValue(taskId);
                cb.onCommitted(taskId);
            } catch (Exception e) {
                Log.e(TAG, "commitToTask FAILED", e);
                String msg = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                importError.postValue(msg);
            }
        });
    }

    public interface ImportCallback {
        void onCommitted(long taskId);
    }
}

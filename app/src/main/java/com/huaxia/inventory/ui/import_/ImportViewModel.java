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
        /**
         * 解析成功的行中, 命中 DB 已存在 epc 的条数.
         * 仅统计 (同文件内 HashSet 去重后) 的 epc 与 DB 现有 epc 的交集 —
         * 导入时这些会被 ON CONFLICT(epc) DO UPDATE 覆盖.
         */
        public final int duplicateCount;
        public final List<String> errorSample;
        public final List<Asset> preview;
        /** 提交时需要重新打开文件。 */
        public final Uri sourceUri;
        public ParsedFile(String n, int s, int eCount, int dCount,
                          List<String> eSample,
                          List<Asset> p, Uri src) {
            this.fileName = n; this.successCount = s;
            this.errorCount = eCount; this.duplicateCount = dCount;
            this.errorSample = eSample;
            this.preview = p; this.sourceUri = src;
        }
    }

    private static final String TAG = "ImportVM";

    public static class ImportProgress {
        public final int imported;   // 已写入数据库条数（含新增 + 覆盖更新）
        public final int updated;    // 本批中命中 DB 已存在 epc 的覆盖条数
        public final int total;      // 总条数（来自预期值或解析结果）
        /**
         * 导入后归属 taskId. 库存池模式固定 = 0;
         * 旧的"导入即建任务"模式才会传具体 taskId (现已废弃).
         */
        public final long taskId;
        public ImportProgress(int imported, int updated, int total, long taskId) {
            this.imported = imported; this.updated = updated;
            this.total = total; this.taskId = taskId;
        }
    }

    /**
     * 导入完成结果.
     * @param imported 入库总条数 = 新增 + 覆盖更新
     * @param updated  命中已存在 epc 的覆盖更新条数
     * @param added    实际新增条数 = imported - updated
     */
    public static class ImportResult {
        public final int imported;
        public final int updated;
        public final int added;
        public ImportResult(int imported, int updated) {
            this.imported = imported;
            this.updated = updated;
            this.added = imported - updated;
        }
    }

    private final Executor diskIO;
    private final AppDatabase db;
    private final InventoryApp app;
    private final MutableLiveData<ParsedFile> parsedFile = new MutableLiveData<>();
    private final MutableLiveData<String> parseError = new MutableLiveData<>();
    private final MutableLiveData<ImportProgress> importProgress = new MutableLiveData<>();
    /**
     * 导入完成信号。值 = {@link ImportResult}, 仅作触发使用.
     * 库存池模式不再创建 InventoryTask，因此这里没有 taskId。
     */
    private final MutableLiveData<ImportResult> importDone = new MutableLiveData<>();
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
    public LiveData<ImportResult> importDone() { return importDone; }
    public LiveData<String> importError() { return importError; }

    /**
     * 流式解析：不再把全表 Asset 留在堆里。保留前 10 条预览 + 错误采样 + 源 Uri。
     */
    public void parseFile(Uri uri, String displayName) {
        Log.i(TAG, "parseFile start uri=" + uri + " name=" + displayName);
        diskIO.execute(() -> {
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                Log.i(TAG, "openInputStream returned " + (in == null ? "null" : "ok"));
                int[] counters = {0, 0, 0};  // [success, error, duplicate]
                List<Asset> preview = new ArrayList<>(PREVIEW_MAX);
                List<String> errors = new ArrayList<>(ERROR_SAMPLE_MAX);
                ExcelImporter.stream(in, 0L, STREAM_BATCH,
                        new ExcelImporter.BatchHandler() {
                            @Override public void onBatch(List<Asset> batch) {
                                counters[0] += batch.size();
                                // 解析阶段只读 DB, 不写 — 统计"已存在 epc"条数.
                                // 每批 500 条调一次 IN 查询, 3400 行 ≈ 7 次, 可接受.
                                List<String> batchEpcs = new ArrayList<>(batch.size());
                                for (Asset a : batch) batchEpcs.add(a.epc);
                                counters[2] += db.assetDao().findExistingEpcs(batchEpcs).size();
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
                Log.i(TAG, "stream() returned success=" + counters[0]
                        + " error=" + counters[1]
                        + " duplicate=" + counters[2]);
                parsedFile.postValue(new ParsedFile(
                        displayName, counters[0], counters[1], counters[2],
                        errors, preview, uri));
                Log.i(TAG, "parsedFile.postValue success=" + counters[0]);
            } catch (Exception e) {
                Log.e(TAG, "parseFile FAILED", e);
                String msg = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                parseError.postValue(msg);
                parsedFile.postValue(new ParsedFile(
                        displayName, 0, 0, 0,
                        Collections.singletonList("Parse error: " + msg),
                        Collections.emptyList(), uri));
                Log.w(TAG, "posted parsedFile(success=0) due to exception");
            }
        });
    }

    /**
     * 真正写入：重新打开 Uri 流式读 + 每批 500 条 insertAll。
     * 通过 {@link #importProgress} 回调进度，完成后通过 {@link #importDone} 回调写入条数。
     *
     * <p><b>库存池模式</b>：不再创建 InventoryTask。所有资产以 taskId=0 写入，
     * 后续在任务页由用户创建任务时，由 {@link com.huaxia.inventory.ui.task.TaskListViewModel#createTask}
     * 把 pool 资产划拨到新任务。
     *
     * <p>方法名保留 commitToTask 是为了不破坏 dialog 端调用，但 taskId 已无意义。
     *
     * @param cb 回调参数 = 实际写入条数（旧版是 taskId，已废弃语义）
     */
    public void commitToTask(String fileName, Uri sourceUri, int expectedTotal,
                             ImportCallback cb) {
        if (sourceUri == null) {
            importError.postValue("sourceUri 为空");
            return;
        }
        diskIO.execute(() -> {
            try {
                // 库存池模式: 直接以 taskId=0 导入, 不创建 InventoryTask
                importProgress.postValue(new ImportProgress(0, 0, expectedTotal, 0L));

                int[] imported = {0};
                int[] updated = {0};
                try (InputStream in = app.getContentResolver().openInputStream(sourceUri)) {
                    ExcelImporter.stream(in, 0L, STREAM_BATCH,
                            new ExcelImporter.BatchHandler() {
                                @Override public void onBatch(List<Asset> batch) {
                                    // upsertBatch 走单事务 ON CONFLICT(epc) DO UPDATE —
                                    // 重复 epc 覆盖业务字段, 保留 id / taskId / inventory_time.
                                    // 统计 updated: 批量查 DB 中已存在的 epc 数.
                                    List<String> batchEpcs = new ArrayList<>(batch.size());
                                    for (Asset a : batch) batchEpcs.add(a.epc);
                                    int updatedInBatch = db.assetDao().findExistingEpcs(batchEpcs).size();
                                    db.assetDao().upsertBatch(batch);
                                    imported[0] += batch.size();
                                    updated[0] += updatedInBatch;
                                    importProgress.postValue(new ImportProgress(
                                            imported[0], updated[0], expectedTotal, 0L));
                                }
                                @Override public void onError(String message) {
                                    // 提交阶段不再处理行错误（解析阶段已统计）
                                }
                            });
                }
                ImportResult result = new ImportResult(imported[0], updated[0]);
                importDone.postValue(result);
                cb.onCommitted(result.imported);
            } catch (Exception e) {
                Log.e(TAG, "commitToTask FAILED", e);
                String msg = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                importError.postValue(msg);
            }
        });
    }

    /**
     * @param count 实际入库条数 (新增 + 覆盖更新); 库存池模式下旧版 taskId 语义已废弃
     */
    public interface ImportCallback {
        void onCommitted(long count);
    }
}

package com.huaxia.inventory.ui.import_;

import android.app.Application;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
         * 导入时这些会被 upsertOne (INSERT OR IGNORE + 条件 UPDATE) 覆盖.
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
        this(app, null);
    }

    /**
     * 测试 / 诊断用构造器 — {@code dbOverride != null} 时绕过 {@link InventoryApp#getDb()}
     * 走传入的 db, 允许导入路径用 in-memory db 跑不污染真实数据.
     * <p>普通代码路径仍然走单参 {@link #ImportViewModel(Application)} 构造器 —
     * {@code dbOverride} 默认 null, 行为与之前完全一致.</p>
     *
     * <p>设计动机: {@link com.huaxia.inventory.InventoryApp} 私有化字段之后,
     * {@code _DiagnoseViewModelTest} 之前通过 {@code app.db = inMemoryDb} 注入
     * 的方式不再可用 (字段已 private). 留这个构造器是测试期的最小入口 —
     * 生产代码 (ViewModelProvider) 仍然走单参, 不会用到.</p>
     */
    @androidx.annotation.VisibleForTesting
    public ImportViewModel(@NonNull Application app, @Nullable AppDatabase dbOverride) {
        super(app);
        this.app = (InventoryApp) app;
        this.diskIO = this.app.getExecutors().diskIO;
        this.db = dbOverride != null ? dbOverride : this.app.getDb();
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
                if (in == null) {
                    // URI 不可用 / 权限已失效时 openInputStream 返回 null —
                    // 直接给明确提示, 不要让 null 流进 XSSFWorkbook 抛 NPE 串.
                    Log.w(TAG, "openInputStream returned null — URI unavailable");
                    parseError.postValue("无法读取文件（URI 不可用或权限已失效）");
                    parsedFile.postValue(new ParsedFile(displayName, 0, 0, 0,
                            Collections.singletonList("无法读取文件：URI 不可用或权限已失效"),
                            Collections.emptyList(), uri));
                    return;
                }
                Log.i(TAG, "openInputStream returned ok");
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
                    if (in == null) {
                        // 同 parseFile: URI 失效时明确报错而非 NPE.
                        importError.postValue("无法读取文件（URI 不可用或权限已失效）");
                        return;
                    }
                    ExcelImporter.stream(in, 0L, STREAM_BATCH,
                            new ExcelImporter.BatchHandler() {
                                @Override public void onBatch(List<Asset> batch) {
                                    // 单事务: findExistingEpcs + upsertBatch — 防止 diskIO
                                    // 队列上另一任务 (例如 ResultFragment.refreshStats /
                                    // ScanViewModel.recomputeStats 间接触发 Asset 写入)
                                    // 在步骤 1 与步骤 2 之间插入新 Asset, 导致 updated
                                    // 计数与实际覆盖更新路径偏差.
                                    final int[] updatedInBatchHolder = {0};
                                    try {
                                        db.runInTransaction(() -> {
                                            List<String> batchEpcs = new ArrayList<>(batch.size());
                                            for (Asset a : batch) batchEpcs.add(a.epc);
                                            updatedInBatchHolder[0] = db.assetDao()
                                                    .findExistingEpcs(batchEpcs).size();
                                            db.assetDao().upsertBatch(batch);
                                        });
                                    } catch (Exception ex) {
                                        Log.e(TAG, "commit batch (size=" + batch.size()
                                                + ") failed", ex);
                                        throw ex;
                                    }
                                    int updatedInBatch = updatedInBatchHolder[0];
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

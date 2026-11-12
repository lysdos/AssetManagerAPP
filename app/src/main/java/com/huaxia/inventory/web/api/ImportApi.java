package com.huaxia.inventory.web.api;

import android.util.Log;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.excel.ExcelImporter;
import com.huaxia.inventory.web.ApiException;
import com.huaxia.inventory.web.WebRequest;
import com.huaxia.inventory.web.WebResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Excel 上传导入库存池（taskId = 0）。
 *
 * <p>与 {@code ImportViewModel.commitToTask} 同构：每批 500 条用单事务包住
 * 「查重 + 写入」，防止并发插入让 updated 计数漂移。
 *
 * <p>并发控制：同一时刻只允许一个导入 —— 两个导入并发 upsert 同一 epc 会互相覆盖。
 */
public class ImportApi {

    private static final String TAG = "ImportApi";
    private static final int BATCH = 500;
    private static final int ERROR_SAMPLE_MAX = 50;

    private final AppDatabase db;
    private final File uploadDir;
    private final AtomicBoolean importing = new AtomicBoolean(false);

    public ImportApi(AppDatabase db, File uploadDir) {
        this.db = db;
        this.uploadDir = uploadDir;
    }

    public WebResponse upload(WebRequest req) throws ApiException {
        byte[] body = req.body();
        if (body.length == 0) {
            throw ApiException.badRequest("上传内容为空");
        }
        if (!importing.compareAndSet(false, true)) {
            throw ApiException.conflict("IMPORT_IN_PROGRESS", "已有导入正在进行，请稍后重试");
        }
        File tmp = null;
        try {
            tmp = writeTemp(body);
            return WebResponse.ok(parseAndCommit(tmp));
        } finally {
            importing.set(false);
            if (tmp != null && tmp.exists() && !tmp.delete()) {
                Log.w(TAG, "failed to delete temp upload " + tmp.getName());
            }
        }
    }

    private File writeTemp(byte[] body) throws ApiException {
        if (!uploadDir.exists() && !uploadDir.mkdirs()) {
            throw ApiException.badRequest("无法创建上传临时目录");
        }
        File tmp = new File(uploadDir, UUID.randomUUID() + ".xlsx");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(body);
        } catch (IOException e) {
            throw ApiException.badRequest("写入临时文件失败");
        }
        return tmp;
    }

    private Map<String, Object> parseAndCommit(File file) throws ApiException {
        int[] counters = {0, 0, 0};   // success, failed, updated
        List<String> errors = new ArrayList<>();

        try (InputStream in = new FileInputStream(file)) {
            ExcelImporter.stream(in, 0L, BATCH, new ExcelImporter.BatchHandler() {
                @Override public void onBatch(List<Asset> batch) {
                    final int[] updatedInBatch = {0};
                    db.runInTransaction(() -> {
                        List<String> epcs = new ArrayList<>(batch.size());
                        for (Asset a : batch) epcs.add(a.epc);
                        updatedInBatch[0] = db.assetDao().findExistingEpcs(epcs).size();
                        db.assetDao().upsertBatch(batch);
                    });
                    counters[0] += batch.size();
                    counters[2] += updatedInBatch[0];
                }

                @Override public void onError(String message) {
                    counters[1]++;
                    if (errors.size() < ERROR_SAMPLE_MAX) errors.add(message);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "import failed", e);
            throw new ApiException(422, "IMPORT_PARSE_FAILED",
                    "文件解析失败：" + (e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", counters[0]);
        out.put("updated", counters[2]);
        out.put("added", counters[0] - counters[2]);
        out.put("failed", counters[1]);
        out.put("errors", errors);
        out.put("truncated", counters[1] > errors.size());
        return out;
    }
}

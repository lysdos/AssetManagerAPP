package com.huaxia.inventory.web.api;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.excel.AssetLedgerExporter;
import com.huaxia.inventory.excel.ExcelExporter;
import com.huaxia.inventory.excel.HeaderSpec;
import com.huaxia.inventory.util.SqlLikeEscaper;
import com.huaxia.inventory.web.ApiException;
import com.huaxia.inventory.web.WebRequest;
import com.huaxia.inventory.web.WebResponse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Excel 导出。
 *
 * <p>先写临时文件再以文件流下发 —— 客户端中途取消下载时，写临时文件不会像写管道那样
 * 抛 broken pipe；临时文件在流关闭时删除。
 */
public class ExportApi {

    private static final String TAG = "ExportApi";
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final AppDatabase db;
    private final Context ctx;
    private final File exportDir;
    private final ConcurrentHashMap<Long, Boolean> inFlight = new ConcurrentHashMap<>();

    public ExportApi(AppDatabase db, Context ctx, File exportDir) {
        this.db = db;
        this.ctx = ctx.getApplicationContext();
        this.exportDir = exportDir;
    }

    public WebResponse taskReport(WebRequest req) throws ApiException {
        long taskId = parseTaskId(req);
        InventoryTask task = db.inventoryTaskDao().findById(taskId);
        if (task == null) throw ApiException.notFound("TASK_NOT_FOUND", "任务不存在");

        if (inFlight.putIfAbsent(taskId, Boolean.TRUE) != null) {
            throw ApiException.conflict("EXPORT_IN_PROGRESS", "该任务正在导出，请稍后重试");
        }
        try {
            // 导出前补一次盘点时间，否则「盘点时间」列可能整列为空
            new com.huaxia.inventory.data.ResultRepository(db)
                    .ensureInventoryTime(taskId, task.status);
            File out = newTempFile();
            try (OutputStream os = new FileOutputStream(out)) {
                ExcelExporter.exportStreamed(os, task.sourceFileName, task.status,
                        rebuildHeaders(taskId, task.status),
                        new com.huaxia.inventory.data.ResultRepository(db),
                        taskId, ctx.getCacheDir());
            } catch (IOException e) {
                deleteQuietly(out);
                throw new ApiException(500, "INTERNAL", "导出失败：" + e.getMessage());
            }
            return fileResponse(out, task.sourceFileName + "_盘点结果.xlsx");
        } finally {
            inFlight.remove(taskId);
        }
    }

    public WebResponse ledger(WebRequest req) throws ApiException {
        String pattern = SqlLikeEscaper.likePattern(req.query("keyword"));
        String dept = emptyToNull(req.query("dept"));
        String type = emptyToNull(req.query("type"));

        File out = newTempFile();
        try (OutputStream os = new FileOutputStream(out)) {
            AssetLedgerExporter.export(os, db.assetDao(), pattern, dept, type,
                    ctx.getCacheDir());
        } catch (IOException e) {
            deleteQuietly(out);
            throw new ApiException(500, "INTERNAL", "导出失败：" + e.getMessage());
        }
        return fileResponse(out, "资产台账.xlsx");
    }

    public WebResponse template(WebRequest req) throws ApiException {
        try (InputStream in = ctx.getAssets().open("import_template.xlsx")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return WebResponse.bytes(out.toByteArray(), XLSX_MIME);
        } catch (IOException e) {
            Log.e(TAG, "read import template failed", e);
            throw new ApiException(500, "INTERNAL", "读取模板失败");
        }
    }

    /** path 参数形如 "3.xlsx"，剥掉后缀再解析。 */
    private static long parseTaskId(WebRequest req) throws ApiException {
        String raw = req.pathParam("id");
        if (raw != null && raw.endsWith(".xlsx")) {
            raw = raw.substring(0, raw.length() - 5);
        }
        try {
            return Long.parseLong(raw);
        } catch (Exception e) {
            throw ApiException.badRequest("任务 id 必须是数字");
        }
    }

    /**
     * 从该任务首个 asset 的 rawRowJson 重建原始表头顺序，并丢弃「盘点日期」列
     * （由「盘点时间」取代）。与 App 端 ResultFragment 的导出逻辑一致。
     */
    private List<String> rebuildHeaders(long taskId, String taskStatus) {
        return ExcelExporter.rebuildHeaders(
                new com.huaxia.inventory.data.ResultRepository(db), taskId, taskStatus);
    }

    private WebResponse fileResponse(File file, String downloadName) throws ApiException {
        try {
            InputStream in = new SelfDeletingInputStream(new FileInputStream(file), file);
            return WebResponse.file(in, file.length(), XLSX_MIME, downloadName);
        } catch (IOException e) {
            deleteQuietly(file);
            throw new ApiException(500, "INTERNAL", "读取导出文件失败");
        }
    }

    private File newTempFile() throws ApiException {
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw new ApiException(500, "INTERNAL", "无法创建导出临时目录");
        }
        return new File(exportDir, UUID.randomUUID() + ".xlsx");
    }

    private static void deleteQuietly(File f) {
        if (f.exists() && !f.delete()) Log.w(TAG, "failed to delete " + f.getName());
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** 流关闭时删除底层临时文件。 */
    private static final class SelfDeletingInputStream extends FilterInputStream {
        private final File file;

        SelfDeletingInputStream(InputStream in, File file) {
            super(in);
            this.file = file;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "failed to delete export " + file.getName());
                }
            }
        }
    }
}

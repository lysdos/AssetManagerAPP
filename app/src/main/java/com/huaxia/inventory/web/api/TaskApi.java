package com.huaxia.inventory.web.api;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.dao.ScanAggregate;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.service.TaskService;
import com.huaxia.inventory.session.InventorySession;
import com.huaxia.inventory.web.ApiException;
import com.huaxia.inventory.web.WebJson;
import com.huaxia.inventory.web.WebRequest;
import com.huaxia.inventory.web.WebResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 盘点任务读写接口 —— 全部委托 {@link TaskService}，不复制事务逻辑。 */
public class TaskApi {

    private final AppDatabase db;
    private final TaskService taskService;
    private final InventorySession session;

    public TaskApi(AppDatabase db, TaskService taskService, InventorySession session) {
        this.db = db;
        this.taskService = taskService;
        this.session = session;
    }

    public WebResponse list(WebRequest req) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (InventoryTask t : db.inventoryTaskDao().findAll()) {
            items.add(summary(t));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", items.size());
        return WebResponse.ok(body);
    }

    public WebResponse detail(WebRequest req) throws ApiException {
        InventoryTask t = requireTask(req);
        Map<String, Object> body = summary(t);
        body.put("remark", t.remark);
        return WebResponse.ok(body);
    }

    public WebResponse depts(WebRequest req) {
        return WebResponse.ok(taskService.distinctPoolDepts());
    }

    public WebResponse create(WebRequest req) throws ApiException {
        Map<?, ?> body = parseBody(req);
        String name = asString(body.get("name"));
        if (name == null || name.trim().isEmpty()) {
            throw ApiException.badRequest("任务名称不能为空");
        }
        Long previous = session.getCurrentTaskId();
        long id = taskService.createTask(name.trim(), asString(body.get("dept")),
                asString(body.get("remark")));
        InventoryTask created = db.inventoryTaskDao().findById(id);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("assigned", created == null ? 0 : created.expectedCount);
        out.put("sessionChanged", !Objects.equals(previous, session.getCurrentTaskId()));
        out.put("previousTaskId", previous);
        return WebResponse.ok(out);
    }

    public WebResponse pause(WebRequest req) throws ApiException {
        InventoryTask t = requireTask(req);
        Long previous = session.getCurrentTaskId();
        taskService.pauseTask(t.id);
        return WebResponse.ok(changeResultBody(previous));
    }

    public WebResponse resume(WebRequest req) throws ApiException {
        InventoryTask t = requireTask(req);
        Long previous = session.getCurrentTaskId();
        taskService.resumeTask(t.id);
        return WebResponse.ok(changeResultBody(previous));
    }

    public WebResponse complete(WebRequest req) throws ApiException {
        InventoryTask t = requireTask(req);
        Long previous = session.getCurrentTaskId();
        taskService.completeTask(t.id);
        return WebResponse.ok(changeResultBody(previous));
    }

    public WebResponse delete(WebRequest req) throws ApiException {
        InventoryTask t = requireTask(req);
        Long previous = session.getCurrentTaskId();
        int released = taskService.deleteTask(t.id);
        Map<String, Object> out = changeResultBody(previous);
        out.put("released", released);
        return WebResponse.ok(out);
    }

    public WebResponse clearCompleted(WebRequest req) {
        Long previous = session.getCurrentTaskId();
        taskService.clearCompleted();
        return WebResponse.ok(changeResultBody(previous));
    }

    private Map<String, Object> changeResultBody(Long previous) {
        Long now = session.getCurrentTaskId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sessionChanged", !Objects.equals(previous, now));
        out.put("previousTaskId", previous);
        out.put("currentTaskId", now);
        return out;
    }

    private InventoryTask requireTask(WebRequest req) throws ApiException {
        long id = req.pathParamLong("id");
        InventoryTask t = db.inventoryTaskDao().findById(id);
        if (t == null) throw ApiException.notFound("TASK_NOT_FOUND", "任务不存在");
        return t;
    }

    private Map<String, Object> summary(InventoryTask t) {
        ScanAggregate agg = db.scanRecordDao().aggregateStats(t.id);
        int scanned = agg.matchedCount;
        int missing = Math.max(0, t.expectedCount - scanned);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id);
        m.put("name", t.sourceFileName);
        m.put("dept", t.dept);
        m.put("status", t.status);
        m.put("expected", t.expectedCount);
        m.put("scanned", scanned);
        m.put("missing", missing);
        m.put("extra", agg.extraCount);
        m.put("progress", t.expectedCount == 0 ? 0.0
                : Math.min(1.0, (double) scanned / t.expectedCount));
        m.put("lastScannedAt", agg.lastScannedAt);
        m.put("importedAt", t.importedAt);
        return m;
    }

    /**
     * 解析 JSON 请求体。
     *
     * <p><b>刻意读成 {@code Map} 而不是自定义 DTO</b>：Jackson 反序列化自定义类要靠
     * 字段名反射，release 构建 R8 会重命名字段，即使加 {@code @JsonProperty} 也可能
     * 在混淆后失效（真机实测：JVM 集成测试通过、release 包报「请求体不是合法 JSON」）。
     * {@code Map} 走 Jackson 内置反序列化器，不依赖任何自定义类的反射。
     * 仓库里读 {@code rawRowJson} 也是这个写法。
     */
    private static Map<?, ?> parseBody(WebRequest req) throws ApiException {
        String raw = req.bodyAsString();
        if (raw == null || raw.trim().isEmpty()) {
            throw ApiException.badRequest("请求体不能为空");
        }
        try {
            return WebJson.read(raw, Map.class);
        } catch (Exception e) {
            // 把原因带出去 —— 混淆后的失败原因不可猜，排障时需要原文
            throw ApiException.badRequest("请求体不是合法 JSON："
                    + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()));
        }
    }

    private static String asString(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }
}

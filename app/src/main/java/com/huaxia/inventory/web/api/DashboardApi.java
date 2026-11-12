package com.huaxia.inventory.web.api;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.dao.CategoryCount;
import com.huaxia.inventory.data.dao.ScanAggregate;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.session.InventorySession;
import com.huaxia.inventory.web.WebRequest;
import com.huaxia.inventory.web.WebResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大屏看板聚合接口。
 *
 * <p>一次请求返回全部数据；服务端做 1 秒快照合并 —— 多个浏览器标签同时 5 秒轮询时
 * 不会把 PDA 打成 N 倍负载。
 */
public class DashboardApi {

    private static final int TOP_N = 10;
    private static final long CACHE_TTL_MS = 1000L;

    private final AppDatabase db;
    private final InventorySession session;

    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    public DashboardApi(AppDatabase db, InventorySession session) {
        this.db = db;
        this.session = session;
    }

    public WebResponse handle(WebRequest req) {
        Map<String, Object> snapshot = cached;
        if (snapshot != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
            return WebResponse.ok(snapshot);
        }
        synchronized (this) {
            if (cached != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
                return WebResponse.ok(cached);
            }
            Map<String, Object> fresh = build();
            cached = fresh;
            cachedAt = System.currentTimeMillis();
            return WebResponse.ok(fresh);
        }
    }

    private Map<String, Object> build() {
        List<CategoryCount> deptGroups = db.assetDao().countByDeptGrouped();
        List<CategoryCount> typeGroups = db.assetDao().countByCategoryAll();

        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("total", db.assetDao().countAll());
        ledger.put("deptCount", deptGroups.size());
        ledger.put("typeCount", typeGroups.size());

        List<InventoryTask> tasks = db.inventoryTaskDao().findAll();
        Map<String, Object> taskCounts = new LinkedHashMap<>();
        taskCounts.put("inProgress", countByStatus(tasks, "IN_PROGRESS"));
        taskCounts.put("paused", countByStatus(tasks, "PAUSED"));
        taskCounts.put("completed", countByStatus(tasks, "COMPLETED"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ledger", ledger);
        result.put("taskCounts", taskCounts);
        result.put("activeTask", activeTask(tasks));
        result.put("deptDistribution", topN(deptGroups, "未填写"));
        result.put("typeDistribution", topN(typeGroups, "未分类"));
        result.put("updatedAt", System.currentTimeMillis());
        return result;
    }

    private Map<String, Object> activeTask(List<InventoryTask> tasks) {
        Long currentId = session.getCurrentTaskId();
        if (currentId == null) return null;
        for (InventoryTask t : tasks) {
            if (t.id != currentId) continue;
            ScanAggregate agg = db.scanRecordDao().aggregateStats(t.id);
            int scanned = agg.matchedCount;
            int missing = Math.max(0, t.expectedCount - scanned);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id);
            m.put("name", t.sourceFileName);
            m.put("dept", t.dept);
            m.put("expected", t.expectedCount);
            m.put("scanned", scanned);
            m.put("missing", missing);
            m.put("extra", agg.extraCount);
            m.put("progress", t.expectedCount == 0 ? 0.0
                    : Math.min(1.0, (double) scanned / t.expectedCount));
            m.put("lastScannedAt", agg.lastScannedAt);
            return m;
        }
        return null;
    }

    /** Top N + 「其他」合并。空字符串桶映射成调用方给的中文名。 */
    private static List<Map<String, Object>> topN(List<CategoryCount> groups, String blankLabel) {
        List<Map<String, Object>> out = new ArrayList<>();
        long otherCount = 0;
        for (int i = 0; i < groups.size(); i++) {
            CategoryCount g = groups.get(i);
            if (i < TOP_N) {
                Map<String, Object> m = new LinkedHashMap<>();
                String name = g.category == null || g.category.isEmpty() ? blankLabel : g.category;
                m.put("name", name);
                m.put("count", g.count);
                out.add(m);
            } else {
                otherCount += g.count;
            }
        }
        if (otherCount > 0) {
            Map<String, Object> other = new LinkedHashMap<>();
            other.put("name", "其他");
            other.put("count", otherCount);
            out.add(other);
        }
        return out;
    }

    private static int countByStatus(List<InventoryTask> tasks, String status) {
        int n = 0;
        for (InventoryTask t : tasks) {
            if (status.equals(t.status)) n++;
        }
        return n;
    }
}

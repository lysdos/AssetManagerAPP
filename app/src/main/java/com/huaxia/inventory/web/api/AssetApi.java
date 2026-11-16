package com.huaxia.inventory.web.api;

import androidx.annotation.Nullable;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.ResultRepository;
import com.huaxia.inventory.data.dao.CategoryCount;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.util.EpcNormalizer;
import com.huaxia.inventory.util.SqlLikeEscaper;
import com.huaxia.inventory.web.ApiException;
import com.huaxia.inventory.web.WebRequest;
import com.huaxia.inventory.web.WebResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 资产台账与任务结果的只读查询。 */
public class AssetApi {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final AppDatabase db;

    public AssetApi(AppDatabase db) {
        this.db = db;
    }

    public WebResponse list(WebRequest req) {
        String pattern = SqlLikeEscaper.likePattern(req.query("keyword"));
        String dept = emptyToNull(req.query("dept"));
        String type = emptyToNull(req.query("type"));
        int page = Math.max(1, req.queryInt("page", 1));
        int size = clampSize(req.queryInt("pageSize", DEFAULT_PAGE_SIZE));

        int total = db.assetDao().countFiltered(pattern, dept, type);
        List<Asset> rows = db.assetDao().pageFiltered(pattern, dept, type,
                size, (page - 1) * size);

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (Asset a : rows) items.add(toDto(a));
        return WebResponse.ok(envelope(items, page, size, total));
    }

    public WebResponse filters(WebRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("depts", groups(db.assetDao().countByDeptGrouped(), "未填写"));
        body.put("types", groups(db.assetDao().countByCategoryAll(), "未分类"));
        return WebResponse.ok(body);
    }

    public WebResponse results(WebRequest req) throws ApiException {
        long taskId = req.pathParamLong("id");
        com.huaxia.inventory.data.entity.InventoryTask task =
                db.inventoryTaskDao().findById(taskId);
        if (task == null) {
            throw ApiException.notFound("TASK_NOT_FOUND", "任务不存在");
        }
        String tab = req.query("tab") == null
                ? "ALL" : req.query("tab").toUpperCase(Locale.ROOT);
        ResultRepository.Tab which;
        switch (tab) {
            case "FOUND":   which = ResultRepository.Tab.FOUND;   break;
            case "MISSING": which = ResultRepository.Tab.MISSING; break;
            case "EXTRA":   which = ResultRepository.Tab.EXTRA;   break;
            default:        which = ResultRepository.Tab.ALL;     break;
        }
        int page = Math.max(1, req.queryInt("page", 1));
        int size = clampSize(req.queryInt("pageSize", DEFAULT_PAGE_SIZE));

        List<Map<String, Object>> items = new ArrayList<>();
        for (ResultRepository.ResultRow r : new ResultRepository(db)
                .page(taskId, task.status, which, size, (page - 1) * size)) {
            items.add(toDto(r));
        }
        return WebResponse.ok(envelope(items, page, size,
                totalFor(taskId, task.status, which)));
    }

    private int totalFor(long taskId, String status, ResultRepository.Tab tab) {
        ResultRepository.Counts c = new ResultRepository(db).counts(taskId, status);
        switch (tab) {
            case FOUND:   return c.found;
            case MISSING: return c.missing;
            case EXTRA:   return c.extra;
            case ALL:
            default:      return c.expected;
        }
    }

    private static Map<String, Object> toDto(ResultRepository.ResultRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("epc", displayEpc(r.epc));
        m.put("assetType", r.assetType);
        m.put("assetModel", r.assetModel);
        m.put("serialNumber", r.serialNumber);
        m.put("dept", r.dept);
        m.put("custodian", r.custodian);
        m.put("status", r.status);
        m.put("inventoryTime", r.inventoryTime);
        m.put("scannedAt", r.scannedAt);
        m.put("rssi", r.rssi);
        return m;
    }

    private static Map<String, Object> envelope(List<Map<String, Object>> items,
                                                int page, int size, int total) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", page);
        body.put("pageSize", size);
        body.put("total", total);
        body.put("totalPages", size == 0 ? 0 : (total + size - 1) / size);
        return body;
    }

    private static Map<String, Object> toDto(Asset a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id);
        m.put("epc", displayEpc(a.epc));
        m.put("assetType", a.assetType);
        m.put("assetModel", a.assetModel);
        m.put("serialNumber", a.serialNumber);
        m.put("dept", a.dept);
        m.put("custodian", a.custodian);
        m.put("inventoryTime", a.inventoryTime);
        return m;
    }


    private static List<Map<String, Object>> groups(List<CategoryCount> src, String blankLabel) {
        List<Map<String, Object>> out = new ArrayList<>(src.size());
        for (CategoryCount g : src) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", g.category == null || g.category.isEmpty() ? blankLabel : g.category);
            m.put("count", g.count);
            out.add(m);
        }
        return out;
    }

    private static String displayEpc(@Nullable String epc) {
        String display = EpcNormalizer.toDisplayForm(epc);
        return display == null ? "" : display;
    }

    @Nullable
    private static String emptyToNull(@Nullable String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static int clampSize(int size) {
        if (size <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(size, MAX_PAGE_SIZE);
    }
}

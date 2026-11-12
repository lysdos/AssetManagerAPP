package com.huaxia.inventory.web;

import android.content.Context;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.service.TaskService;
import com.huaxia.inventory.session.InventorySession;
import com.huaxia.inventory.web.api.AssetApi;
import com.huaxia.inventory.web.api.DashboardApi;
import com.huaxia.inventory.web.api.ExportApi;
import com.huaxia.inventory.web.api.ImportApi;
import com.huaxia.inventory.web.api.TaskApi;

import java.util.Collections;

/** 把各 API 模块挂到路由上 —— 后续任务在这里逐行追加。 */
final class WebApiRegistry {

    private WebApiRegistry() {}

    static void register(WebRouter router, Context ctx, AppDatabase db,
                         InventorySession session) {
        router.get("/api/ping", r -> WebResponse.ok(Collections.singletonMap("ok", true)));

        DashboardApi dashboard = new DashboardApi(db, session);
        router.get("/api/dashboard", dashboard::handle);

        TaskApi tasks = new TaskApi(db, new TaskService(db, session), session);
        // 注册顺序: 字面量路径必须排在 {id} 之前，否则 {id} 先匹配并在 parseLong 失败后返回 400
        router.get("/api/tasks/depts", tasks::depts);
        router.delete("/api/tasks/completed", tasks::clearCompleted);
        router.get("/api/tasks", tasks::list);
        router.post("/api/tasks", tasks::create);
        router.get("/api/tasks/{id}", tasks::detail);
        router.post("/api/tasks/{id}/pause", tasks::pause);
        router.post("/api/tasks/{id}/resume", tasks::resume);
        router.post("/api/tasks/{id}/complete", tasks::complete);
        router.delete("/api/tasks/{id}", tasks::delete);

        AssetApi assets = new AssetApi(db);
        router.get("/api/assets", assets::list);
        router.get("/api/assets/filters", assets::filters);
        router.get("/api/tasks/{id}/results", assets::results);

        ImportApi importer = new ImportApi(db, new java.io.File(ctx.getCacheDir(), "web-upload"));
        router.post("/api/import", importer::upload);

        ExportApi exporter = new ExportApi(db, ctx,
                new java.io.File(ctx.getCacheDir(), "web-export"));
        router.get("/api/export/task/{id}.xlsx", exporter::taskReport);
        router.get("/api/export/ledger.xlsx", exporter::ledger);
        router.get("/api/template.xlsx", exporter::template);
    }
}

package com.huaxia.inventory.web;

import android.content.Context;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.session.InventorySession;

import java.util.Collections;

/** 把各 API 模块挂到路由上 —— 后续任务在这里逐行追加。 */
final class WebApiRegistry {

    private WebApiRegistry() {}

    static void register(WebRouter router, Context ctx, AppDatabase db,
                         InventorySession session) {
        router.get("/api/ping", r -> WebResponse.ok(Collections.singletonMap("ok", true)));
    }
}

package com.huaxia.inventory.web;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 极简路由：方法 + 路径模板（支持 {name} 段）。 */
public final class WebRouter {

    public interface Handler {
        WebResponse handle(WebRequest req) throws ApiException;
    }

    private static final class Route {
        final String method;
        final String[] segments;
        final Handler handler;

        Route(String method, String pattern, Handler handler) {
            this.method = method;
            this.segments = split(pattern);
            this.handler = handler;
        }
    }

    private final List<Route> routes = new ArrayList<>();

    public void get(String pattern, Handler h) { routes.add(new Route("GET", pattern, h)); }
    public void post(String pattern, Handler h) { routes.add(new Route("POST", pattern, h)); }
    public void delete(String pattern, Handler h) { routes.add(new Route("DELETE", pattern, h)); }

    /** 分发请求；未匹配方法返回 405，未匹配路径返回 404。 */
    public WebResponse dispatch(WebRequest req) {
        boolean pathMatched = false;
        for (Route r : routes) {
            Map<String, String> params = match(r.segments, split(req.path()));
            if (params == null) continue;
            pathMatched = true;
            if (!r.method.equals(req.method())) continue;
            try {
                return r.handler.handle(req.withPathParams(params));
            } catch (ApiException e) {
                return WebResponse.error(e.status, e.code, e.getMessage());
            } catch (Exception e) {
                return WebResponse.error(500, "INTERNAL",
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        if (pathMatched) {
            return WebResponse.error(405, "METHOD_NOT_ALLOWED", "不支持该请求方法");
        }
        return WebResponse.error(404, "NOT_FOUND", "接口不存在");
    }

    @Nullable
    private static Map<String, String> match(String[] pattern, String[] actual) {
        if (pattern.length != actual.length) return null;
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < pattern.length; i++) {
            String p = pattern[i];
            if (p.startsWith("{") && p.endsWith("}")) {
                params.put(p.substring(1, p.length() - 1), actual[i]);
            } else if (!p.equals(actual[i])) {
                return null;
            }
        }
        return params;
    }

    private static String[] split(String path) {
        String p = path;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? new String[0] : p.split("/");
    }
}

package com.huaxia.inventory.web;

import androidx.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.util.Collections;
import java.util.Map;

/** HTTP 请求值对象 —— 与 NanoHTTPD 解耦，便于单测直接构造。 */
public final class WebRequest {

    private final String method;
    private final String path;
    private final Map<String, String> query;
    private final Map<String, String> headers;
    private final byte[] body;
    private final Map<String, String> pathParams;

    public WebRequest(String method, String path, Map<String, String> query,
                      Map<String, String> headers, byte[] body) {
        this(method, path, query, headers, body, Collections.emptyMap());
    }

    private WebRequest(String method, String path, Map<String, String> query,
                       Map<String, String> headers, byte[] body,
                       Map<String, String> pathParams) {
        this.method = method;
        this.path = path;
        this.query = query == null ? Collections.emptyMap() : query;
        this.headers = headers == null ? Collections.emptyMap() : headers;
        this.body = body == null ? new byte[0] : body;
        this.pathParams = pathParams;
    }

    /** 返回带 path 参数的新实例（路由匹配时由 WebRouter 调用）。 */
    public WebRequest withPathParams(Map<String, String> params) {
        return new WebRequest(method, path, query, headers, body, params);
    }

    public String method() { return method; }

    public String path() { return path; }

    @Nullable
    public String query(String name) { return query.get(name); }

    public int queryInt(String name, int defaultValue) {
        String raw = query.get(name);
        if (raw == null || raw.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Nullable
    public String header(String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    public byte[] body() { return body; }

    public String bodyAsString() {
        try {
            return new String(body, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return new String(body);
        }
    }

    @Nullable
    public String pathParam(String name) { return pathParams.get(name); }

    /** 取 path 参数并转 long；缺失或非法抛 400。 */
    public long pathParamLong(String name) throws ApiException {
        String raw = pathParams.get(name);
        if (raw == null) throw ApiException.badRequest("缺少路径参数 " + name);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("路径参数 " + name + " 必须是数字");
        }
    }
}

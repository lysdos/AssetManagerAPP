package com.huaxia.inventory.web;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** HTTP 响应值对象：JSON / 原始字节 / 文件流 三种。 */
public final class WebResponse {

    public final int status;
    public final String mime;
    public final byte[] bytes;       // bytes 与 stream 二选一
    public final InputStream stream;
    public final long length;
    public final Map<String, String> headers;

    private WebResponse(int status, String mime, byte[] bytes,
                        InputStream stream, long length, Map<String, String> headers) {
        this.status = status;
        this.mime = mime;
        this.bytes = bytes;
        this.stream = stream;
        this.length = length;
        this.headers = headers;
    }

    public static WebResponse json(int status, Object payload) {
        return new WebResponse(status, "application/json; charset=utf-8",
                WebJson.write(payload), null, -1, Collections.emptyMap());
    }

    public static WebResponse ok(Object payload) {
        return json(200, payload);
    }

    public static WebResponse bytes(byte[] data, String mime) {
        return new WebResponse(200, mime, data, null, -1, Collections.emptyMap());
    }

    /**
     * 文件下载。
     *
     * @param downloadName 非 null 时写 Content-Disposition，中文按 RFC 5987 编码
     */
    public static WebResponse file(InputStream in, long length, String mime,
                                   String downloadName) {
        Map<String, String> h = new HashMap<>();
        if (downloadName != null) {
            h.put("Content-Disposition", "attachment; filename*=UTF-8''"
                    + rfc5987(downloadName));
        }
        return new WebResponse(200, mime, null, in, length, h);
    }

    /** 静态资源：带 1 小时缓存头。 */
    public static WebResponse asset(InputStream in, long length, String mime) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "public, max-age=3600");
        return new WebResponse(200, mime, null, in, length, h);
    }

    public static WebResponse error(int status, String code, String message) {
        Map<String, Object> err = new HashMap<>();
        err.put("code", code);
        err.put("message", message);
        return json(status, Collections.singletonMap("error", err));
    }

    private static String rfc5987(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return "download";
        }
    }
}

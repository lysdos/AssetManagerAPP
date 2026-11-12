package com.huaxia.inventory.web;

import android.content.Context;
import android.util.Log;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.session.InventorySession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;

/** NanoHTTPD 子类：线程池、请求体读取、异常兜底、静态资源。 */
public class WebServer extends NanoHTTPD {

    private static final String TAG = "WebServer";

    /** 上传体积上限 20MB —— 千级数据 xlsx 通常 200KB~2MB，留足余量。 */
    private static final int MAX_BODY = 20 * 1024 * 1024;

    /**
     * 工作线程数。
     *
     * <p><b>不能设小</b>：NanoHTTPD 的 {@code ClientHandler.run()} 是 keep-alive 循环 ——
     * 它 {@code execute()} 一次后 {@code goto} 回去继续读同一个 socket，直到连接关闭。
     * 也就是<b>一条浏览器连接独占一个线程</b>。浏览器同源默认开 6 条并行连接，
     * 两台设备就是 12 条，再加上 5 秒轮询的常连接。
     *
     * <p>早期版本用 {@code core=2 / max=4 / ArrayBlockingQueue(32)}：有界队列让线程
     * 永远涨不到 max，实际只有 2 个并发 —— 页面 6 个静态资源请求里 4 个永久排队，
     * 表现为浏览器里 css/js 一直 pending、页面空白。
     */
    private static final int WORKER_THREADS = 16;

    private final Context ctx;
    private final WebRouter router;
    private final ThreadPoolExecutor executor;

    public WebServer(int port, Context ctx, AppDatabase db, InventorySession session) {
        super(port);
        this.ctx = ctx.getApplicationContext();
        this.router = new WebRouter();
        this.executor = new ThreadPoolExecutor(WORKER_THREADS, WORKER_THREADS,
                60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256),
                new ThreadPoolExecutor.AbortPolicy());
        setAsyncRunner(new PooledRunner(executor));
        WebApiRegistry.register(router, this.ctx, db, session);
    }

    /** 让 NanoHTTPD 的请求处理跑在我们的有界线程池里。 */
    private static final class PooledRunner implements NanoHTTPD.AsyncRunner {
        private final ThreadPoolExecutor pool;

        PooledRunner(ThreadPoolExecutor pool) { this.pool = pool; }

        @Override public void closeAll() { pool.shutdownNow(); }

        @Override public void closed(NanoHTTPD.ClientHandler clientHandler) { /* 无需回收 */ }

        @Override public void exec(NanoHTTPD.ClientHandler code) {
            try {
                pool.execute(code);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // 池与队列同时打满（远超正常局域网并发）。此处若让异常冒泡，
                // 会打断 ServerRunnable 的 accept 循环、整个服务停止接受新连接，
                // 所以吞掉并记日志：只丢这一个连接。
                Log.w(TAG, "request rejected: worker pool saturated");
            }
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri == null || uri.isEmpty()) uri = "/";

        if (!uri.startsWith("/api/")) {
            return serveStatic(uri);
        }

        WebRequest req = toWebRequest(session);
        if (req == null) {
            return toNano(WebResponse.error(413, "PAYLOAD_TOO_LARGE", "请求体超过 20MB"));
        }
        return toNano(router.dispatch(req));
    }

    private Response serveStatic(String uri) {
        String path = "/".equals(uri) ? "/index.html" : uri;
        if (path.startsWith("/static/")) {
            path = path.substring("/static".length());
        } else if (!"/index.html".equals(path)) {
            // 非 /static 前缀的未知路径回落到单页应用入口（前端用 hash 路由）
            path = "/index.html";
        }
        try {
            WebResponse res = StaticAssets.serve(ctx, path);
            if (res == null) {
                return toNano(WebResponse.error(404, "NOT_FOUND", "资源不存在"));
            }
            return toNano(res);
        } catch (IOException e) {
            Log.e(TAG, "static serve failed: " + path, e);
            return toNano(WebResponse.error(500, "INTERNAL", "读取资源失败"));
        }
    }

    private WebRequest toWebRequest(IHTTPSession session) {
        Map<String, String> query = new HashMap<>();
        for (Map.Entry<String, List<String>> e : session.getParameters().entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                query.put(e.getKey(), e.getValue().get(0));
            }
        }
        byte[] body = new byte[0];
        String lenHeader = session.getHeaders().get("content-length");
        long declared;
        try {
            declared = lenHeader == null ? 0L : Long.parseLong(lenHeader.trim());
        } catch (NumberFormatException e) {
            declared = 0L;
        }
        if (declared > MAX_BODY) return null;
        if (declared > 0) {
            try {
                body = readBody(session.getInputStream(), (int) declared);
            } catch (IOException e) {
                Log.e(TAG, "read body failed", e);
                return null;
            }
        }
        return new WebRequest(session.getMethod().name(), session.getUri(),
                query, session.getHeaders(), body);
    }

    private static byte[] readBody(InputStream in, int length) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(length);
        byte[] buf = new byte[8192];
        int remaining = length;
        while (remaining > 0) {
            int n = in.read(buf, 0, Math.min(buf.length, remaining));
            if (n <= 0) break;
            out.write(buf, 0, n);
            remaining -= n;
        }
        return out.toByteArray();
    }

    private static Response toNano(WebResponse res) {
        Response nano;
        if (res.bytes != null) {
            nano = newFixedLengthResponse(Response.Status.lookup(res.status),
                    res.mime, new java.io.ByteArrayInputStream(res.bytes), res.bytes.length);
        } else {
            nano = newFixedLengthResponse(Response.Status.lookup(res.status),
                    res.mime, res.stream, res.length);
        }
        for (Map.Entry<String, String> e : res.headers.entrySet()) {
            nano.addHeader(e.getKey(), e.getValue());
        }
        return nano;
    }

    @Override
    public void stop() {
        executor.shutdownNow();
        super.stop();
    }
}

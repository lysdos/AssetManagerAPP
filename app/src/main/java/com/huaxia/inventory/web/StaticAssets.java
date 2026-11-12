package com.huaxia.inventory.web;

import android.content.Context;

import com.huaxia.inventory.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** 从 assets/web/ 读静态资源；debug 构建优先读外部目录便于 adb push 调试。 */
public final class StaticAssets {

    private static final String ASSET_ROOT = "web";

    private StaticAssets() {}

    public static boolean isSafe(String path) {
        if (path == null) return false;
        String decoded = path;
        try {
            decoded = java.net.URLDecoder.decode(path, "UTF-8");
        } catch (Exception ignored) {
            // 解码失败按原串继续校验
        }
        return !decoded.contains("..") && !decoded.contains("\\") && decoded.startsWith("/");
    }

    public static String mimeOf(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    /**
     * 读取静态资源。
     *
     * @param path 以 "/" 开头的资源路径，如 "/js/app.js"；根路径传 "/" 由调用方转成 index.html
     * @return 找不到返回 null
     */
    public static WebResponse serve(Context ctx, String path) throws IOException {
        if (!isSafe(path)) return null;
        String rel = path.substring(1);

        // debug 调试口子：外部目录存在同名文件则优先（release 不启用）
        if (BuildConfig.DEBUG) {
            File external = new File(ctx.getExternalFilesDir(null), ASSET_ROOT + "/" + rel);
            if (external.isFile()) {
                return WebResponse.asset(new FileInputStream(external),
                        external.length(), mimeOf(rel));
            }
        }

        try (InputStream in = ctx.getAssets().open(ASSET_ROOT + "/" + rel)) {
            return WebResponse.bytes(readAll(in), mimeOf(rel));
        } catch (IOException notFound) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}

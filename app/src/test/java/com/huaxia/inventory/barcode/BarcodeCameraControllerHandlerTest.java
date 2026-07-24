package com.huaxia.inventory.barcode;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: BarcodeCameraController.emit 必须复用字段化 {@code mainHandler},
 * 而非每次 {@code new android.os.Handler(Looper.getMainLooper())}. 后者高频回调
 * (analyzer 线程 30fps → 去抖后仍 100-500ms 一次) 持续创建 Handler 实例 + 内部
 * MessageQueue, ART 上有可观察 GC 压力.
 *
 * <p>同时 release() 必须重置 lastEmitMs/lastEmitCode + removeCallbacksAndMessages,
 * 避免 in-flight emit 在新实例复用前命中旧 callback.</p>
 */
public class BarcodeCameraControllerHandlerTest {

    @Test
    public void emit_usesReusedMainHandlerField() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/barcode/BarcodeCameraController.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/barcode/BarcodeCameraController.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 字段声明: private final Handler mainHandler = new Handler(Looper.getMainLooper());
        assertTrue("BarcodeCameraController must declare a reused mainHandler field. Source:\n"
                + text,
                text.contains("private final Handler mainHandler"));

        String emitBody = extractMethodBody(text, "private void emit(");
        assertTrue("emit body not found.\nSource:\n" + text, emitBody.length() > 0);
        // 必须用 mainHandler.post(...), 不允许 new Handler(Looper.getMainLooper()).
        assertTrue("emit must call mainHandler.post(...) to reuse the field, "
                + "not new Handler each call. Body:\n" + emitBody,
                emitBody.contains("mainHandler.post("));
        assertTrue("emit must NOT instantiate a new Handler in emit — leaks via frequent GC. "
                + "Body:\n" + emitBody,
                !emitBody.contains("new android.os.Handler(")
                        && !emitBody.contains("new Handler(android.os.Looper"));
    }

    @Test
    public void release_resetsDebounceAndClearsMainHandlerCallbacks() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/barcode/BarcodeCameraController.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/barcode/BarcodeCameraController.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        String releaseBody = extractMethodBody(text, "public void release()");
        assertTrue("release body not found", releaseBody.length() > 0);

        assertTrue("release() must call mainHandler.removeCallbacksAndMessages(null) — "
                + "clear in-flight emits to a destroyed controller. Body:\n" + releaseBody,
                releaseBody.contains("removeCallbacksAndMessages(null)"));
        assertTrue("release() must reset 2s debounce (lastEmitMs=0, lastEmitCode=\"\") — "
                + "else rebuild controller inherits stale debounce state and swallows "
                + "first identical code. Body:\n" + releaseBody,
                releaseBody.contains("lastEmitMs = 0L")
                        && releaseBody.contains("lastEmitCode = \"\""));
    }

    private static String extractMethodBody(String text, String signature) {
        int sigStart = text.indexOf(signature);
        if (sigStart < 0) return "";
        int braceStart = text.indexOf('{', sigStart);
        if (braceStart < 0) return "";
        int depth = 1;
        int i = braceStart + 1;
        while (i < text.length() && depth > 0) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        if (depth != 0) return "";
        return text.substring(braceStart + 1, i - 1);
    }
}

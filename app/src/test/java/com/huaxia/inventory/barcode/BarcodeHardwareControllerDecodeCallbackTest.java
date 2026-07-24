package com.huaxia.inventory.barcode;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: BarcodeHardwareController.stopScan 必须 {@code decoder.setDecodeCallback(null)}
 * 后置清理. 否则 SDK 内部单例会持有匿名 DecodeCallback, 该 callback 闭包引用本
 * controller; 反复硬件/CameraX/硬件切换后旧 controller 不被回收, 累计 Fragment
 * 视图链泄漏.
 *
 * <p>同时 stopScan 必须重置 {@code lastEmitMs/lastEmitCode} 2s 去抖窗口 — 否则
 * 重建 controller 时首条相同码被去重窗口吞掉.</p>
 */
public class BarcodeHardwareControllerDecodeCallbackTest {

    @Test
    public void stopScan_nullsDecodeCallbackAndResetsDebounce() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/barcode/BarcodeHardwareController.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/barcode/BarcodeHardwareController.java");
        }
        assertTrue("source not found: " + source.getAbsolutePath(), source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        String body = extractMethodBody(text, "public void stopScan()");
        assertTrue("stopScan body not found", body.length() > 0);

        // 必须调 setDecodeCallback(null); SDK 不再持有 controller 引用的 DecodeCallback.
        assertTrue("stopScan must call decoder.setDecodeCallback(null) — else SDK singleton "
                + "holds the controller's anonymous DecodeCallback, leaking Fragment. Body:\n"
                + body,
                body.contains("setDecodeCallback(null)"));

        // 必须把 stopScan 放在 setDecodeCallback(null) 之前 — 否则未停 SDK 调度就先切断回调链,
        // 仍可能有 in-flight emit 命中 activeCallback.
        int stopIdx = body.indexOf("decoder.stopScan()");
        int nullIdx = body.indexOf("setDecodeCallback(null)");
        assertTrue("decoder.stopScan() must precede setDecodeCallback(null) — first stop the "
                + "polling so no more in-flight emits, then null out the callback. Body:\n"
                + body,
                stopIdx >= 0 && nullIdx > stopIdx);

        // 必须重置 2s 去抖窗口 — 否则 rebuild controller 时仍沿用旧 lastEmitMs,
        // 首条相同码被去重窗口吞掉, 用户体感"扫码没反应".
        assertTrue("stopScan must reset 2s debounce (lastEmitMs=0, lastEmitCode=\"\") so "
                + "a freshly rebuilt controller does not inherit stale state. Body:\n" + body,
                body.contains("lastEmitMs = 0L") && body.contains("lastEmitCode = \"\""));
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

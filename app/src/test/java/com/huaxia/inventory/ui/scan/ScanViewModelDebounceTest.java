package com.huaxia.inventory.ui.scan;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: 高频扫描时, ScanViewModel 应将 recomputeScannedCount
 * 合并到 300ms debounce 窗口内, 避免每个新 EPC 都触发全表
 * COUNT(DISTINCT matchedAssetId) 子查询。
 *
 * <p>实现方式: {@code recomputeScannedCount} 只在 {@code recomputeStats}
 * 内部调用, 而 {@code recomputeStats} 由 {@code scheduleRecomputeStats}
 * (300ms 去抖) 调度; {@code onEpcScanned} 不再直接调 {@code recomputeScannedCount}。</p>
 */
public class ScanViewModelDebounceTest {

    @Test
    public void scannedCount_isOnlyInvokedFromDebouncedRecomputeStats() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        }

        assertTrue("ScanViewModel.java not found: " + source.getAbsolutePath(), source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 提取 onEpcScanned 方法体 (基于花括号配对, 不依赖缩进格式).
        String onEpcBody = extractMethodBody(text, "public void onEpcScanned(");
        assertFalse("onEpcScanned must not invoke recomputeScannedCount per-scan — " +
                "should be debounced into recomputeStats. Body:\n" + onEpcBody,
                onEpcBody.contains("recomputeScannedCount("));

        // recomputeScannedCount 必须位于 recomputeStats 方法体内部, 防止被移走.
        String recomputeBody = extractMethodBody(text, "private void recomputeStats()");
        assertTrue("recomputeScannedCount should still be invoked from recomputeStats — " +
                "to keep it debounced. recomputeStats body:\n" + recomputeBody,
                recomputeBody.contains("recomputeScannedCount("));
    }

    /** 返回方法签名后第一个匹配的 '{' 之后到对应 '}' 之间的内容。 */
    private static String extractMethodBody(String text, String signature) {
        int sigStart = text.indexOf(signature);
        if (sigStart < 0) {
            throw new AssertionError("signature not found: " + signature);
        }
        int braceStart = text.indexOf('{', sigStart);
        if (braceStart < 0) {
            throw new AssertionError("opening brace not found for: " + signature);
        }
        int depth = 1;
        int i = braceStart + 1;
        while (i < text.length() && depth > 0) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        if (depth != 0) {
            throw new AssertionError("unbalanced braces for: " + signature);
        }
        return text.substring(braceStart + 1, i - 1);
    }
}
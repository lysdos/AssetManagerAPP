package com.huaxia.inventory.ui.scan;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: ScanViewModel 必须有 disposed 守卫 — 在 onEpcScanned + bindToTask
 * + clearCurrentTask + recomputeStats 等 diskIO 入口检查, 防止 VM 销毁后
 * 仍有 in-flight runnable 在 diskIO 线程池持 VM 强引用, 高频扫描 + 旋转
 * 累积 10MB+ 堆残留.
 */
public class ScanViewModelDisposedGuardTest {

    @Test
    public void scanVm_hasDisposedFlag_andOnClearedSetsIt_andDiskIOEntriesCheck() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 字段声明
        assertTrue("ScanViewModel must declare disposed AtomicBoolean field. "
                + "Source:\n" + text,
                text.contains("private final java.util.concurrent.atomic.AtomicBoolean disposed")
                        || text.contains("private final AtomicBoolean disposed"));

        // onCleared 必须 set disposed true
        String onCleared = extractMethodBody(text, "protected void onCleared()");
        assertTrue(onCleared.length() > 0);
        assertTrue("onCleared must call disposed.set(true) so in-flight diskIO "
                + "lambdas short-circuit. Body:\n" + onCleared,
                onCleared.contains("disposed.set(true)"));

        // onEpcScanned 主体必须含 disposed.get() 检查
        String onEpcBody = extractMethodBody(text, "public void onEpcScanned(");
        assertTrue(onEpcBody.length() > 0);
        assertTrue("onEpcScanned must guard with disposed.get() — high-frequency "
                + "RFID callback path leaks VM otherwise. Body:\n" + onEpcBody,
                onEpcBody.contains("disposed.get()"));

        // bindToTask 必须含 disposed.get() 守卫
        String bindBody = extractMethodBody(text, "public void bindToTask(");
        assertTrue(bindBody.length() > 0);
        assertTrue("bindToTask must guard with disposed.get(). Body:\n" + bindBody,
                bindBody.contains("disposed.get()"));
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

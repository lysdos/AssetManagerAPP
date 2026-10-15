package com.huaxia.inventory.ui.result;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: {@code ResultViewModel.currentTaskId} 跨线程读写 (主线程 bindToTask 写,
 * diskIO 的 refresh()/deleteExtra() 读) 必须 {@code volatile}, 保证可见性 ——
 * 与 ScanViewModel 用 AtomicLong 同理。
 */
public class ResultViewModelVolatileTest {

    @Test
    public void currentTaskId_isVolatile() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File f = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/result/ResultViewModel.java");
        if (!f.exists()) {
            f = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/result/ResultViewModel.java");
        }
        assertTrue("source not found: " + f.getAbsolutePath(), f.exists());
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue("ResultViewModel.currentTaskId must be volatile (跨 diskIO 线程读写). "
                + "Source:\n" + text, text.contains("volatile long currentTaskId"));
    }
}

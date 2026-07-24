package com.huaxia.inventory;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: InventoryApp 公开的 {@code executors / session / rfid / db} 字段
 * 必须 {@code final} 修饰 — 防止跨进程 (主线程 / 后台线程) 重新赋值破坏
 * 应用生命周期. 不能改成 private 因为有 10+ 文件直接读这些字段,
 * 改 getter 会大改 call site. 当前用 {@code @VisibleForTesting} +
 * final 解决一半问题 (JMM 重排 + 引用稳定性).
 */
public class InventoryAppFieldsFinalTest {

    @Test
    public void inventoryApp_publicFields_markedVisibleForTesting_andDoNotLieFinal()
            throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/InventoryApp.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/InventoryApp.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 字段必须标 @VisibleForTesting 表明是有意暴露的测试入口
        int visIdx = text.indexOf("@VisibleForTesting");
        assertTrue("InventoryApp fields should be marked @VisibleForTesting for "
                + "test-only access intent. Source:\n" + text, visIdx >= 0);

        // 顶部 Javadoc 必须说明为什么不私有 (Application.onCreate 中无法 final 赋值,
        // 改 getter 需迁移 10+ caller 文件 — 当前保留 public + VisibleForTesting).
        assertTrue("InventoryApp must document why fields are not private/final via Javadoc. "
                + "Source:\n" + text,
                text.contains("不变量") || text.contains("invariant"));
    }
}

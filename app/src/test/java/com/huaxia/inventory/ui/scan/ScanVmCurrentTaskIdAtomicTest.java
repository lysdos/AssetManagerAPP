package com.huaxia.inventory.ui.scan;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: ScanViewModel.currentTaskId 必须是 AtomicLong — 跨 diskIO 4 线程
 * 共享读写, 普通 long 在 32-bit JVM 上非原子且无 happens-before. 改
 * AtomicLong 同时保证原子读写 + volatile 等价 happens-before.
 */
public class ScanVmCurrentTaskIdAtomicTest {

    @Test
    public void currentTaskId_isAtomicLong_andAllReadsGoThroughGet() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 字段声明: AtomicLong currentTaskId, 非 final long
        assertTrue("ScanViewModel must declare currentTaskId as AtomicLong. Source:\n"
                + text,
                text.contains("private final AtomicLong currentTaskId")
                        || text.contains("private final java.util.concurrent.atomic.AtomicLong currentTaskId"));
        assertTrue("currentTaskId must NOT be a plain long (would be non-atomic on "
                + "32-bit JVM and lack happens-before across threads). Source:\n"
                + text,
                !text.contains("private long currentTaskId = -1L;"));

        // 所有 currentTaskId 读取必须走 .get() — 不能直接读
        // 简单地校验: 全文不应有 {@code = currentTaskId} 这种字段直接读取
        // (排除注释等误中)。
        // 实际我们检查是不是所有 read site 都用 .get()
        // 列出所有 read site:
        // 我们用更稳的检查: 所有 "currentTaskId" 出现后, 后面应该是 "(" (.get() / .set()).
        // 简单做: 找所有形如 "long X = currentTaskId" 的模式, 必须改为 "long X = currentTaskId.get()"
        boolean hasDirectRead = false;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue;
            // 匹配 "long X = currentTaskId" 或 "if (currentTaskId == ..."
            if (trimmed.matches("\\s*long\\s+\\w+\\s*=\\s*currentTaskId\\s*;?\\s*")
                    || trimmed.matches("\\s*if\\s*\\(\\s*currentTaskId\\s*[!=]=\\s*.*")) {
                hasDirectRead = true;
                break;
            }
        }
        assertTrue("ScanViewModel must read currentTaskId only via .get(), never direct. "
                + "Detected direct read site. Source:\n" + text, !hasDirectRead);
    }
}

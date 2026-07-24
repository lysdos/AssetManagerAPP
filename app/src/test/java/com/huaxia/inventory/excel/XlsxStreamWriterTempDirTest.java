package com.huaxia.inventory.excel;

import org.junit.Test;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 防回归: XlsxStreamWriter 必须在构造时强制传入 parentTmpDir (Android 应用
 * 的 {@code context.getCacheDir()}). 修复前默认走 {@code System.getProperty(
 * "java.io.tmpdir")} — 在 Android 上落到 /sdcard 或 /data/local/tmp,
 * Scoped Storage / SAF 路径下大概率 Permission denied, 1w 行任务会导出全程崩.
 */
public class XlsxStreamWriterTempDirTest {

    @Test
    public void constructor_rejectsNullTmpDir_andNeverUsesJvmTmpdir() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/excel/XlsxStreamWriter.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/excel/XlsxStreamWriter.java");
        }
        assertTrue("XlsxStreamWriter.java not found: " + source.getAbsolutePath(),
                source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 不允许 (在代码中) 调用 System.getProperty("java.io.tmpdir") — 注释里的字面量
        // 描述仅说明 "原犯", 不影响运行时. 我们只检查有无动态读取 JVM 系统属性这条路径.
        // 用整段语句级匹配: `new File(System.getProperty` 或 `System.getProperty(..).`.
        assertTrue("XlsxStreamWriter must NOT call System.getProperty(\"java.io.tmpdir\") "
                + "at runtime — Android JVM tmpdir points to /sdcard or /data/local/tmp, "
                + "which SAF/Scoped Storage cannot write. Source:\n" + text,
                !text.contains("new File(System.getProperty(\"java.io.tmpdir\")")
                        && !text.contains("System.getProperty(\"java.io.tmpdir\"),"));

        // 必须有强制 parentTmpDir 参数的构造器.
        assertTrue("XlsxStreamWriter must declare a constructor accepting File parentTmpDir. "
                + "Source:\n" + text,
                text.contains("public XlsxStreamWriter(")
                        && text.contains("File parentTmpDir"));
    }

    @Test
    public void constructor_rejectsNullParentTmpDir() throws Exception {
        File cacheDir = Files.createTempDirectory("xlsx-test-").toFile();
        try {
            // null parentTmpDir 必须显式抛 IllegalArgumentException, 而非 NPE.
            try (OutputStream sink = new java.io.ByteArrayOutputStream()) {
                new XlsxStreamWriter(sink, Arrays.asList("col1"), null);
                fail("XlsxStreamWriter(null File) must throw, but accepted");
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        } finally {
            deleteRecursively(cacheDir);
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        // best-effort
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}

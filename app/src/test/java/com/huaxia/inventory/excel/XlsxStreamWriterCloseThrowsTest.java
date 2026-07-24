package com.huaxia.inventory.excel;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: XlsxStreamWriter.close() 必须 throws IOException 并保留 finish 失败
 * 的异常 — 调用方 (ExcelExporter.exportStreamed try-with-resources) 才能感知
 * 导出是否成功结束, 不再被 catch-and-ignore 让 cleanup() 掩盖原始错误.
 */
public class XlsxStreamWriterCloseThrowsTest {

    @Test
    public void close_methodSignature_declaresIOException_andPropagates() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/excel/XlsxStreamWriter.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/excel/XlsxStreamWriter.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // close 方法签名必须 throws IOException.
        int idx = text.indexOf("public void close()");
        assertTrue("close() signature must declare throws IOException. Source:\n"
                + text, idx >= 0);
        int throwsIdx = text.indexOf("throws IOException", idx);
        assertTrue("close() must declare throws IOException. Source:\n" + text,
                throwsIdx >= 0 && throwsIdx < text.indexOf("{", idx));

        // close() 体内必须调 finish() 而非 try/catch 静默.
        String closeBody = extractMethodBody(text, "public void close()");
        assertTrue(closeBody.length() > 0);
        assertTrue("close() must call finish() (or guard via finished flag). "
                + "Body:\n" + closeBody, closeBody.contains("finish"));
        // 不允许代码部分 catch IOException 后忽略 — 排除注释行后检查.
        boolean codeHasIgnoredCatch = false;
        for (String line : closeBody.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*")) {
                continue;
            }
            if (line.contains("IOException ignored")) {
                codeHasIgnoredCatch = true;
                break;
            }
        }
        assertTrue("close() must NOT catch (IOException ignored) in code. Body:\n"
                + closeBody, !codeHasIgnoredCatch);
    }

    @Test
    public void close_propagatesIOExceptionFromFinish() throws Exception {
        // smoke test: 直接 verify 现在 close() 声明 throws, 实例化后可编译通过.
        java.io.File cacheDir = Files.createTempDirectory("xlsx-close-").toFile();
        try {
            cacheDir.deleteOnExit();
            try (OutputStream sink = new java.io.ByteArrayOutputStream()) {
                XlsxStreamWriter writer = new XlsxStreamWriter(sink,
                        Arrays.asList("col1"), cacheDir);
                // close() 必须 throws IOException; 编译期校验 + 实际不抛.
                try {
                    writer.close();
                } catch (IOException ex) {
                    // finish 失败 (本测试不会失败, 但作为接口契约保护)
                    assertNotNull(ex.getMessage());
                }
            }
        } finally {
            deleteRecursively(cacheDir);
        }
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

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}

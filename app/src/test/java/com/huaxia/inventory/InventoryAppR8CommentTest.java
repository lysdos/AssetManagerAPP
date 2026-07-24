package com.huaxia.inventory;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: InventoryApp.checkRfidClasses 调用点的注释必须不再声称
 * "R8 minify 在 debug 开启后" — 自从 build.gradle 把 debug minifyEnabled 改回
 * false, 这条注释已过时; R8 minify 只在 release 触发, debug 是 no-op.
 */
public class InventoryAppR8CommentTest {

    @Test
    public void checkRfidClassesComment_doesNotLieAboutDebugMinify() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/InventoryApp.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/InventoryApp.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 找 checkRfidClasses 调用前的一段注释. 支持 // 单行 + /** Javadoc.
        int callIdx = text.indexOf("checkRfidClasses();");
        assertTrue(callIdx >= 0);

        StringBuilder sb = new StringBuilder();
        int scan = callIdx;
        int lineCount = 0;
        while (scan > 0 && lineCount < 30) {
            int prevNL = text.lastIndexOf('\n', scan - 1);
            if (prevNL < 0) break;
            String line = text.substring(prevNL + 1, scan).trim();
            if (!line.isEmpty()) {
                if (!line.startsWith("//") && !line.startsWith("/*") && !line.startsWith("*")
                        && !line.startsWith("/**")) {
                    break;
                }
                sb.insert(0, line + "\n");
            }
            scan = prevNL;
            lineCount++;
        }
        String commentSnippet = sb.toString();
        assertFalse("Comment block before checkRfidClasses() must NOT claim 'R8 minify "
                + "在 debug 开启后' — debug 已关 R8. Snippet:\n" + commentSnippet,
                commentSnippet.contains("R8 minify 在 debug 开启后"));
        assertTrue("Comment must clarify that R8 minify is release-only now. Snippet:\n"
                + commentSnippet,
                commentSnippet.toLowerCase().contains("release"));
    }
}

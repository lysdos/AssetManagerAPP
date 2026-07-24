package com.huaxia.inventory;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: proguard-rules.pro 的 rscja keep 段必须标注是 release-only, 与
 * {@code build.gradle: debug { minifyEnabled false }} 配套 — debug 不走 R8,
 * 不要误以为"-keep 在 debug 也需要".
 *
 * <p>另外必须含验证指引, 让维护者知道 release 出问题时如何用
 * {@code InventoryApp.checkRfidClasses} + logcat 排查.</p>
 */
public class ProguardR8KeepCommentTest {

    @Test
    public void rscjaKeepSection_documentsReleaseOnly_andVerificationPath() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir, "app/proguard-rules.pro");
        if (!source.exists()) {
            source = new File(moduleDir, "proguard-rules.pro");
        }
        assertTrue("proguard-rules.pro not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 段标题必须含 "rscja" (确认是同一段)
        assertTrue("proguard-rules.pro must contain a rscja SDK keep section. Source:\n"
                + text, text.contains("rscja UHF/Barcode SDK"));

        // 段必须标记 release-only (避免误认为 debug 也要走 R8)
        assertTrue("rscja keep section must document release-only intent (debug has "
                + "minifyEnabled false). Source:\n" + text,
                text.contains("Release-only")
                        || text.contains("release-only")
                        || text.contains("release build"));
        assertFalse("rscja keep section MUST NOT mislead to think debug is minified. "
                + "Source:\n" + text,
                text.contains("debug 也走 R8")
                        || text.contains("debug must minify"));

        // 必须有验证路径 (ClassCheck / logcat / [ClassCheck])
        assertTrue("rscja keep section must document verification path (logcat / "
                + "ClassCheck) so release-time issues can be diagnosed. Source:\n"
                + text,
                text.contains("ClassCheck") || text.contains("[ClassCheck]"));

        // 必须保持现有 keep 规则不被破坏
        for (String keep : new String[]{
                "-keep class com.rscja.deviceapi.** { *; }",
                "-keep class com.rscja.team.** { *; }",
                "-keep class com.rscja.utility.** { *; }",
                "-keep class com.rscja.CWDeviceInfo { *; }",
                "-keep class com.rscja.system.** { *; }",
                "-keep class com.rscja.barcode.** { *; }"
        }) {
            assertTrue("Required keep rule must remain: " + keep + ". Source:\n"
                    + text, text.contains(keep));
        }
    }

    @Test
    public void inventoryApp_checkRfidClasses_pointsToProguardDoc() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/InventoryApp.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/InventoryApp.java");
        }
        assertTrue("InventoryApp.java not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // checkRfidClasses 方法上的注释必须指向 proguard-rules.pro
        int idx = text.indexOf("private void checkRfidClasses()");
        assertTrue("checkRfidClasses must exist. Source:\n" + text, idx >= 0);
        int javadocStart = text.lastIndexOf("/**", idx);
        if (javadocStart < 0) {
            // 单行注释
            int lineStart = text.lastIndexOf('\n', idx - 1);
            String line = text.substring(lineStart + 1, idx).trim();
            if (!line.startsWith("//")) {
                line = text.substring(text.lastIndexOf("        // ", idx - 1) + 1, idx).trim();
            }
            assertTrue("checkRfidClasses must have a comment referencing proguard-rules.pro. "
                    + "Line:\n" + line, line.contains("proguard-rules.pro"));
        } else {
            String javadoc = text.substring(javadocStart, idx);
            assertTrue("checkRfidClasses javadoc must reference proguard-rules.pro for "
                    + "verification step. Javadoc:\n" + javadoc,
                    javadoc.contains("proguard-rules.pro"));
        }
    }
}

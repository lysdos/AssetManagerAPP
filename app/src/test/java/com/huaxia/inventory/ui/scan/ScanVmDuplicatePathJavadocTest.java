package com.huaxia.inventory.ui.scan;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: ScanViewModel.onEpcScanned 的 Javadoc 必须明确两条互斥重复路径:
 * 内存 dedup (scannedEpcSet) 与 DB UNIQUE IGNORE — 行为不同.
 *
 * <p>维护者改 onEpcScanned 时容易踩坑 (例如"为啥内存命中不写 DB"), 必须留文档.</p>
 */
public class ScanVmDuplicatePathJavadocTest {

    @Test
    public void onEpcScanned_javadocDocumentsBothPaths() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 找 onEpcScanned 方法, 取方法上的 /** Javadoc
        int idx = text.indexOf("public void onEpcScanned(String rawCode");
        assertTrue("onEpcScanned must exist. Source:\n" + text, idx >= 0);
        int javadocStart = text.lastIndexOf("/**", idx);
        assertTrue("onEpcScanned must have a Javadoc block above. Source:\n"
                + text, javadocStart >= 0);
        String javadoc = text.substring(javadocStart, idx);

        // 必须明确两条路径
        assertTrue("Javadoc must mention 内存层去重 / scannedEpcSet 路径. "
                + "Javadoc:\n" + javadoc,
                javadoc.contains("内存") || javadoc.contains("scannedEpcSet"));
        assertTrue("Javadoc must mention DB 层去重 / UNIQUE IGNORE 路径. "
                + "Javadoc:\n" + javadoc,
                javadoc.contains("DB") && (javadoc.contains("UNIQUE")
                        || javadoc.contains("IGNORE")));
        assertTrue("Javadoc must mention makeUnmatchedRecord 占位 record "
                + "(UI 不带 taskId/matchedAssetId). Javadoc:\n" + javadoc,
                javadoc.contains("makeUnmatchedRecord"));
    }
}

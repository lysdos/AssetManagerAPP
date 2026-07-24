package com.huaxia.inventory.ui.scan;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: ScanViewModel.bindToTask 必须输出 in-memory EPC 去重集合大小的 log,
 * 让 QA / 运维在真机上看到 10w+ EPC 任务的内存占用. 不强制截断 (截断会漏检).
 */
public class ScanViewModelSetSizeLogTest {

    @Test
    public void bindToTask_logsScannedEpcSetSize() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        String body = extractMethodBody(text, "public void bindToTask(");
        assertTrue("bindToTask body not found", body.length() > 0);

        // bindToTask 体内必须有 explicit Log.i/w 行提到 loaded/loaded EPC 数量.
        assertTrue("bindToTask must emit a log line reporting scanned EPC count for ops "
                + "visibility (10w+ tasks → ~5MB+ heap). Body:\n" + body,
                body.contains("Log.i(")
                        && body.contains("loaded")
                        && body.contains("existingEpcs"));
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

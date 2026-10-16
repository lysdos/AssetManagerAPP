package com.huaxia.inventory.ui.scan;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: "重置扫描进度" = 放弃本次盘点重来 —— 回滚字段 + 清盘点时间, 但
 * <b>保留快照</b> (任务还在, 之后删除任务仍能回滚), 且只能有一个 runInTransaction。
 *
 * <p>误加 {@code clearSnapshotForTask} → 重置后快照没了, 之后删除该任务无法回滚;
 * 多一个 {@code runInTransaction} → 违反 {@code ScanViewModelTransactionTest} 的单事务契约。</p>
 */
public class ResetScanProgressKeepsSnapshotTest {

    @Test
    public void resetScanProgress_rollsBackFieldsAndClearsTime_keepsSnapshot() throws Exception {
        String text = read("src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        String body = extractMethodBody(text, "public void resetScanProgress(");
        assertTrue("resetScanProgress body not found. Source:\n" + text, body.length() > 0);

        assertTrue("resetScanProgress must revert edited fields. Body:\n" + body,
                body.contains("revertEditedFields("));
        assertTrue("resetScanProgress must clear inventory_time. Body:\n" + body,
                body.contains("clearInventoryTimeForTask("));
        assertEquals("resetScanProgress must not add a second runInTransaction. Body:\n" + body,
                1, countOccurrences(body, "runInTransaction("));
        assertFalse("resetScanProgress must KEEP the snapshot (task still alive). Body:\n" + body,
                body.contains("clearSnapshotForTask("));
    }

    private static String read(String rel) throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File f = new File(moduleDir, rel);
        if (!f.exists()) f = new File(moduleDir, "app/" + rel);
        assertTrue("source not found: " + f.getAbsolutePath(), f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
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

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}

package com.huaxia.inventory.ui.scan;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: ScanViewModel.clearCurrentTask 在磁盘事务后必须立刻
 * {@code app.getRfid().stopInventory()}. 修复前只删三表 + 清 session,
 * 但 rfid-poll 守护线程仍在 while-loop 持续 emit onEpcScanned →
 * 读 currentTaskId=-1L → findByEpc + insert 写 taskId=-1L 孤儿
 * ScanRecord, 永远不在 WHERE taskId=:tid 视图里, 污染统计.
 */
public class ClearCurrentTaskStopsRfidTest {

    @Test
    public void clearCurrentTask_body_invokesStopInventory() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        String body = extractMethodBody(text, "public void clearCurrentTask()");
        assertTrue("clearCurrentTask body not found", body.length() > 0);

        // 必须出现 app.getRfid().stopInventory() 调用, 且位于 diskIO lambda 内 (db.runInTransaction 之后)
        int runTxIdx = body.indexOf("app.getDb().runInTransaction(");
        int stopIdx = body.indexOf("app.getRfid().stopInventory()");
        assertTrue("clearCurrentTask must call app.getRfid().stopInventory() inside diskIO "
                + "lambda (after runInTransaction). Body:\n" + body,
                runTxIdx >= 0 && stopIdx > runTxIdx);
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

package com.huaxia.inventory.ui.manual;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: ManualScanViewModel 的 confirmCheck 与 onBarcodeScanned
 * 必须把"查找 Asset + 写 ScanRecord"两步包进 {@code app.getDb().runInTransaction},
 * 与 ScanViewModel.onEpcScanned 风格一致, 防并发 (RFID 自动扫描 + 手动确认)
 * 同时抢同一 EPC 时 ScanRecord UNIQUE 约束触发 / matchedAssetId 漂移到 stale id.
 */
public class ManualScanViewModelTransactionTest {

    @Test
    public void confirmCheck_findByEpcAndInsertInsideSingleTransaction() throws Exception {
        // confirmCheck 的 Asset 由 caller (UI) 传入, 不需要在事务里再查 assetDao.findByEpc;
        // 但 ScanRecord 的去重检查必须在事务内重做, 避免 UI 选中并发插入同 EPC.
        assertSingleTransactionContaining(
                "src/main/java/com/huaxia/inventory/ui/manual/ManualScanViewModel.java",
                "public void confirmCheck(",
                "scanRecordDao().insert",
                "scanRecordDao().findByEpc",
                null);
    }

    @Test
    public void onBarcodeScanned_findByEpcAndInsertInsideSingleTransaction() throws Exception {
        assertSingleTransactionContaining(
                "src/main/java/com/huaxia/inventory/ui/manual/ManualScanViewModel.java",
                "public void onBarcodeScanned(",
                "scanRecordDao().insert",
                "assetDao().findByEpc",
                null);
    }

    private static void assertSingleTransactionContaining(String path, String signature,
                                                           String mustInsertCall,
                                                           String mustContain,
                                                           String mustAlsoContainOrNull)
            throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir, path);
        if (!source.exists()) {
            source = new File(moduleDir, "app/" + path);
        }
        assertTrue("source not found: " + source.getAbsolutePath(), source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        String body = extractMethodBody(text, signature);
        assertTrue(signature + " body not found.\nSource:\n" + text, body.length() > 0);

        assertEquals(signature + " must invoke runInTransaction( exactly once. Body:\n" + body,
                1, countOccurrences(body, "runInTransaction("));

        String lambdaBody = extractFollowingLambdaBody(body, body.indexOf("runInTransaction"));
        assertTrue("Could not extract runInTransaction lambda for " + signature,
                lambdaBody.length() > 0);

        assertTrue(mustInsertCall + " must be inside runInTransaction lambda. Lambda:\n"
                + lambdaBody, lambdaBody.contains(mustInsertCall));
        if (mustContain != null) {
            assertTrue(mustContain + " must be inside runInTransaction lambda. Lambda:\n"
                    + lambdaBody, lambdaBody.contains(mustContain));
        }
        if (mustAlsoContainOrNull != null) {
            assertTrue(mustAlsoContainOrNull + " must also be inside runInTransaction lambda. "
                    + "Lambda:\n" + lambdaBody, lambdaBody.contains(mustAlsoContainOrNull));
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

    private static String extractFollowingLambdaBody(String text, int startIdx) {
        int arrowIdx = text.indexOf("->", startIdx);
        if (arrowIdx < 0) return "";
        int braceStart = text.indexOf('{', arrowIdx);
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

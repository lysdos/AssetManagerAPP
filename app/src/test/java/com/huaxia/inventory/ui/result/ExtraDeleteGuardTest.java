package com.huaxia.inventory.ui.result;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: 单条删除盘盈记录.
 * <ul>
 *   <li>{@code ScanRecordDao.deleteExtraById} 必须带 {@code matchedAssetId IS NULL}
 *       守卫 — 只允许删盘盈, 不能误删已盘到的记录;</li>
 *   <li>{@code ResultViewModel.deleteExtra} 单事务删 ExtraClaim + ScanRecord,
 *       不碰 Asset, 并广播 {@code notifyScanRemoved} 让扫描页驱逐内存去重集;</li>
 *   <li>{@code ScanViewModel.onScanRemoved} 必须把 EPC 从 {@code scannedEpcSet}
 *       移除, 否则同标签重扫被"已见过"挡住、不落库.</li>
 * </ul>
 */
public class ExtraDeleteGuardTest {

    @Test
    public void scanRecordDao_deleteExtraById_guardsOnMatchedAssetNull() throws Exception {
        String text = readFile("src/main/java/com/huaxia/inventory/data/dao/ScanRecordDao.java");
        String body = extractMethodBody(text, "int deleteExtraById(");
        assertTrue("deleteExtraById body not found. Source:\n" + text, body.length() > 0);
        // 注解在方法签名之前, 取方法体不含注解 — 改为在方法上方片段里找守卫.
        int sig = text.indexOf("int deleteExtraById(");
        String above = text.substring(Math.max(0, sig - 260), sig);
        assertTrue("deleteExtraById must guard on matchedAssetId IS NULL. Above:\n" + above,
                above.contains("matchedAssetId IS NULL"));
    }

    @Test
    public void deleteExtra_deletesClaimAndScan_inSingleTransaction_noAsset() throws Exception {
        String text = readFile("src/main/java/com/huaxia/inventory/ui/result/ResultViewModel.java");
        String body = extractMethodBody(text, "public void deleteExtra(");
        assertTrue("deleteExtra body not found. Source:\n" + text, body.length() > 0);

        assertEquals("deleteExtra must invoke runInTransaction( exactly once. Body:\n" + body,
                1, countOccurrences(body, "runInTransaction("));

        String lambdaBody = extractFollowingLambdaBody(body, body.indexOf("runInTransaction"));
        assertTrue("Could not extract runInTransaction lambda body", lambdaBody.length() > 0);
        assertTrue("extraClaimDao().deleteByScanRecordId must be inside transaction. Lambda:\n"
                + lambdaBody, lambdaBody.contains("extraClaimDao().deleteByScanRecordId"));
        assertTrue("scanRecordDao().deleteExtraById must be inside transaction. Lambda:\n"
                + lambdaBody, lambdaBody.contains("scanRecordDao().deleteExtraById"));

        // 不碰资产 — 盘盈行 matchedAssetId 恒为 NULL.
        assertFalse("deleteExtra must NOT touch Asset. Body:\n" + body,
                body.contains("assetDao"));
        // 必须广播, 让扫描页驱逐内存去重集.
        assertTrue("deleteExtra must notifyScanRemoved. Body:\n" + body,
                body.contains("notifyScanRemoved"));
    }

    @Test
    public void scanViewModel_onScanRemoved_evictsFromDedupSet() throws Exception {
        String text = readFile("src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        String body = extractMethodBody(text, "private void onScanRemoved(");
        assertTrue("onScanRemoved body not found. Source:\n" + text, body.length() > 0);
        assertTrue("onScanRemoved must remove EPC from scannedEpcSet. Body:\n" + body,
                body.contains("scannedEpcSet.remove("));
    }

    private static String readFile(String rel) throws Exception {
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

package com.huaxia.inventory.ui.scan;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: ScanViewModel 的两个写路径 {@code onEpcScanned} 和
 * {@code clearCurrentTask} 必须把多步 DAO 调用包进单个
 * {@code app.db.runInTransaction(...)} lambda, 防并发/进程被杀时留下
 * "matchedAssetId 漂移 / 孤儿 Asset / ScanRecord 未删".
 */
public class ScanViewModelTransactionTest {

    @Test
    public void onEpcScanned_findByEpcAndInsertInsideSingleTransaction() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        }
        assertTrue("ScanViewModel.java not found: " + source.getAbsolutePath(),
                source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        String onEpcBody = extractMethodBody(text, "public void onEpcScanned(");
        assertTrue("onEpcScanned body not found.", onEpcBody.length() > 0);

        // 必须在 onEpcScanned 体内执行 (非注释) runInTransaction 恰好 1 次.
        // 注释里出现 "runInTransaction" 字符串是允许的 (解释意图);
        // 真正调用用 "runInTransaction(" (带左括号) 这一形态来识别.
        assertTrue("onEpcScanned must call runInTransaction. Body:\n" + onEpcBody,
                onEpcBody.contains("runInTransaction("));
        assertEquals("onEpcScanned must invoke runInTransaction( exactly once. "
                + "Body:\n" + onEpcBody,
                1, countOccurrences(onEpcBody, "runInTransaction("));

        // findByEpc + insert 必须在 runInTransaction lambda 内.
        String lambdaBody = extractFollowingLambdaBody(onEpcBody,
                onEpcBody.indexOf("runInTransaction"));
        assertTrue("Could not extract runInTransaction lambda body", lambdaBody.length() > 0);
        assertTrue("findByEpc must be inside the runInTransaction lambda. Lambda:\n"
                + lambdaBody, lambdaBody.contains("findByEpc"));
        assertTrue("scanRecordDao().insert must be inside the runInTransaction lambda. "
                + "Lambda:\n" + lambdaBody, lambdaBody.contains("scanRecordDao().insert"));
    }

    @Test
    public void clearCurrentTask_threeDeleteStepsInsideSingleTransaction() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        }
        assertTrue("ScanViewModel.java not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        String body = extractMethodBody(text, "public void clearCurrentTask()");
        assertTrue("clearCurrentTask body not found", body.length() > 0);

        assertEquals("clearCurrentTask must invoke app.db.runInTransaction( exactly once. "
                + "Body:\n" + body,
                1, countOccurrences(body, "runInTransaction("));

        String lambdaBody = extractFollowingLambdaBody(body, body.indexOf("runInTransaction"));
        assertTrue(lambdaBody.length() > 0);
        assertTrue("scanRecordDao().deleteByTask must be inside transaction. Lambda:\n"
                + lambdaBody, lambdaBody.contains("scanRecordDao().deleteByTask"));
        assertTrue("assetDao().deleteByTask must be inside transaction. Lambda:\n"
                + lambdaBody, lambdaBody.contains("assetDao().deleteByTask"));
        assertTrue("inventoryTaskDao().deleteById must be inside transaction. Lambda:\n"
                + lambdaBody, lambdaBody.contains("inventoryTaskDao().deleteById"));
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

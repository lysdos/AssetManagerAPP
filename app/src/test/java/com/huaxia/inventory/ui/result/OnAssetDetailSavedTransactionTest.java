package com.huaxia.inventory.ui.result;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: ResultFragment.onAssetDetailSaved 必须把"precheck findByEpcGlobal
 * + inventoryTime 计算 + update" 包到 {@code db.runInTransaction}, 防并发
 * (a) 其它路径改写其它字段; (b) backfillFoundInventoryTime 与
 * pendingSave.inventoryTime 并发写入同一行不一致.
 */
public class OnAssetDetailSavedTransactionTest {

    @Test
    public void onAssetDetailSaved_packsCheckAndUpdateIntoSingleTransaction() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        String body = extractMethodBody(text, "private void onAssetDetailSaved(");
        assertTrue("onAssetDetailSaved body not found", body.length() > 0);

        assertEquals("onAssetDetailSaved must invoke db.runInTransaction( exactly once. "
                + "Body:\n" + body,
                1, countOccurrences(body, ".runInTransaction("));

        String lambdaBody = extractFollowingLambdaBody(body, body.indexOf(".runInTransaction("));
        assertTrue("Could not extract runInTransaction lambda body", lambdaBody.length() > 0);

        assertTrue("findByEpcGlobal must be inside the runInTransaction lambda. Lambda:\n"
                + lambdaBody, lambdaBody.contains("findByEpcGlobal"));
        assertTrue("assetDao().update must be inside the runInTransaction lambda. Lambda:\n"
                + lambdaBody, lambdaBody.contains("assetDao().update"));
        assertTrue("findFirstScanTime must be inside the runInTransaction lambda. Lambda:\n"
                + lambdaBody, lambdaBody.contains("findFirstScanTime"));
    }

    @Test
    public void onAssetDetailSaved_catchesSqliteConstraintExceptionAsDuplicate() throws Exception {
        // SQLiteConstraintException 在事务边界外跨并发窗口触发, 必须路由到 completion.onDuplicateEpc().
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        String body = extractMethodBody(text, "private void onAssetDetailSaved(");
        assertTrue("onAssetDetailSaved body not found", body.length() > 0);

        assertTrue("onAssetDetailSaved must catch SQLiteConstraintException and route "
                + "to completion.onDuplicateEpc (TOCTOU protection). Body:\n" + body,
                body.contains("SQLiteConstraintException")
                        && body.contains("onDuplicateEpc"));
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

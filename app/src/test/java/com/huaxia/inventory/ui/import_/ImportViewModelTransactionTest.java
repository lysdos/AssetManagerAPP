package com.huaxia.inventory.ui.import_;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: ImportViewModel.commitToTask 的 onBatch 必须把
 * {@code findExistingEpcs + upsertBatch} 两步包到 {@code db.runInTransaction},
 * 否则 diskIO 队列里其他任务 (例如 refreshStats) 在两步之间插入新 Asset 时
 * 会让 updated 计数与实际 ON CONFLICT DO UPDATE 覆盖数偏差.
 */
public class ImportViewModelTransactionTest {

    @Test
    public void commitToTask_findExistingEpcsAndUpsertInsideSingleTransaction() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/import_/ImportViewModel.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/import_/ImportViewModel.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // commitToTask 内 onBatch 走 lambda — 关注这个 lambda 体内的关键调用, 不强求
        // 具体方法和顺序.
        String commitBody = extractMethodBody(text, "public void commitToTask(");
        assertTrue("commitToTask body not found", commitBody.length() > 0);

        // 必须出现在某个 onBatch lambda 内: runInTransaction + findExistingEpcs + upsertBatch.
        // 这里只检查 runInTransaction 直接出现在 commitToTask 的方法体内即可
        // (分支包 lambda, 我们看 onBatch lambda).
        int lambdaIdx = commitBody.indexOf("db.runInTransaction(");
        assertTrue("commitToTask must invoke db.runInTransaction(...) — finds+upsert "
                + "must be in same transaction. Body:\n" + commitBody,
                lambdaIdx >= 0);
        int dbRunInTxCount = countOccurrences(commitBody, "db.runInTransaction(");
        assertTrue("commitToTask must invoke db.runInTransaction( exactly once. Body:\n"
                + commitBody,
                dbRunInTxCount == 1);

        // lambda 体必须包含 findExistingEpcs 与 upsertBatch 两个调用.
        String lambdaBody = extractFollowingLambdaBody(commitBody, lambdaIdx);
        assertTrue("Could not extract runInTransaction lambda body", lambdaBody.length() > 0);
        assertTrue("findExistingEpcs must be inside the runInTransaction lambda. Lambda:\n"
                + lambdaBody, lambdaBody.contains("findExistingEpcs"));
        assertTrue("upsertBatch must be inside the runInTransaction lambda. Lambda:\n"
                + lambdaBody, lambdaBody.contains("upsertBatch"));
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

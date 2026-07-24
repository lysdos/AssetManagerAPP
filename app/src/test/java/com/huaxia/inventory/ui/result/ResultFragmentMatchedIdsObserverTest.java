package com.huaxia.inventory.ui.result;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: ResultFragment 的 found id 集合 ({@code matchedIdsCache}) 必须由
 * {@link ResultViewModel#matchedIds()} LiveData 通过 observer 喂入,
 * 不再在 assets observer 内手动 dispatch {@code assetDao().findFound(taskId)}
 * 全表加载 — 后者导致 (1) 每次翻页重跑全表;
 * (2) UI 线程 / diskIO 线程并发读写 matchedIdsCache 非线程安全.
 */
public class ResultFragmentMatchedIdsObserverTest {

    @Test
    public void fragmentObservesVmMatchedIds_andDoesNotDispatchFindFound() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // onViewCreated 中必须 observe vm.matchedIds().
        assertTrue("ResultFragment.onViewCreated must observe vm.matchedIds() to receive "
                + "found-id set lazily from ViewModel. Source:\n" + text,
                text.contains("vm.matchedIds().observe("));

        // onViewCreated 内不允许出现 "assetDao().findFound" — findFound 必须只在
        // ViewModel.refresh / bindToTask 内调用.
        String onViewBody = extractMethodBody(text, "public void onViewCreated(");
        assertTrue("onViewCreated body not found", onViewBody.length() > 0);
        assertFalse("ResultFragment.onViewCreated must NOT call assetDao().findFound — "
                + "that keeps the Fragment off the IO-critical path. Body:\n" + onViewBody,
                onViewBody.contains("assetDao().findFound("));

        // assets observer 内也不再调度 assetDao().findFound.
        // 这条断言通过 brace 配对提取整个 vm.assets().observe 的回调 lambda 体;
        // 那个 lambda 体内不允许出现 assetDao().findFound.
        int observerIdx = text.indexOf("vm.assets().observe(");
        assertTrue("vm.assets().observe(...) callback must exist. Source:\n" + text,
                observerIdx >= 0);
        String assetsLambdaBody = extractFollowingLambdaBody(text, observerIdx);
        assertTrue(assetsLambdaBody.length() > 0);
        assertFalse("vm.assets().observe callback must NOT call assetDao().findFound — "
                + "observer body:\n" + assetsLambdaBody,
                assetsLambdaBody.contains("assetDao().findFound("));
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
}

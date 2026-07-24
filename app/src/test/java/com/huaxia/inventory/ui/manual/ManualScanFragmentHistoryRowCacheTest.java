package com.huaxia.inventory.ui.manual;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: ManualScanFragment.renderHistory 必须按 ScanRecord.id 复用
 * inflate 出来的 row view. 修复前每次 refreshHistory 都 removeAllViews +
 * 逐条 inflate + 4 次 findViewById, 10 条历史 ~200ms 不可接受的 jank.
 *
 * <p>缓存池在 onDestroyView 清空, 防止下次 onViewCreated 重建 historyList
 * 后 addView 旧 view 抛 IllegalStateException.</p>
 */
public class ManualScanFragmentHistoryRowCacheTest {

    @Test
    public void renderHistory_usesRowPool_andClearedOnDestroyView() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/manual/ManualScanFragment.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/manual/ManualScanFragment.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 必须有 historyRowPool 字段
        assertTrue("ManualScanFragment must declare a historyRowPool field keyed by "
                + "ScanRecord.id for view reuse. Source:\n" + text,
                text.contains("historyRowPool"));

        // renderHistory 必须先 historyRowPool.get(), 否则 inflate 一次缓存
        String renderBody = extractMethodBody(text,
                "private void renderHistory(");
        assertTrue(renderBody.length() > 0);
        assertTrue("renderHistory must check historyRowPool before inflating. "
                + "Body:\n" + renderBody, renderBody.contains("historyRowPool.get("));
        assertTrue("renderHistory must cache newly inflated rows into historyRowPool. "
                + "Body:\n" + renderBody, renderBody.contains("historyRowPool.put("));

        // onDestroyView 必须清空缓存
        String destroyBody = extractMethodBody(text,
                "public void onDestroyView()");
        assertTrue(destroyBody.length() > 0);
        assertTrue("onDestroyView must clear historyRowPool — old cached rows' parent "
                + "LinearLayout has been detached, addView next time throws "
                + "IllegalStateException. Body:\n" + destroyBody,
                destroyBody.contains("historyRowPool.clear()"));
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

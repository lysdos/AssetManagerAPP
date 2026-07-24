package com.huaxia.inventory.ui.result;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: ResultFragment.doExport 在切到 excelIO 线程之前必须 cache
 * {@code Context} / {@code ContentResolver} / {@code File cacheDir} — SAF
 * 导出 5s+ 期间用户切底部 nav 导致 fragment detach, 否则已 dispatch 的
 * lambda 调用 requireContext() 抛 ISE, 半写文件被 GC 截断.
 *
 * <p>同时 success / error 分支都需双层 isAdded() 守卫 + getView() null 检查.</p>
 */
public class ResultFragmentDoExportCtxTest {

    @Test
    public void doExport_cachesContext_andHasDoubleIsAddedGuards() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        String body = extractMethodBody(text, "private void doExport(");
        assertTrue(body.length() > 0);

        // 必须在切到 excelIO 之前 cache Context
        assertTrue("doExport must cache Context before excelIO dispatch. Body:\n"
                + body, body.contains("final android.content.Context ctx = requireContext()"));
        assertTrue("doExport must cache ContentResolver from cached Context. Body:\n"
                + body, body.contains("ContentResolver resolver"));
        assertTrue("doExport must cache File cacheDir (getCacheDir()). Body:\n"
                + body, body.contains("File cacheDir"));

        // excelIO lambda 内必须用 cached ctx, 不能 requireContext()
        // 通过查找 lambda 体内是否存在 "requireContext()" 字符串来验证
        int executorIdx = body.indexOf("executors.excelIO.execute(");
        int requireContextIdx = body.indexOf("requireContext()", executorIdx);
        // 允许在 cache 段 (ctx 定义前) 出现 requireContext — 只检查 cache 之后是否还有
        int cachedCtxIdx = body.indexOf("requireContext()", body.indexOf("ctx.getCacheDir"));
        assertTrue("After caching Context, the excelIO lambda MUST NOT call "
                + "requireContext() again — only use cached ctx. Body:\n" + body,
                cachedCtxIdx < 0);

        // success / error 分支 runOnUiThread 内必须有双层 isAdded() 守卫
        // 我们只校验 lambda 体内 isAdded() 出现至少 4 次 (外 + 内 2 个 + 另一个分支外 + 内 2 个 = 4)
        int isAddedIdx = body.indexOf("isAdded()");
        int count = 0;
        while (isAddedIdx != -1) {
            count++;
            isAddedIdx = body.indexOf("isAdded()", isAddedIdx + 1);
        }
        assertTrue("doExport lambda must have multiple isAdded() guards in success and "
                + "error branches (>=4). Body:\n" + body, count >= 4);
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

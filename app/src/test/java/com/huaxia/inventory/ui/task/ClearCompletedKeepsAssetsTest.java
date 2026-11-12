package com.huaxia.inventory.ui.task;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归 (口径统一): "清除已完成"必须与"删除任务"一致 —— 资产**退回库存池**保留,
 * 而不是连同资产一起删除 (库存池是资产主表, 清完成不应销毁主数据)。
 *
 * <p>同时: 结果页支持"查看指定任务"(ARG_VIEW_TASK_ID), 让已完成任务的结果可查看/导出。</p>
 */
public class ClearCompletedKeepsAssetsTest {

    @Test
    public void clearCompleted_releasesAssetsToPool_notDelete() throws Exception {
        String text = readFile("src/main/java/com/huaxia/inventory/service/TaskService.java");
        String body = extractMethodBody(text, "public void clearCompleted()");
        assertTrue("clearCompleted body not found. Source:\n" + text, body.length() > 0);
        assertTrue("clearCompleted must release assets back to the pool (口径与 deleteTask 一致). "
                + "Body:\n" + body, body.contains("assetDao().releaseTaskToPool("));
        assertFalse("clearCompleted must NOT delete the completed tasks' assets. Body:\n" + body,
                body.contains("assetDao().deleteByTask("));
    }

    @Test
    public void resultFragment_supportsViewTaskIdArg() throws Exception {
        String text = readFile("src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java");
        assertTrue("ResultFragment must declare ARG_VIEW_TASK_ID. Source:\n" + text,
                text.contains("ARG_VIEW_TASK_ID"));
        assertTrue("ResultFragment must read the viewTaskId arg. Source:\n" + text,
                text.contains("getLong(ARG_VIEW_TASK_ID"));
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
}

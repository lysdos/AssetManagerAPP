package com.huaxia.inventory.ui.scan;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: 任务卡"查看详情" → 扫描页<b>只读查看模式</b>。
 *
 * <p>{@code ScanFragment} 由 {@code ARG_VIEW_TASK_ID >= 0} 判定只读: 不绑 session、不启 RFID、
 * 禁用写操作 (扫描 / 手动盘点 / 重置); 统计卡片下钻结果页时带上 taskId (结果页也只读)。
 * 入口在 {@code TaskListFragment.onViewTask}。</p>
 */
public class ScanViewModeGuardTest {

    @Test
    public void scanFragment_supportsReadOnlyViewMode() throws Exception {
        String text = read("src/main/java/com/huaxia/inventory/ui/scan/ScanFragment.java");
        assertTrue("ScanFragment must declare ARG_VIEW_TASK_ID. Source:\n" + text,
                text.contains("ARG_VIEW_TASK_ID"));
        assertTrue("readOnly must derive from viewTaskId. Source:\n" + text,
                text.contains("readOnly = viewTaskId >= 0L"));

        String onView = extractMethodBody(text, "public void onViewCreated(");
        assertTrue("onViewCreated body not found. Source:\n" + text, onView.length() > 0);
        assertTrue("read-only must disable manual scan. Body:\n" + onView,
                onView.contains("manualScanBtn.setEnabled(false)"));
        assertTrue("read-only must disable reset progress. Body:\n" + onView,
                onView.contains("clearTaskBtn.setEnabled(false)"));

        String onResume = extractMethodBody(text, "public void onResume(");
        assertTrue("onResume must skip RFID in read-only mode. Body:\n" + onResume,
                onResume.contains("if (readOnly)"));
        assertTrue("onPause must skip RFID teardown in read-only mode. Source:\n" + text,
                text.contains("if (!readOnly)"));

        assertTrue("result drilldown must pass viewTaskId. Source:\n" + text,
                text.contains("navigateToResult(ResultTab.ALL, viewTaskId)"));
    }

    @Test
    public void taskListFragment_viewTask_opensScanViewMode() throws Exception {
        String text = read("src/main/java/com/huaxia/inventory/ui/task/TaskListFragment.java");
        assertTrue("onViewTask must open scan view mode. Source:\n" + text,
                text.contains("navigateToScanView(task.id)"));
        assertTrue("navigateToScanView must pass ScanFragment.ARG_VIEW_TASK_ID. Source:\n" + text,
                text.contains("ScanFragment.ARG_VIEW_TASK_ID"));
    }

    private static String read(String rel) throws Exception {
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

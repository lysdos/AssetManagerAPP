package com.huaxia.inventory;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: "查看已完成任务"的上下文必须跨底部 tab 保持。
 *
 * <p>底部 tab 新建的是<b>无参</b> fragment ({@code new ScanFragment()} / {@code new ResultFragment()}),
 * 参数里的 {@code ARG_VIEW_TASK_ID} 会丢。修法: 把查看中的任务存进 {@code InventorySession}
 * ({@code viewingTaskId}), 扫描 / 结果页在无参时回落到它; 出处在任务页"查看详情", 进任务页时清除。</p>
 */
public class ViewTaskContextGuardTest {

    @Test
    public void inventorySession_exposesViewingTaskId() throws Exception {
        String text = read("src/main/java/com/huaxia/inventory/session/InventorySession.java");
        assertTrue("InventorySession must expose getViewingTaskId(). Source:\n" + text,
                text.contains("getViewingTaskId()"));
        assertTrue("InventorySession must expose setViewingTaskId(). Source:\n" + text,
                text.contains("setViewingTaskId("));
    }

    @Test
    public void scanAndResultFragments_fallBackToSessionViewingTask() throws Exception {
        assertTrue("ScanFragment must fall back to session.getViewingTaskId(). Source:\n"
                        + read("src/main/java/com/huaxia/inventory/ui/scan/ScanFragment.java"),
                read("src/main/java/com/huaxia/inventory/ui/scan/ScanFragment.java")
                        .contains("getViewingTaskId()"));
        assertTrue("ResultFragment must fall back to session.getViewingTaskId(). Source:\n"
                        + read("src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java"),
                read("src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java")
                        .contains("getViewingTaskId()"));
    }

    @Test
    public void taskListSetsViewingTask_andMainActivityClearsOnTaskTab() throws Exception {
        String task = read("src/main/java/com/huaxia/inventory/ui/task/TaskListFragment.java");
        assertTrue("任务卡\"查看详情\" must set session view task. Source:\n" + task,
                task.contains("setViewingTaskId("));
        String main = read("src/main/java/com/huaxia/inventory/MainActivity.java");
        assertTrue("进入任务 tab must clear the view context. Source:\n" + main,
                main.contains("setViewingTaskId(null)"));
    }

    private static String read(String rel) throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File f = new File(moduleDir, rel);
        if (!f.exists()) f = new File(moduleDir, "app/" + rel);
        assertTrue("source not found: " + f.getAbsolutePath(), f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}

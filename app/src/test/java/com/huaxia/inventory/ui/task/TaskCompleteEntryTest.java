package com.huaxia.inventory.ui.task;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: 任务页必须有"完成任务"入口 (把 status 置 COMPLETED)。
 * <p>此前全项目没有任何地方把任务置为 COMPLETED, 导致"清除已完成"按钮与"已完成"
 * 统计都是空壳 —— 补上这个入口它们才有意义。</p>
 */
public class TaskCompleteEntryTest {

    @Test
    public void completeTask_setsCompleted_andClearsSession() throws Exception {
        // 事务体已抽到 TaskService —— 状态翻转与 session 清理在这里断言.
        String text = readFile("src/main/java/com/huaxia/inventory/service/TaskService.java");
        String body = extractMethodBody(text, "public void completeTask(");
        assertTrue("completeTask body not found. Source:\n" + text, body.length() > 0);
        assertTrue("completeTask must set status to COMPLETED. Body:\n" + body,
                body.contains("t.status = \"COMPLETED\""));
        assertTrue("completeTask must clear the session when the task is current. Body:\n" + body,
                body.contains("session.clear()"));

        // try/catch 仍在 ViewModel 的 diskIO lambda 内 —— 异常不得静默吞掉.
        String vm = readFile("src/main/java/com/huaxia/inventory/ui/task/TaskListViewModel.java");
        String vmBody = extractMethodBody(vm, "public void completeTask(");
        assertTrue("TaskListViewModel.completeTask body not found", vmBody.length() > 0);
        assertTrue("completeTask must wrap the diskIO lambda in try/catch. Body:\n" + vmBody,
                vmBody.contains("try {") && vmBody.contains("catch (Exception"));
    }

    @Test
    public void taskMoreMenu_declaresCompleteItem() throws Exception {
        String menu = readFile("src/main/res/menu/popup_task_more.xml");
        assertTrue("popup_task_more.xml must declare menu_task_complete. Source:\n" + menu,
                menu.contains("@+id/menu_task_complete"));
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

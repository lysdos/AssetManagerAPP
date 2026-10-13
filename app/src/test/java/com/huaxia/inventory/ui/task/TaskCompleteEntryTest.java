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
        String text = readFile("src/main/java/com/huaxia/inventory/ui/task/TaskListViewModel.java");
        String body = extractMethodBody(text, "public void completeTask(");
        assertTrue("completeTask body not found. Source:\n" + text, body.length() > 0);
        assertTrue("completeTask must set status to COMPLETED. Body:\n" + body,
                body.contains("t.status = \"COMPLETED\""));
        assertTrue("completeTask must clear the session when the task is current. Body:\n" + body,
                body.contains("getSession().clear()"));
        assertTrue("completeTask must wrap the diskIO lambda in try/catch. Body:\n" + body,
                body.contains("try {") && body.contains("catch (Exception"));
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

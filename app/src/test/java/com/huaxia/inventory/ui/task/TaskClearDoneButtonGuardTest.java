package com.huaxia.inventory.ui.task;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: 没有已完成任务时, 任务页底部的「清除已完成」按钮必须禁用。
 *
 * <p>历史问题: 按钮永远可点 —— 一个已完成任务都没有时点它, 会弹"确定清除所有已完成
 * 任务？"确认框, 确认后什么都不发生 (没有已完成任务可清), 用户会以为操作没生效。</p>
 *
 * <p>按钮态由 {@code renderTasks} 按已完成任务数设置, 属 UI 行为, 单测跑不到,
 * 只能靠源码守卫钉住。</p>
 */
public class TaskClearDoneButtonGuardTest {

    @Test
    public void renderTasks_disablesClearDoneButton_whenNothingCompleted() throws Exception {
        String text = read("src/main/java/com/huaxia/inventory/ui/task/TaskListFragment.java");
        String body = extractMethodBody(text, "private void renderTasks(");
        assertTrue("renderTasks body not found. Source:\n" + text, body.length() > 0);
        assertTrue("renderTasks must disable the clear-completed button when there is nothing "
                + "completed (done == 0) — otherwise tapping it pops a confirm dialog that "
                + "silently does nothing. Body:\n" + body,
                body.contains("clearDoneBtn.setEnabled(done > 0)"));
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

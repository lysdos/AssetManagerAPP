package com.huaxia.inventory.ui.task;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: TaskListViewModel.createTask / deleteTask / clearCompleted 三处
 * diskIO lambda 必须包 try/catch, 异常路径走 callback.onError 通道 — 否则
 * 异常时 callback 永不触发, UI 黑屏 (用户操作没有任何反馈).
 *
 * <p>callback 接口 (CreateCallback / SwitchCallback / DeleteCallback) 加
 * default onError 让现有调用方不必重写.</p>
 */
public class TaskListViewModelTryCatchTest {

    @Test
    public void threeEntryPoints_each_invokeTryCatch() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/task/TaskListViewModel.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/task/TaskListViewModel.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        for (String method : new String[]{"createTask", "deleteTask", "switchToTask"}) {
            String body = extractMethodBody(text, "public void " + method + "(");
            assertTrue(method + " body not found", body.length() > 0);
            assertTrue(method + " body must wrap diskIO lambda body in try { } catch "
                    + "(Exception). Body:\n" + body, body.contains("try {")
                    && body.contains("catch (Exception"));
            assertTrue(method + " must log failure to Log.e. Body:\n" + body,
                    body.contains("Log.e(TAG"));
            if (!"clearCompleted".equals(method)) {
                assertTrue(method + " must call cb.onError(...) in catch block. "
                        + "Body:\n" + body,
                        body.contains("cb.onError("));
            }
        }

        // clearCompleted 没有回调, catch 至少要 Log.e
        String clearBody = extractMethodBody(text, "public void clearCompleted()");
        assertTrue(clearBody.length() > 0);
        assertTrue("clearCompleted must catch Exception and log it. Body:\n"
                + clearBody, clearBody.contains("catch (Exception"));

        // callback 接口必须声明 default onError
        assertTrue("CreateCallback must declare a default onError(String) method. "
                + "Source:\n" + text,
                text.contains("default void onError(String reason)"));
    }

    @Test
    public void callback_interfaces_haveDefaultOnError() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/task/TaskListViewModel.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/task/TaskListViewModel.java");
        }
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // CreateCallback, SwitchCallback, DeleteCallback 都必须有 default onError
        int defaultErrorCount = countOccurrences(text, "default void onError(");
        assertTrue("At least 3 callback interfaces must declare onError default "
                + "(CreateCallback, SwitchCallback, DeleteCallback). Source:\n" + text,
                defaultErrorCount >= 3);
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

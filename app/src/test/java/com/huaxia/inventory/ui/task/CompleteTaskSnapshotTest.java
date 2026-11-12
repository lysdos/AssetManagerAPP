package com.huaxia.inventory.ui.task;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: 完成任务 = 提交 —— 必须在事务里清快照 (丢弃 orig_*, 让编辑值成为主数据),
 * 同时保留状态翻转的字面量 (被 {@code TaskCompleteEntryTest} 钉住)。
 *
 * <p>漏了 clearSnapshotForTask → 已完成任务的快照残留, 之后删除/清除已完成可能误回滚
 * 已提交的值。</p>
 */
public class CompleteTaskSnapshotTest {

    @Test
    public void completeTask_clearsSnapshotInsideTransaction_preservingStatusFlip() throws Exception {
        String text = read("src/main/java/com/huaxia/inventory/service/TaskService.java");
        String body = extractMethodBody(text, "public void completeTask(");
        assertTrue("completeTask body not found. Source:\n" + text, body.length() > 0);

        assertTrue("completeTask must clear the snapshot (=提交). Body:\n" + body,
                body.contains("clearSnapshotForTask("));
        assertTrue("completeTask must flip status (被 TaskCompleteEntryTest 钉住). Body:\n" + body,
                body.contains("t.status = \"COMPLETED\""));

        int tx = body.indexOf("runInTransaction(");
        int clearSnap = body.indexOf("clearSnapshotForTask(");
        assertTrue("clearSnapshotForTask must run inside runInTransaction. Body:\n" + body,
                tx >= 0 && clearSnap >= 0 && tx < clearSnap);
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

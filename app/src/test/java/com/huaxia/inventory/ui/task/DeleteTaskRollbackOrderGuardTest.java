package com.huaxia.inventory.ui.task;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: deleteTask 必须先"回滚字段 + 清盘点时间 + 清快照", 再 releaseTaskToPool。
 *
 * <p>顺序错了会静默失效 —— {@code clearSnapshotForTask} 按 taskId 过滤, 若在
 * {@code releaseTaskToPool} (把 taskId 归零) <b>之后</b>调用, 会匹配 0 行、快照残留在池行上。</p>
 * <p>且回滚只在"未提交"任务上发生 —— 已完成任务保留编辑值 (等同"清除已完成")。</p>
 */
public class DeleteTaskRollbackOrderGuardTest {

    @Test
    public void deleteTask_rollsBackUncommitted_thenClearsSnapshotBeforeRelease() throws Exception {
        String text = read("src/main/java/com/huaxia/inventory/service/TaskService.java");
        String body = extractMethodBody(text, "public int deleteTask(");
        assertTrue("deleteTask body not found. Source:\n" + text, body.length() > 0);

        assertTrue("deleteTask must revert edited fields. Body:\n" + body,
                body.contains("revertEditedFields("));
        assertTrue("deleteTask must clear inventory_time. Body:\n" + body,
                body.contains("clearInventoryTimeForTask("));
        assertTrue("deleteTask must clear snapshot. Body:\n" + body,
                body.contains("clearSnapshotForTask("));
        assertTrue("deleteTask must release to pool. Body:\n" + body,
                body.contains("releaseTaskToPool("));

        int clearSnap = body.indexOf("clearSnapshotForTask(");
        int release = body.indexOf("releaseTaskToPool(");
        assertTrue("clearSnapshotForTask must run BEFORE releaseTaskToPool "
                        + "(else taskId is 0 and snapshot clear matches 0 rows). Body:\n" + body,
                clearSnap >= 0 && release >= 0 && clearSnap < release);

        // 回滚只对未提交任务 —— 必须出现 COMPLETED 状态判断, 否则已完成任务也会被回滚.
        assertTrue("deleteTask must branch on COMPLETED status (已完成不回滚). Body:\n" + body,
                body.contains("\"COMPLETED\".equals(t.status)"));
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

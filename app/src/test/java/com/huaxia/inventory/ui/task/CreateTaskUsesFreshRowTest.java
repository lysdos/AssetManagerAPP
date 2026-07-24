package com.huaxia.inventory.ui.task;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: TaskListViewModel.createTask 在事务内插 InventoryTask 后,
 * 修正 expectedCount 之前必须 {@code findById(idHolder[0])} 拿 fresh row —
 * 不能用 {@code app.getDb().inventoryTaskDao().update(t)} 因为 {@code t.id}
 * 仍是 0 (Room @Insert 返回 rowid 不回写到 POJO 字段), 会走
 * {@code UPDATE WHERE id=0} 命中 0 行, expectedCount 修正丢失.
 * 并发导入/创建任务同时跑 (assignPool 与 countPool 跨事务读),
 * 用户看到 "已盘 X / 共 Y" 百分比错.
 */
public class CreateTaskUsesFreshRowTest {

    @Test
    public void createTask_usesFindByIdBeforeUpdate_notRawT() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/task/TaskListViewModel.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/task/TaskListViewModel.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        String body = extractMethodBody(text, "public void createTask(");
        assertTrue("createTask body not found", body.length() > 0);

        // 必须有 findById(idHolder[0]) — 在 update 之前
        int findByIdIdx = body.indexOf("findById(idHolder[0])");
        assertTrue("createTask must call findById(idHolder[0]) to read the inserted row's "
                + "real id before expectedCount update. Body:\n" + body,
                findByIdIdx >= 0);

        // update 调用必须基于 fresh row (update(fresh)), 不是 update(t)
        assertTrue("createTask update must target the freshly fetched row, "
                + "not the original t. Body:\n" + body,
                body.contains("update(fresh)"));

        // 检查代码部分 (去掉注释行) 不再含 update(t) 形式.
        // 注释行以 // 开头 — 提取非注释行检查.
        boolean codeHasUpdateT = false;
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*")) {
                continue;
            }
            if (line.contains(".update(t)") || line.contains(".update( t )")) {
                codeHasUpdateT = true;
                break;
            }
        }
        assertTrue("Code lines (not comments) of createTask must NOT call "
                + ".update(t) — t.id is still 0 after Insert, maps to WHERE id=0. "
                + "Body:\n" + body, !codeHasUpdateT);
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

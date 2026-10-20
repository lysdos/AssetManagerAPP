package com.huaxia.inventory.data;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: 任务备注 (remark) 必须完整落地 —— 实体列 + createTask 写入。
 *
 * <p>曾经的坑: {@code CreateTaskDialogFragment} 收集了备注并传给 {@code createTask}, 但
 * {@code InventoryTask} 没有 remark 字段、{@code createTask} 也没写它 → 备注被静默丢弃。</p>
 */
public class InventoryTaskRemarkTest {

    @Test
    public void inventoryTaskDeclaresRemarkColumn() throws Exception {
        String text = read("src/main/java/com/huaxia/inventory/data/entity/InventoryTask.java");
        assertTrue("InventoryTask must declare @ColumnInfo(name = \"remark\"). Source:\n" + text,
                text.contains("@ColumnInfo(name = \"remark\")"));
    }

    @Test
    public void createTask_persistsRemark() throws Exception {
        String text = read("src/main/java/com/huaxia/inventory/ui/task/TaskListViewModel.java");
        String body = extractMethodBody(text, "public void createTask(");
        assertTrue("createTask body not found. Source:\n" + text, body.length() > 0);
        assertTrue("createTask must persist remark (t.remark = ...). Body:\n" + body,
                body.contains("t.remark ="));
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

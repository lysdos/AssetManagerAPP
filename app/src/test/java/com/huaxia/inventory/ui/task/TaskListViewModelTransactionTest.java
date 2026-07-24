package com.huaxia.inventory.ui.task;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: TaskListViewModel.createTask 必须把"插入任务 + 划拨库存池资产"
 * 两步全部包进单个 runInTransaction 的 lambda 内,
 * 与同文件 deleteTask / clearCompleted 风格一致, 防止进程被杀后留下
 * "任务壳 + 0 划拨"孤儿.
 *
 * <p>2026-07-24 更新: P0#9 修复后, 事务内只放 insert + assignPool (写新数据),
 * expectedCount 修正的 update(fresh) 移到事务外 — 让 assignPoolToTask 先
 * commit, 再独立 UPDATE. 所以测试只要求 insert + assignPool 在事务内,
 * update(fresh) 在事务外但必须用 findById 拿真实 rowid 的 fresh row.</p>
 */
public class TaskListViewModelTransactionTest {

    @Test
    public void createTask_insertAndAssignRunInsideRunInTransaction() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/task/TaskListViewModel.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/task/TaskListViewModel.java");
        }

        assertTrue("TaskListViewModel.java not found: " + source.getAbsolutePath(), source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 提取 createTask 方法体 (花括号配对, 不依赖缩进格式).
        String body = extractMethodBody(text, "public void createTask(");
        assertTrue("createTask method body not found", body.length() > 0);

        // 提取 runInTransaction 的第一个 lambda 体 (花括号配对).
        // insert + assignPool 必须在同一个 lambda 内, 不允许被拆到 lambda 外.
        int runTxIdx = body.indexOf("runInTransaction");
        assertTrue("createTask body must call runInTransaction. Body:\n" + body,
                runTxIdx >= 0);

        // runInTransaction 必须只出现一次 — 多重嵌套会让 insert + assignPool 无法同时落在一个事务里.
        assertEquals("createTask body must invoke runInTransaction exactly once — "
                + "multiple calls would defeat the all-or-nothing guarantee. Body:\n" + body,
                1, countOccurrences(body, "runInTransaction"));

        String txLambdaBody = extractFollowingLambdaBody(body, runTxIdx);
        assertTrue("Could not extract runInTransaction lambda body from createTask", txLambdaBody.length() > 0);

        assertTrue("insert must run inside runInTransaction lambda. Lambda body:\n" + txLambdaBody,
                txLambdaBody.contains("inventoryTaskDao().insert("));
        assertTrue("assignPoolToTask must run inside runInTransaction lambda. Lambda body:\n"
                + txLambdaBody,
                txLambdaBody.contains("assetDao().assignPoolToTask("));

        // update(fresh) 在事务外 (catchup). 但必须基于 fresh row 而非 t —
        // t.id 在 Insert 后还停在 0, update(t) 走 WHERE id=0 命中 0 行.
        assertTrue("createTask must call findById(idHolder[0]) to fetch fresh row. "
                + "Body:\n" + body,
                body.contains("findById(idHolder[0])"));
        assertTrue("createTask update must target fresh row, not the original t. "
                + "Body:\n" + body,
                body.contains("update(fresh)"));
    }

    /** 返回方法签名后第一个匹配的 '{' 之后到对应 '}' 之间的内容。 */
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

    /**
     * 从 {@code startIdx} 之后找首个 {@code -> { ... }}, 用花括号配对截取 lambda 体.
     * 这是 Room 的 {@code runInTransaction(Runnable)} 调用形式 — 我们期望紧跟着的是
     * 一个无参 Runnable lambda, 形如 {@code () -> { ... }}。
     */
    private static String extractFollowingLambdaBody(String text, int startIdx) {
        int arrowIdx = text.indexOf("->", startIdx);
        if (arrowIdx < 0) return "";
        int braceStart = text.indexOf('{', arrowIdx);
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

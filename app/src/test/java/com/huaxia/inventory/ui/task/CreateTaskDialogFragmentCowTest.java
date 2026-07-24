package com.huaxia.inventory.ui.task;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: CreateTaskDialogFragment.deptOptions 必须初始化为
 * {@link java.util.concurrent.CopyOnWriteArrayList}, 不是 {@link ArrayList}.
 *
 * <p>背景: deptOptions 在 diskIO 回调 runOnUiThread 里被 clear/addAll,
 * 而 onViewCreated 自身也在主线程 add. 现行没有跨线程并发 (callback 已
 * runOnUiThread + isAdded 守卫), 但 callback 期间若 dialog 被旋转重建,
 * 旧 callback 仍可能命中已替换的 List 实例 — CoW 提供兜底.</p>
 */
public class CreateTaskDialogFragmentCowTest {

    @Test
    public void deptOptions_isCopyOnWriteArrayList() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/task/CreateTaskDialogFragment.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/task/CreateTaskDialogFragment.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 必须用 CopyOnWriteArrayList — ArrayList 在未来的扩展 (例如多观察者 / 跨
        // Fragment 共享 spinner 数据) 下会出现 ConcurrentModificationException.
        assertTrue("CreateTaskDialogFragment.deptOptions must be a CopyOnWriteArrayList. "
                + "Source:\n" + text,
                text.contains("new java.util.concurrent.CopyOnWriteArrayList<>()"));

        // 旧 ArrayList 不应保留.
        assertTrue("CreateTaskDialogFragment must NOT retain the old "
                + "new ArrayList<>() initialization for deptOptions. Source:\n" + text,
                !text.contains("private final List<String> deptOptions = new ArrayList<>();"));
    }
}

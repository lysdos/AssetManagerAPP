package com.huaxia.inventory.ui.task;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: TaskListFragment 弹出 CreateTaskDialogFragment 必须用
 * {@code getParentFragmentManager()}, 不能用 {@code getChildFragmentManager()}.
 *
 * <p>后者在底部 nav 切走 (TaskListFragment 被 replace) 时,
 * 父 fragment 销毁会连带 dialog 强制 detach, 但 dialog 内部 listener 闭包
 * 仍引用 fragment 实例, 后续事件 dispatch 到 detach 的 fragment 触发 NPE
 * 以及内存 anchor. parentFragmentManager 让 dialog 跟随 Activity 完整
 * 生命周期管理, 不会因父 fragment 销毁而卡死.</p>
 */
public class DialogFragmentUsesParentManagerTest {

    @Test
    public void createTaskDialog_show_usesParentFragmentManager() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/task/TaskListFragment.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/task/TaskListFragment.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 必须出现 getParentFragmentManager() 调用
        assertTrue("TaskListFragment must use getParentFragmentManager() to show "
                + "DialogFragment, not getChildFragmentManager(). Source:\n" + text,
                text.contains("getParentFragmentManager()"));

        // 不允许 getChildFragmentManager() (会强制 dialog 跟父 fragment 同生命周期,
        // 父 fragment 销毁时 dialog detach 触发 NPE)
        assertTrue("TaskListFragment must NOT use getChildFragmentManager() for "
                + "DialogFragment show. Source:\n" + text,
                !text.contains("getChildFragmentManager()"));
    }
}

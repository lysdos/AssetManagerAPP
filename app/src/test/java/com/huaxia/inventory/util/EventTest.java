package com.huaxia.inventory.util;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: 一次性事件包装 {@link Event} 的语义 + 三条瞬时事件流必须用它。
 *
 * <p>背景: Fragment 的 ViewModel 跨旋转存活, 其 LiveData 保留旧值 —— 旋转后新
 * observer 立刻收到旧事件, 造成重复 Snackbar / 重复蜂鸣 / 再次清空输入。Event
 * 取一次即置空, 消除重放。</p>
 */
public class EventTest {

    @Test
    public void getContentIfNotHandled_returnsOnceThenNull() {
        Event<String> e = new Event<>("boom");
        assertEquals("first consume must yield content", "boom", e.getContentIfNotHandled());
        assertNull("second consume (replay) must be null", e.getContentIfNotHandled());
        assertNull("third consume must stay null", e.getContentIfNotHandled());
    }

    @Test
    public void transientEventStreams_areWrappedInEvent() throws Exception {
        assertContains("src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java",
                "MutableLiveData<Event<ClearResult>>");
        assertContains("src/main/java/com/huaxia/inventory/ui/manual/ManualScanViewModel.java",
                "MutableLiveData<Event<CheckEvent>>");
        assertContains("src/main/java/com/huaxia/inventory/ui/manual/ManualScanViewModel.java",
                "MutableLiveData<Event<ScanEvent>>");
    }

    private static void assertContains(String rel, String needle) throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File f = new File(moduleDir, rel);
        if (!f.exists()) f = new File(moduleDir, "app/" + rel);
        assertTrue("source not found: " + f.getAbsolutePath(), f.exists());
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue(rel + " must declare " + needle
                + " (防旋转重放的一次性事件). Source:\n" + text, text.contains(needle));
    }
}

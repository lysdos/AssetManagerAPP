package com.huaxia.inventory;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: {@code MainActivity} 在 {@code onStop} 里
 * {@code addObserver(processObserver)} 之后, 必须在 {@code onDestroy} 里
 * {@code removeObserver} —— 否则 ProcessLifecycleOwner (进程级) 会永久持有
 * 捕获了 {@code MainActivity.this} 的 observer, 每次旋转/重建泄漏一个 Activity。
 */
public class MainActivityProcessObserverLifecycleTest {

    @Test
    public void mainActivity_removesProcessObserverInOnDestroy() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File f = new File(moduleDir, "src/main/java/com/huaxia/inventory/MainActivity.java");
        if (!f.exists()) {
            f = new File(moduleDir, "app/src/main/java/com/huaxia/inventory/MainActivity.java");
        }
        assertTrue("MainActivity.java not found: " + f.getAbsolutePath(), f.exists());
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);

        assertTrue("MainActivity must removeObserver(processObserver) — onStop's addObserver "
                + "otherwise leaks an Activity per recreation. Source:\n" + text,
                text.contains("removeObserver(processObserver)"));
        assertTrue("removeObserver must live in an onDestroy override. Source:\n" + text,
                text.contains("protected void onDestroy()"));
    }
}

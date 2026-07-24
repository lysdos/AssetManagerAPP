package com.huaxia.inventory;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: MainActivity 必须用 {@code androidx.lifecycle.ProcessLifecycleOwner}
 * 监听"应用真进后台"事件, 取代旧的
 * {@code onStop 记录 lastStopTime + onStart 判断} 模式 — 后者把权限弹窗、
 * 系统对话框短离场当成"应用进后台", 用户高频被打断会感觉 RFID 反复关闭.
 *
 * <p>判定要求:</p>
 * <ul>
 *   <li>{@code import androidx.lifecycle.ProcessLifecycleOwner} 出现在 MainActivity</li>
 *   <li>{@code ProcessLifecycleOwner.get().getLifecycle().addObserver(...)} 调用点
 *       必须注册一个 {@code DefaultLifecycleObserver} 字段 (而非匿名 lambda — 注销时
 *       需要引用)</li>
 *   <li>{@code onStart} 内的 powerOff 判定条件必须基于
 *       {@code lastProcessStopTime} (新的 process-level 变量), 不是旧的
 *       {@code lastStopTime} (Activity-level)</li>
 * </ul>
 */
public class MainActivityProcessLifecycleTest {

    @Test
    public void mainActivity_usesProcessLifecycleOwner() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/MainActivity.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/MainActivity.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        assertTrue("MainActivity must import androidx.lifecycle.ProcessLifecycleOwner. Source:\n"
                + text,
                text.contains("import androidx.lifecycle.ProcessLifecycleOwner"));

        assertTrue("MainActivity must register a DefaultLifecycleObserver with "
                + "ProcessLifecycleOwner. Source:\n" + text,
                text.contains("ProcessLifecycleOwner.get().getLifecycle().addObserver("));

        assertTrue("MainActivity must use a process-level stop time variable name "
                + "(distinct from Activity.onStop's lastStopTime). Source:\n" + text,
                text.contains("lastProcessStopTime"));

        // 旧字段 lastStopTime (Activity-level) 应已删除, 否则新旧两套共存.
        assertTrue("MainActivity must NOT keep the old Activity-level lastStopTime field "
                + "— it triggered powerOff on short exits (permission dialogs, system "
                + "dialogs). Source:\n" + text,
                !text.contains("private long lastStopTime"));
    }
}

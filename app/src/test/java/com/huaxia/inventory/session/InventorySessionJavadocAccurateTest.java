package com.huaxia.inventory.session;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: InventorySession 的类级 Javadoc 必须如实区分主线程同步 (setValue)
 * 与后台线程异步 (postValue) 路径, 不能继续笼统承诺
 * "setCurrentTaskId/clear 必须满足调用方紧跟 getCurrentTaskId() 立即读到新值".
 * 后者把后台 postValue 也错误归入, 后台调用方会误判可见性.
 */
public class InventorySessionJavadocAccurateTest {

    @Test
    public void javadoc_documentsMainThreadSetValue_andBackendPostValueSeperately()
            throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/session/InventorySession.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/session/InventorySession.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 必须有 "主线程" 措辞 + setValue / 同步 提及
        assertTrue("InventorySession Javadoc must mention main thread + setValue "
                + "with synchronous visibility. Source:\n" + text,
                text.contains("主线程") && text.contains("setValue"));

        // 必须有 "后台线程" 措辞 + postValue / 异步 提及
        assertTrue("InventorySession Javadoc must mention backend thread + postValue "
                + "with asynchronous visibility. Source:\n" + text,
                text.contains("后台线程") && text.contains("postValue"));

        // 不能在 javadoc 顶部/abstract class 注释里笼统写 "立即可见"
        // (覆盖整个类, 而不限定主线程).
        assertFalse("InventorySession Javadoc must NOT state a blanket "
                + "'立即看到/立即读到' without main-thread qualification. "
                + "Source:\n" + text,
                text.contains("立即看到") && !text.contains("主线程"));
        assertFalse("InventorySession Javadoc must NOT state a blanket "
                + "'getCurrentTaskId() 立即读到新值' without main-thread "
                + "qualification. Source:\n" + text,
                text.contains("立即读到新值"));
    }
}

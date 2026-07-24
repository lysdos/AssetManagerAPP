package com.huaxia.inventory.rfid;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: SdkRfidAdapter 不允许再保留包私有构造
 * {@code SdkRfidAdapter(RFIDWithUHFUART reader)} 测试 seam 死代码 —
 * 没有任何调用方使用 (R5/rfid 单元测试都走 Robolectric 默认构造), 包私有
 * 仅增加 API 表面却不增加覆盖.
 */
public class SdkRfidAdapterNoTestSeamTest {

    @Test
    public void noInjectedReaderConstructor() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/rfid/SdkRfidAdapter.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/rfid/SdkRfidAdapter.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 注入式构造器 (任意可见性) 都不能存在.
        assertFalse("SdkRfidAdapter must NOT have an injected-reader constructor — "
                + "the package-private test seam was dead code. Source:\n" + text,
                text.contains("SdkRfidAdapter(RFIDWithUHFUART reader)"));

        // 默认 public 构造器应当保留 — 业务唯一路径.
        assertTrue("SdkRfidAdapter must keep the public default constructor. Source:\n"
                + text,
                text.contains("public SdkRfidAdapter()"));
    }
}

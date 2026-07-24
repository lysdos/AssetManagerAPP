package com.huaxia.inventory;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: InventoryApp.onCreate 的 rfid.init callback 内部若
 * {@code ready}, 调 {@code rfid.setPower(savedPower)} 必须切到
 * {@code executors.diskIO.execute(...)} 后台线程 + try/catch Throwable
 * 兜底, 不能再在主线程同步调. setPower 走 SDK native 调用,
 * HAL miss / 状态机错误时主线程累计阻塞会触发启动期 ANR.
 */
public class InventoryAppSetPowerIsAsyncTest {

    @Test
    public void rfidInitCallback_setPowerRunsOnDiskIO() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/InventoryApp.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/InventoryApp.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 找到 rfid.init 调用段, 检查回调体
        int initIdx = text.indexOf("rfid.init(this, ready ->");
        assertTrue("rfid.init callback not found", initIdx >= 0);

        // 从该 callback 到对应的 '});' 行, 提取回调 body
        // 简化: 直接看是否在同一行或之后有 "executors.diskIO.execute(" 包住 setPower
        int diskIOIdx = text.indexOf("executors.diskIO.execute(", initIdx);
        int setPowerIdx = text.indexOf("rfid.setPower(savedPower)", initIdx);
        assertTrue("rfid.init callback must wrap setPower in executors.diskIO.execute "
                + "— synchronous setPower on init callback accumulates main-thread "
                + "blocking on slow HAL devices. Source:\n" + text,
                diskIOIdx >= 0 && setPowerIdx >= 0);

        // 必须有 try/catch Throwable 保护
        int tryIdx = text.indexOf("try {", diskIOIdx);
        int catchIdx = text.indexOf("catch (Throwable", diskIOIdx);
        assertTrue("diskIO.execute body must catch Throwable — setPower can throw "
                + "RuntimeException from native SDK. Source:\n" + text,
                tryIdx >= 0 && catchIdx >= 0 && catchIdx > tryIdx);
    }
}

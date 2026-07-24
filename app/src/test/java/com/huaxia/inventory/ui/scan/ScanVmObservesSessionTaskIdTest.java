package com.huaxia.inventory.ui.scan;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: ScanViewModel 必须 observe InventorySession.currentTaskId,
 * 外部 deleteTask / pauseTask / resumeTask / switchToTask 改写或清空
 * session.taskId 时, vm 内部 scannedEpcSet / currentTaskId 跟着复位并停
 * RFID — 否则 rfid-poll 守护线程继续 emit 写到已删 taskId,
 * 写出孤儿 ScanRecord (taskId=已删 taskId).
 *
 * <p>同时 onCleared 必须 removeObserver, 否则 observeForever 与 VM 互相持有,
 * 新建同 InventorySession 的下一轮 ViewModel 拿到旧 VM 引用.</p>
 */
public class ScanVmObservesSessionTaskIdTest {

    @Test
    public void scanVm_observesSessionTaskId_andResetsOnMismatch() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 构造器内必须 observeForever session.currentTaskId
        String ctorBody = extractMethodBody(text, "public ScanViewModel(");
        assertTrue("ScanViewModel constructor body not found", ctorBody.length() > 0);
        assertTrue("ScanViewModel constructor must call app.session.currentTaskId()"
                + ".observeForever(...). Body:\n" + ctorBody,
                ctorBody.contains("observeForever"));

        // 必须有 onSessionTaskIdChanged / onSessionCleared 等清空 vm 状态的入口
        assertTrue("ScanViewModel must define a sessionObserver method to detect "
                + "external taskId changes. Source:\n" + text,
                text.contains("onSessionTaskIdChanged")
                        || text.contains("onSessionCleared"));

        // onSessionCleared 体内必须调 app.rfid.stopInventory()
        // 我们抽取该方法体 (private)
        int idx = text.indexOf("private void onSessionCleared()");
        assertTrue("ScanViewModel must declare a private onSessionCleared() method. "
                + "Source:\n" + text, idx >= 0);
        String onSessionBody = extractMethodBody(text, "private void onSessionCleared()");
        assertTrue("onSessionCleared body not found", onSessionBody.length() > 0);
        assertTrue("onSessionCleared must call app.rfid.stopInventory() to halt the "
                + "rfid-poll daemon. Body:\n" + onSessionBody,
                onSessionBody.contains("app.rfid.stopInventory"));

        // onCleared 必须反注册 observer
        String onClearedBody = extractMethodBody(text, "protected void onCleared()");
        assertTrue("onCleared body not found", onClearedBody.length() > 0);
        assertTrue("onCleared must call session.currentTaskId().removeObserver(...) "
                + "to release the forever observer. Body:\n" + onClearedBody,
                onClearedBody.contains("removeObserver("));
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

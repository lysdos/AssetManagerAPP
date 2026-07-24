package com.huaxia.inventory.ui.setting;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: PowerSettingDialog 必须删除已死的
 * {@code OnPowerChangedListener / setOnPowerChangedListener / listener} 字段.
 * 修复前 MainActivity 从未调用 setOnPowerChangedListener, 是死代码 + 字段
 * 泄漏 (dialog 旋转重建期间 listener 引用仍可达).
 */
public class PowerSettingDialogNoListenerTest {

    @Test
    public void powerSettingDialog_dropsDeadListenerInterface() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/setting/PowerSettingDialog.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/setting/PowerSettingDialog.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        assertFalse("PowerSettingDialog must NOT declare a OnPowerChangedListener "
                + "interface — never invoked. Source:\n" + text,
                text.contains("interface OnPowerChangedListener"));
        assertFalse("PowerSettingDialog must NOT have setOnPowerChangedListener method.",
                text.contains("setOnPowerChangedListener"));
        assertFalse("PowerSettingDialog must NOT hold a private listener field.",
                text.contains("private OnPowerChangedListener listener"));

        // savePower() 也不再有 listener.onPowerChanged 调用 — 因为已无人注册
        String saveBody = extractMethodBody(text, "private void savePower()");
        if (saveBody != null) {
            assertFalse("savePower() must NOT call listener.onPowerChanged. Body:\n"
                    + saveBody, saveBody.contains("listener.onPowerChanged"));
        }
    }

    private static String extractMethodBody(String text, String signature) {
        int sigStart = text.indexOf(signature);
        if (sigStart < 0) return null;
        int braceStart = text.indexOf('{', sigStart);
        if (braceStart < 0) return null;
        int depth = 1;
        int i = braceStart + 1;
        while (i < text.length() && depth > 0) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        if (depth != 0) return null;
        return text.substring(braceStart + 1, i - 1);
    }
}

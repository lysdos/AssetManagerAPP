package com.huaxia.inventory.ui.manual;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: ManualScanFragment.cameraPermissionLauncher 回调 入口必须
 * {@code if (!isAdded()) return;} 守卫. 修复前回调体直接
 * {@code requireView()/Snackbar.make(...)} — 系统权限对话框弹出期间用户切走
 * fragment, 等回调派发时 fragment 已 detach, requireView() 抛 IllegalStateException.
 */
public class ManualScanFragmentPermissionLauncherGuardTest {

    @Test
    public void cameraPermissionLauncher_callback_hasIsAddedGuard() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/manual/ManualScanFragment.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/manual/ManualScanFragment.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // find the cameraPermissionLauncher callback
        int launcherIdx = text.indexOf("cameraPermissionLauncher = registerForActivityResult(");
        assertTrue("cameraPermissionLauncher must exist. Source:\n" + text,
                launcherIdx >= 0);
        // call the granted -> { ... } body
        int arrowIdx = text.indexOf("->", launcherIdx);
        assertTrue("permission callback must be a lambda. Source:\n" + text,
                arrowIdx >= 0);
        int braceStart = text.indexOf('{', arrowIdx);
        assertTrue("permission callback must have lambda body. Source:\n" + text,
                braceStart >= 0);
        // balance braces to find body
        int depth = 1;
        int i = braceStart + 1;
        while (i < text.length() && depth > 0) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        if (depth != 0) return;  // unbalanced, ignore
        String body = text.substring(braceStart + 1, i - 1);

        assertTrue("cameraPermissionLauncher callback must guard with isAdded() "
                + "before requireView(). Body:\n" + body,
                body.contains("if (!isAdded())"));
    }
}

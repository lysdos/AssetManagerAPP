package com.huaxia.inventory.ui.scan;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: ScanFragment.beepPlayer.release() 必须位于 {@code onDestroyView},
 * 不能在 {@code onPause}. 后者路径下, 屏幕旋转会触发:
 * {@code onPause (release)} → {@code onDestroyView (release 二次)} →
 * 重新 {@code onCreateView (init, 但 soundPool != null 走 no-op)}.
 * 第二次 init 走 no-op 但 SoundPool 已被 release — 后续 beepPlayer.play() 静默.
 *
 * <p>正确路径: {@code onViewCreated} init (确保 init 在 View 创建时),
 * {@code onDestroyView} release (确保 release 跟 View 销毁对齐).</p>
 */
public class ScanFragmentBeepPlayerLifecycleTest {

    @Test
    public void releaseLivesInOnDestroyView_notInOnPause() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/scan/ScanFragment.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/scan/ScanFragment.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // onPause 体内不允许再 release SoundPool — 只 stopInventory + unregisterCallback.
        // 注意: 只检查 *调用形式* (beepPlayer.release()), 不匹配注释里出现 "beepPlayer.release"
        // 这样的字面量 — 注释里解释意图是允许的.
        String onPauseBody = extractMethodBody(text, "public void onPause()");
        assertTrue("onPause body not found", onPauseBody.length() > 0);
        assertFalse("onPause must NOT call beepPlayer.release() — SoundPool lifecycle must "
                + "match View lifecycle. Body:\n" + onPauseBody,
                onPauseBody.contains("beepPlayer.release()"));

        // 必须有 onDestroyView override, 体内调 beepPlayer.release().
        String destroyViewBody = extractMethodBody(text, "public void onDestroyView()");
        assertTrue("ScanFragment.onDestroyView must exist and release beepPlayer. Source:\n"
                + text, destroyViewBody.length() > 0);
        assertTrue("onDestroyView must call beepPlayer.release(). Body:\n" + destroyViewBody,
                destroyViewBody.contains("beepPlayer.release()"));
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

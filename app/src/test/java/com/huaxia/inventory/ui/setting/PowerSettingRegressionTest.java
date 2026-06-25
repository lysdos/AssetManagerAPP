package com.huaxia.inventory.ui.setting;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * Static regression tests for the power-setting fixes.
 *
 * <p>Two production bugs are guarded here:</p>
 * <ol>
 *   <li>{@code InventoryApp} must auto-apply the saved power when RFID init
 *       completes — without this, the value the user set is lost on next launch
 *       and the reader uses default 26 dBm silently.</li>
 *   <li>{@code PowerSettingDialog.savePower()} must call {@code setPower}
 *       <b>before</b> {@code savePower} — the opposite order leaves
 *       {@code SharedPreferences} holding a new value the reader never received,
 *       an inconsistent state.</li>
 * </ol>
 *
 * <p>Both tests are textual. They do not execute the production code, but they
 * fail loudly if someone refactors and drops the order or the auto-apply.
 * To make the test exercise the behavior, run the app on device and verify
 * that "set 30 dBm → kill process → reopen" preserves the 30 dBm reading.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class PowerSettingRegressionTest {

    /**
     * InventoryApp.onCreate must contain both {@code rfid.init(...)} and
     * {@code rfid.setPower(getSavedPower()(...))} in the init callback, with
     * the setPower call appearing AFTER the init call.
     */
    @Test
    public void inventoryApp_autoAppliesSavedPower_inInitCallback() throws Exception {
        File f = locateSource("src/main/java/com/huaxia/inventory/InventoryApp.java");
        String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);

        int initIdx = src.indexOf("rfid.init(this");
        assertTrue("InventoryApp must call rfid.init — has onCreate been refactored?",
                initIdx >= 0);

        // Look for the auto-apply pattern within a reasonable window after init.
        // The setPower call site uses `getSavedPower()` so we search for that.
        int setPowerIdx = src.indexOf("rfid.setPower(savedPower)", initIdx);
        assertTrue(
                "InventoryApp.onCreate must auto-apply saved power after rfid.init succeeds.\n"
                        + "Expected: rfid.init callback contains `rfid.setPower(savedPower)`.\n"
                        + "If this fails, the user's chosen power value is lost on every relaunch.",
                setPowerIdx > initIdx);

        // Also verify the auto-apply is gated on `ready == true` — otherwise
        // we'd try to set power before the SDK finished initializing.
        // Find the line just before setPower and check it has an `if (ready)` pattern.
        int windowStart = Math.max(0, setPowerIdx - 400);
        String window = src.substring(windowStart, setPowerIdx);
        assertTrue(
                "auto-applied setPower must be inside `if (ready)` block — otherwise we'd\n"
                        + "call setPower before the SDK is initialized. Window: "
                        + window.replace("\n", "\\n"),
                window.contains("if (ready)"));
    }

    /**
     * PowerSettingDialog.savePower() must call {@code setPower} before
     * {@code savePower}, not the other way around. This catches the regression
     * where prefs is written even when the reader rejects the new value.
     */
    @Test
    public void powerSettingDialog_setPower_before_savePower() throws Exception {
        File f = locateSource("src/main/java/com/huaxia/inventory/ui/setting/PowerSettingDialog.java");
        String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);

        // Locate the savePower() method body. Brace-match from its declaration.
        int methodIdx = src.indexOf("private void savePower()");
        assertTrue("savePower() method not found — has the dialog been refactored?",
                methodIdx >= 0);

        int braceOpen = src.indexOf('{', methodIdx);
        assertTrue("savePower() missing opening brace", braceOpen >= 0);

        int depth = 1;
        int i = braceOpen + 1;
        while (i < src.length() && depth > 0) {
            char c = src.charAt(i++);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        assertTrue("savePower() body not terminated — unmatched braces", depth == 0);
        String body = src.substring(braceOpen, i);

        int setPowerAt = body.indexOf("app.rfid.setPower(");
        int savePowerAt = body.indexOf("app.savePower(");

        assertTrue("savePower() must invoke app.rfid.setPower(...)", setPowerAt >= 0);
        assertTrue("savePower() must invoke app.savePower(...) on success", savePowerAt >= 0);
        assertTrue(
                "savePower() must call app.rfid.setPower(...) BEFORE app.savePower(...).\n"
                        + "Reverse order leaves prefs holding a value the reader never received.\n"
                        + "setPowerAt=" + setPowerAt + ", savePowerAt=" + savePowerAt,
                setPowerAt < savePowerAt);
    }

    /**
     * BarcodeCameraController.startScan must use {@code future.addListener}
     * + main executor instead of blocking {@code future.get()} on the main
     * thread. The check is "future.get() appears only inside the
     * addListener callback" — inside the listener the future is already done
     * and {@code get()} returns immediately, so that's safe.
     */
    @Test
    public void barcodeCameraController_startScan_isAsync() throws Exception {
        File f = locateSource("src/main/java/com/huaxia/inventory/barcode/BarcodeCameraController.java");
        String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);

        int methodIdx = src.indexOf("public void startScan(");
        assertTrue("startScan method not found", methodIdx >= 0);

        int braceOpen = src.indexOf('{', methodIdx);
        assertTrue("startScan missing opening brace", braceOpen >= 0);

        int depth = 1;
        int i = braceOpen + 1;
        while (i < src.length() && depth > 0) {
            char c = src.charAt(i++);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        assertTrue("startScan body not terminated", depth == 0);
        String body = src.substring(braceOpen, i);

        int addListenerAt = body.indexOf("future.addListener(");
        assertTrue(
                "startScan() must use future.addListener(...) for async binding\n"
                        + "instead of blocking future.get() on the main thread.",
                addListenerAt >= 0);

        // If future.get() appears anywhere in the body, it must be AFTER
        // (i.e. inside) the addListener callback. Anything before addListener
        // would be a synchronous block on the main thread.
        int futureGetAt = body.indexOf("future.get()");
        if (futureGetAt >= 0) {
            assertTrue(
                    "future.get() must only be called inside the addListener callback\n"
                            + "(where the future is already done and get() returns immediately).\n"
                            + "future.get() at offset " + futureGetAt
                            + " comes before future.addListener at offset " + addListenerAt
                            + " — that blocks the main thread.",
                    futureGetAt > addListenerAt);
        }

        // Lifecycle guard: must skip binding if owner is destroyed by the time
        // the listener fires.
        assertTrue(
                "startScan() must guard against owner.getLifecycle().getCurrentState() == DESTROYED\n"
                        + "to prevent IllegalArgumentException from CameraX when the host has been\n"
                        + "destroyed while the camera-provider future was pending.",
                body.contains("Lifecycle.State.DESTROYED"));
    }

    private File locateSource(String relPath) {
        // In Gradle test JVM, user.dir is the app module dir, so the project
        // root is one level up.
        File root = new File(System.getProperty("user.dir")).getParentFile();
        File f = new File(root, "app/" + relPath);
        assertTrue("source file not found: " + f.getAbsolutePath(), f.exists());
        return f;
    }
}
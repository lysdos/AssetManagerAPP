package com.huaxia.inventory.ui.result;

import android.os.Looper;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Regression tests for the #2/#5 "Fragment detached after async work" crash.
 *
 * <p>Pattern (in {@link ResultFragment}, {@link com.huaxia.inventory.ui.import_.ImportFragment},
 * {@link com.huaxia.inventory.ui.scan.ScanFragment}): a background executor runs
 * DB or file I/O, then posts a UI update via
 * {@code requireActivity().runOnUiThread(...)}. If the Fragment is detached
 * before the runnable drains, {@code requireActivity()} throws
 * {@link IllegalStateException} and the app crashes.</p>
 *
 * <p>The production fix is a two-layer {@code isAdded()} guard: one before
 * {@code requireActivity()} on the background thread, one inside the
 * {@code runOnUiThread} runnable. These tests pin that pattern in place —
 * if anyone removes the guards, the unguarded variant must crash, proving
 * the regression would be real.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class FragmentAsyncGuardTest {

    private ActivityController<FragmentActivity> activityController;
    private ExecutorService exec;

    @After
    public void teardown() {
        if (exec != null) exec.shutdownNow();
        if (activityController != null) {
            activityController.pause().stop().destroy();
        }
    }

    /**
     * The fixed pattern: when the background runnable wakes up and finds the
     * Fragment detached, it must skip the {@code runOnUiThread} call instead
     * of throwing. After drain, no exception is thrown and the UI runnable
     * is never executed.
     */
    @Test
    public void guarded_runOnUiThread_afterDetach_isSilentlySkipped() throws Exception {
        TestFragment frag = attachFragment();
        // Detach FIRST so the guard check is deterministic — there is no
        // timing window where the background thread can sneak through.
        detachFragment(frag);
        assertFalse(frag.isAdded());

        CountDownLatch uiRan = new CountDownLatch(1);
        AtomicReference<Throwable> bgError = new AtomicReference<>();

        exec = Executors.newSingleThreadExecutor();
        exec.submit(() -> {
            try {
                // Simulate the production shape: do work, then post to UI.
                // Guard #1: do not even call requireActivity() if detached.
                if (!frag.isAdded()) return;
                FragmentActivity act = frag.requireActivity();
                act.runOnUiThread(uiRan::countDown);
            } catch (Throwable t) {
                bgError.set(t);
            }
        });

        ShadowLooper.idleMainLooper();
        exec.shutdown();
        assertTrue("background thread must have terminated",
                exec.awaitTermination(2, TimeUnit.SECONDS));
        ShadowLooper.idleMainLooper();

        assertEquals("UI runnable must not fire after detach", 1L, uiRan.getCount());
        assertEquals("no exception expected on the background thread",
                null, bgError.get());
    }

    /**
     * Sanity check that proves the crash is real when the guard is removed.
     * This is the "without the fix" baseline — it must throw, otherwise our
     * guard test above isn't actually proving anything.
     */
    @Test
    public void unguarded_requireActivity_afterDetach_throwsIllegalState() throws Exception {
        TestFragment frag = attachFragment();
        detachFragment(frag);
        assertFalse(frag.isAdded());

        AtomicReference<Throwable> bgError = new AtomicReference<>();

        exec = Executors.newSingleThreadExecutor();
        exec.submit(() -> {
            try {
                // NO guard. Mirror pre-fix production code.
                FragmentActivity act = frag.requireActivity();
                act.runOnUiThread(() -> {});
            } catch (Throwable t) {
                bgError.set(t);
            }
        });

        ShadowLooper.idleMainLooper();
        exec.shutdown();
        assertTrue(exec.awaitTermination(2, TimeUnit.SECONDS));
        ShadowLooper.idleMainLooper();

        Throwable err = bgError.get();
        assertNotNull("without the guard, requireActivity() must throw after detach", err);
        assertTrue("expected IllegalStateException, got " + err.getClass(),
                err instanceof IllegalStateException);
    }

    /**
     * Companion to {@link #guarded_runOnUiThread_afterDetach_isSilentlySkipped}
     * for the in-runnable guard: even if the background thread passed
     * isAdded() (Fragment was attached when checked), the Fragment may
     * detach before the queued UI runnable actually runs. The in-runnable
     * guard prevents that race.
     */
    @Test
    public void guarded_inRunnable_afterLateDetach_isSilentlySkipped() throws Exception {
        TestFragment frag = attachFragment();
        assertTrue(frag.isAdded());

        CountDownLatch uiRan = new CountDownLatch(1);
        AtomicReference<Throwable> bgError = new AtomicReference<>();

        exec = Executors.newSingleThreadExecutor();
        exec.submit(() -> {
            try {
                FragmentActivity act = frag.requireActivity();
                act.runOnUiThread(() -> {
                    // Guard #2: re-check inside the runnable.
                    if (!frag.isAdded()) return;
                    uiRan.countDown();
                });
            } catch (Throwable t) {
                bgError.set(t);
            }
        });

        // Wait for the background thread to finish queuing the runnable.
        exec.shutdown();
        assertTrue(exec.awaitTermination(2, TimeUnit.SECONDS));

        // Now detach BEFORE draining the main looper, so the runnable will
        // observe isAdded() == false when it finally runs.
        detachFragment(frag);
        assertFalse(frag.isAdded());

        ShadowLooper.idleMainLooper();

        assertEquals("UI runnable must not fire after late detach", 1L, uiRan.getCount());
        assertEquals(null, bgError.get());
    }

    /**
     * Static guard: every {@code runOnUiThread} call inside an
     * {@code executors.*.execute(...)} lambda in production Fragments must
     * be preceded by an {@code if (!isAdded())} check. This is a textual
     * source-presence test — it does not execute the Fragment, but it does
     * fail loudly if someone removes the guard while refactoring.
     *
     * <p>If this test ever fails, the fix for #2/#5 has regressed in the
     * listed Fragment files. Re-add the guard; do not delete this test.</p>
     */
    @Test
    public void production_fragments_have_isAdded_guard_before_runOnUiThread() throws Exception {
        String[] paths = {
                "src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java",
                "src/main/java/com/huaxia/inventory/ui/import_/ImportFragment.java",
                "src/main/java/com/huaxia/inventory/ui/scan/ScanFragment.java",
        };
        // Resolve the project root. In Gradle's test JVM, user.dir is the
        // module dir (i.e. .../huaxiaapp/app), so the project root is one
        // level up.
        java.io.File projectRoot = new java.io.File(System.getProperty("user.dir"))
                .getParentFile();
        for (String relPath : paths) {
            java.io.File f = new java.io.File(projectRoot, "app/" + relPath);
            assertTrue("source file not found: " + f.getAbsolutePath(), f.exists());
            String src = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);

            int count = 0;
            int idx = 0;
            while ((idx = src.indexOf("requireActivity().runOnUiThread", idx)) != -1) {
                count++;
                // Look back 400 chars (typical lambda body is small) for a
                // `if (!isAdded())` pattern. Bracket-balanced enough to
                // catch both outer-guard and in-runnable-guard forms.
                int windowStart = Math.max(0, idx - 400);
                String window = src.substring(windowStart, idx);
                assertTrue(
                        "missing `if (!isAdded())` guard before runOnUiThread in "
                                + relPath + " (occurrence #" + count + ")\n"
                                + "window: ..." + window.replace("\n", "\\n"),
                        window.contains("if (!isAdded())")
                                || window.contains("if (isAdded())"));
                idx++;
            }
            // Sanity: each listed file must actually contain runOnUiThread,
            // otherwise the test passes vacuously.
            assertTrue("no runOnUiThread calls found in " + relPath
                            + " — has the file been refactored? update the test.",
                    count > 0);
        }
    }

    // ---- helpers ----

    private TestFragment attachFragment() {
        activityController = Robolectric.buildActivity(FragmentActivity.class);
        activityController.create().start().resume();
        FragmentActivity host = activityController.get();

        // Sanity: ensure the host has a content view so Fragment has somewhere to live.
        assertNotNull(host.findViewById(android.R.id.content));

        FragmentManager fm = host.getSupportFragmentManager();
        TestFragment frag = new TestFragment();
        fm.beginTransaction()
                .add(android.R.id.content, frag, "guard-test")
                .commitNow();
        assertTrue("Fragment must be added after commitNow", frag.isAdded());
        return frag;
    }

    private void detachFragment(TestFragment frag) {
        FragmentManager fm = activityController.get().getSupportFragmentManager();
        fm.beginTransaction().remove(frag).commitNow();
        // commitNow is synchronous in Robolectric; isAdded() must already be false.
        assertFalse("Fragment must be detached after remove+commitNow", frag.isAdded());
    }

    /** Minimal Fragment — we test the guard pattern, not any production code. */
    public static class TestFragment extends Fragment {
        // Holds the main looper reference so the host class loader doesn't
        // dead-code-eliminate the import in the unused-import lint check.
        @SuppressWarnings("unused")
        private static final Looper MAIN_LOOPER = Looper.getMainLooper();
    }

    // If the helpers above leave the activity in an unexpected state, fail loud
    // rather than silently producing a green test on broken scaffolding.
    static {
        if (Looper.getMainLooper() == null) {
            fail("Robolectric must have installed a main looper before this test runs");
        }
    }
}

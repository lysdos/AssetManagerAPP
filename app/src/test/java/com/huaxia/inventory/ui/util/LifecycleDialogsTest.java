package com.huaxia.inventory.ui.util;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: 一次性 {@link AlertDialog} 必须绑定 Fragment 的 View 生命周期 —
 * 宿主销毁 (旋转 / 切 tab) 时自动 dismiss, 避免 {@code WindowLeaked} 且弹窗凭空遗留。
 * 另断言 4 个确认弹窗站点 (共 6 处) 均改走 {@link LifecycleDialogs}。
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class LifecycleDialogsTest {

    @Test
    public void dialog_isDismissedWhenHostViewDestroyed() {
        ActivityController<FragmentActivity> controller =
                Robolectric.buildActivity(FragmentActivity.class).setup();
        FragmentActivity activity = controller.get();
        TestFragment frag = new TestFragment();
        activity.getSupportFragmentManager().beginTransaction()
                .add(android.R.id.content, frag, "t").commitNow();
        assertTrue("fragment must be added", frag.isAdded());

        AlertDialog dialog = new AlertDialog.Builder(frag.requireContext())
                .setMessage("confirm").create();
        LifecycleDialogs.showDismissOnDestroy(frag, dialog);
        assertTrue("dialog should be showing after helper call", dialog.isShowing());

        // 模拟宿主销毁 (旋转/切 tab): Fragment 的 View 生命周期销毁 → helper 应 dismiss.
        controller.pause().stop().destroy();
        assertFalse("dialog must be dismissed when host view is destroyed",
                dialog.isShowing());
    }

    @Test
    public void allConfirmDialogs_routeThroughLifecycleHelper() throws Exception {
        String[] paths = {
                "src/main/java/com/huaxia/inventory/ui/task/TaskListFragment.java",
                "src/main/java/com/huaxia/inventory/ui/scan/ScanFragment.java",
                "src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java",
                "src/main/java/com/huaxia/inventory/ui/import_/ImportDialogFragment.java",
        };
        File projectRoot = new File(System.getProperty("user.dir")).getParentFile();
        for (String rel : paths) {
            File f = new File(projectRoot, "app/" + rel);
            assertTrue("source not found: " + f.getAbsolutePath(), f.exists());
            String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            assertTrue(rel + " must route its confirm dialog(s) through "
                            + "LifecycleDialogs.showDismissOnDestroy (旋转防泄漏). Source:\n" + src,
                    src.contains("LifecycleDialogs.showDismissOnDestroy("));
        }
    }

    /** 需要一个有 View 的 Fragment, 否则 getViewLifecycleOwner() 抛 IllegalStateException. */
    public static class TestFragment extends Fragment {
        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater,
                                 @Nullable ViewGroup container,
                                 @Nullable Bundle savedInstanceState) {
            return new View(inflater.getContext());
        }
    }
}

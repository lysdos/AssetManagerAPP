package com.huaxia.inventory.ui.util;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

/**
 * 一次性 {@link AlertDialog} 的生命周期托管助手。
 *
 * <p>裸 {@code new AlertDialog.Builder(...).show()} 不归 FragmentManager 管: 宿主
 * View 销毁 (屏幕旋转 / 底部导航切走) 时弹窗既不重建、也不释放, 会泄漏 Window
 * ({@code WindowLeaked}) 并让弹窗凭空消失。这里把 dialog 绑到 Fragment 的
 * <b>View 生命周期</b> (不是 Fragment 生命周期) — 视图销毁即 dismiss, 且 observer
 * 随 View 一起回收, 不会像 {@code getLifecycle()} 那样随每次打开累积。</p>
 */
public final class LifecycleDialogs {

    private LifecycleDialogs() {}

    /** 显示 {@code dialog}, 并在宿主 Fragment 的 View 销毁时自动 dismiss。 */
    public static void showDismissOnDestroy(@NonNull Fragment fragment,
                                            @NonNull AlertDialog dialog) {
        dialog.show();
        fragment.getViewLifecycleOwner().getLifecycle().addObserver(
                new DefaultLifecycleObserver() {
                    @Override
                    public void onDestroy(@NonNull LifecycleOwner owner) {
                        if (dialog.isShowing()) dialog.dismiss();
                    }
                });
    }
}

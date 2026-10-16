package com.huaxia.inventory.ui.result;

import android.app.Dialog;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.huaxia.inventory.R;
import com.huaxia.inventory.data.entity.Asset;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 行为防回归: AssetDetailDialog 只读模式 —— 字段不可编辑、保存按钮隐藏、关闭按钮保留;
 * 非只读模式不受影响。
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class AssetDetailDialogReadOnlyTest {

    private static final int[] EDIT_TEXTS = {
            R.id.dlgEpcEt, R.id.dlgTypeEt, R.id.dlgModelEt,
            R.id.dlgSerialEt, R.id.dlgBorrowDeptEt, R.id.dlgBorrowerEt,
    };

    private ActivityController<AppCompatActivity> activityController;
    private Dialog dialog;

    @After
    public void teardown() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        if (activityController != null) activityController.pause().stop().destroy();
    }

    @Test
    public void readOnly_disablesEditsAndHidesSave() {
        show(true);
        for (int id : EDIT_TEXTS) {
            assertFalse("EditText 在只读模式下应不可编辑", dialog.findViewById(id).isEnabled());
        }
        assertEquals(View.GONE, dialog.findViewById(R.id.dlgSaveBtn).getVisibility());
        assertTrue("关闭按钮应保留可用", dialog.findViewById(R.id.dlgCancelBtn).isEnabled());
    }

    @Test
    public void editable_keepsFieldsEnabledAndSaveVisible() {
        show(false);
        for (int id : EDIT_TEXTS) {
            assertTrue("非只读模式下 EditText 应可编辑", dialog.findViewById(id).isEnabled());
        }
        assertEquals(View.VISIBLE, dialog.findViewById(R.id.dlgSaveBtn).getVisibility());
    }

    private void show(boolean readOnly) {
        activityController = Robolectric.buildActivity(AppCompatActivity.class);
        activityController.get().setTheme(R.style.Theme_HuaxiaInventory);
        activityController.create().start().resume();
        Fragment host = new Fragment();
        FragmentManager fm = activityController.get().getSupportFragmentManager();
        fm.beginTransaction().add(android.R.id.content, host).commitNow();

        AssetDetailDialog.show(host, asset(), true, readOnly, (pending, completion) -> {});
        dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
    }

    private static Asset asset() {
        Asset a = new Asset();
        a.id = 42L;
        a.taskId = 7L;
        a.epc = "1234567890123456";
        a.assetType = "类型";
        a.rawRowJson = "{}";
        return a;
    }
}

package com.huaxia.inventory.ui.result;

import android.app.Dialog;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.huaxia.inventory.R;
import com.huaxia.inventory.data.entity.ScanRecord;

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
 * 行为防回归: ExtraDetailDialog 只读模式 —— 字段不可编辑, 补全保存 + 单条删除入口隐藏;
 * 非只读模式不受影响。
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ExtraDetailDialogReadOnlyTest {

    private static final int[] EDIT_TEXTS = {
            R.id.extraDlgEpcEt, R.id.extraDlgTypeEt, R.id.extraDlgModelEt,
            R.id.extraDlgSerialEt, R.id.extraDlgDeptEt, R.id.extraDlgBorrowerEt,
    };

    private ActivityController<AppCompatActivity> activityController;
    private Dialog dialog;

    @After
    public void teardown() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        if (activityController != null) activityController.pause().stop().destroy();
    }

    @Test
    public void readOnly_disablesEditsAndHidesSaveAndDelete() {
        show(true);
        for (int id : EDIT_TEXTS) {
            assertFalse("EditText 在只读模式下应不可编辑", dialog.findViewById(id).isEnabled());
        }
        assertEquals(View.GONE, dialog.findViewById(R.id.extraDlgDeleteBtn).getVisibility());
        assertEquals(View.GONE, dialog.findViewById(R.id.extraDlgSaveBtn).getVisibility());
        assertTrue("取消按钮应保留可用", dialog.findViewById(R.id.extraDlgCancelBtn).isEnabled());
    }

    @Test
    public void editable_keepsFieldsEnabledAndActionsVisible() {
        show(false);
        for (int id : EDIT_TEXTS) {
            assertTrue("非只读模式下 EditText 应可编辑", dialog.findViewById(id).isEnabled());
        }
        assertEquals(View.VISIBLE, dialog.findViewById(R.id.extraDlgDeleteBtn).getVisibility());
        assertEquals(View.VISIBLE, dialog.findViewById(R.id.extraDlgSaveBtn).getVisibility());
    }

    private void show(boolean readOnly) {
        activityController = Robolectric.buildActivity(AppCompatActivity.class);
        activityController.get().setTheme(R.style.Theme_HuaxiaInventory);
        activityController.create().start().resume();
        Fragment host = new Fragment();
        FragmentManager fm = activityController.get().getSupportFragmentManager();
        fm.beginTransaction().add(android.R.id.content, host).commitNow();

        ExtraDetailDialog.show(host, data(), readOnly, claim -> {}, () -> {});
        dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
    }

    private static ExtraWithClaim data() {
        ScanRecord r = new ScanRecord();
        r.id = 1L;
        r.taskId = 7L;
        r.epc = "1234567890123456";
        r.scannedAt = 1L;
        r.rssi = "-50";
        return new ExtraWithClaim(r, null);
    }
}

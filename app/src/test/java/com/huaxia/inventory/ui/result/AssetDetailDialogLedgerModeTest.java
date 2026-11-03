package com.huaxia.inventory.ui.result;

import android.app.Dialog;
import android.view.View;
import android.widget.TextView;

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
 * 资产 tab 的 ledger 模式: EPC 只读、状态行隐藏、任务提示、已完成只读;
 * 同时回归结果页原有 4/5 参 show 不受影响.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class AssetDetailDialogLedgerModeTest {

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
    public void poolLedger_epcReadOnly_otherFieldsEditable_statusHidden() {
        showLedger(AssetDetailDialog.LedgerOptions.pool(), asset(0L));

        assertFalse(dialog.findViewById(R.id.dlgEpcEt).isEnabled());
        assertTrue(dialog.findViewById(R.id.dlgTypeEt).isEnabled());
        assertTrue(dialog.findViewById(R.id.dlgModelEt).isEnabled());
        assertTrue(dialog.findViewById(R.id.dlgSerialEt).isEnabled());
        assertTrue(dialog.findViewById(R.id.dlgBorrowDeptEt).isEnabled());
        assertTrue(dialog.findViewById(R.id.dlgBorrowerEt).isEnabled());
        assertEquals(View.GONE, dialog.findViewById(R.id.dlgInventoryStatusRow).getVisibility());
        assertEquals(View.VISIBLE, dialog.findViewById(R.id.dlgEpcReadonlyHintTv).getVisibility());
        assertEquals(View.VISIBLE, dialog.findViewById(R.id.dlgTaskHintTv).getVisibility());
        assertEquals(View.VISIBLE, dialog.findViewById(R.id.dlgSaveBtn).getVisibility());
        assertTrue(text(R.id.dlgTaskHintTv).contains("库存池"));
        assertEquals("保存修改", text(R.id.dlgSaveBtn));
    }

    @Test
    public void completedLedger_isReadOnlyWithHint() {
        showLedger(AssetDetailDialog.LedgerOptions.completed("已完成任务"), asset(7L));

        for (int id : EDIT_TEXTS) {
            assertFalse("已完成任务资产应只读", dialog.findViewById(id).isEnabled());
        }
        assertEquals(View.GONE, dialog.findViewById(R.id.dlgSaveBtn).getVisibility());
        assertEquals(View.GONE, dialog.findViewById(R.id.dlgInventoryStatusRow).getVisibility());
        assertEquals("关闭", text(R.id.dlgCancelBtn));
        assertTrue(text(R.id.dlgTaskHintTv).contains("已完成任务"));
    }

    @Test
    public void resultPageMode_keepsStatusRowAndEditableEpc() {
        showResult(asset(7L));

        assertEquals(View.VISIBLE, dialog.findViewById(R.id.dlgInventoryStatusRow).getVisibility());
        assertTrue(dialog.findViewById(R.id.dlgEpcEt).isEnabled());
        assertEquals(View.VISIBLE, dialog.findViewById(R.id.dlgSaveBtn).getVisibility());
        assertEquals("保存", text(R.id.dlgSaveBtn));
    }

    private void showLedger(AssetDetailDialog.LedgerOptions options, Asset asset) {
        Fragment host = host();
        AssetDetailDialog.showLedger(host, asset, options, (pending, completion) -> {});
        dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
    }

    private void showResult(Asset asset) {
        Fragment host = host();
        AssetDetailDialog.show(host, asset, true, false, (pending, completion) -> {});
        dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
    }

    private Fragment host() {
        activityController = Robolectric.buildActivity(AppCompatActivity.class);
        activityController.get().setTheme(R.style.Theme_HuaxiaInventory);
        activityController.create().start().resume();
        Fragment host = new Fragment();
        FragmentManager fm = activityController.get().getSupportFragmentManager();
        fm.beginTransaction().add(android.R.id.content, host).commitNow();
        return host;
    }

    private String text(int id) {
        return ((TextView) dialog.findViewById(id)).getText().toString();
    }

    private static Asset asset(long taskId) {
        Asset a = new Asset();
        a.id = 42L;
        a.taskId = taskId;
        a.epc = "1234567890123456";
        a.assetType = "类型";
        a.rawRowJson = "{}";
        return a;
    }
}

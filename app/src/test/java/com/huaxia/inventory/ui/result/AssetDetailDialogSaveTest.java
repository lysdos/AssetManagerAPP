package com.huaxia.inventory.ui.result;

import android.app.Dialog;
import android.widget.EditText;

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

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class AssetDetailDialogSaveTest {

    private ActivityController<AppCompatActivity> activityController;
    private Dialog dialog;

    @After
    public void teardown() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        if (activityController != null) {
            activityController.pause().stop().destroy();
        }
    }

    @Test
    public void save_usesCopyAndWaitsForSuccess() {
        Asset original = asset();
        String originalJson = original.rawRowJson;
        AtomicReference<Asset> pendingRef = new AtomicReference<>();
        AtomicReference<AssetDetailDialog.SaveCompletion> completionRef = new AtomicReference<>();

        show(original, (pending, completion) -> {
            pendingRef.set(pending);
            completionRef.set(completion);
        });
        EditText epcEt = dialog.findViewById(R.id.dlgEpcEt);
        epcEt.setText("1234567890123457");
        EditText typeEt = dialog.findViewById(R.id.dlgTypeEt);
        typeEt.setText("新类型");

        dialog.findViewById(R.id.dlgSaveBtn).performClick();

        Asset pending = pendingRef.get();
        assertNotNull(pending);
        assertNotSame(original, pending);
        assertEquals(42L, pending.id);
        assertEquals(7L, pending.taskId);
        assertEquals(3, pending.excelRowIndex);
        assertEquals(original.inventoryTime, pending.inventoryTime);
        assertEquals(original.createdAt, pending.createdAt);
        assertEquals("1234567890123456", original.epc);
        assertEquals("旧类型", original.assetType);
        assertEquals(originalJson, original.rawRowJson);
        assertEquals("新类型", pending.assetType);
        assertTrue(dialog.isShowing());
        assertFalse(dialog.findViewById(R.id.dlgSaveBtn).isEnabled());
        assertFalse(dialog.findViewById(R.id.dlgCancelBtn).isEnabled());

        completionRef.get().onSuccess();

        assertFalse(dialog.isShowing());
    }

    @Test
    public void duplicateEpc_keepsDialogOpenAndShowsInlineError() {
        AtomicReference<AssetDetailDialog.SaveCompletion> completionRef = new AtomicReference<>();
        show(asset(), (pending, completion) -> completionRef.set(completion));

        dialog.findViewById(R.id.dlgSaveBtn).performClick();
        completionRef.get().onDuplicateEpc();

        EditText epcEt = dialog.findViewById(R.id.dlgEpcEt);
        assertTrue(dialog.isShowing());
        assertTrue(dialog.findViewById(R.id.dlgSaveBtn).isEnabled());
        assertTrue(dialog.findViewById(R.id.dlgCancelBtn).isEnabled());
        assertEquals("该固资编号已存在，请修改后重试", epcEt.getError().toString());
    }

    @Test
    public void saveError_keepsDialogOpenAndRestoresControls() {
        AtomicReference<AssetDetailDialog.SaveCompletion> completionRef = new AtomicReference<>();
        show(asset(), (pending, completion) -> completionRef.set(completion));

        dialog.findViewById(R.id.dlgSaveBtn).performClick();
        completionRef.get().onError();

        assertTrue(dialog.isShowing());
        assertTrue(dialog.findViewById(R.id.dlgSaveBtn).isEnabled());
        assertTrue(dialog.findViewById(R.id.dlgCancelBtn).isEnabled());
    }

    private void show(Asset asset, AssetDetailDialog.SaveCallback callback) {
        activityController = Robolectric.buildActivity(AppCompatActivity.class);
        activityController.get().setTheme(R.style.Theme_HuaxiaInventory);
        activityController.create().start().resume();
        Fragment host = new Fragment();
        FragmentManager fm = activityController.get().getSupportFragmentManager();
        fm.beginTransaction().add(android.R.id.content, host).commitNow();

        AssetDetailDialog.show(host, asset, true, callback);
        dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
    }

    private static Asset asset() {
        Asset asset = new Asset();
        asset.id = 42L;
        asset.taskId = 7L;
        asset.excelRowIndex = 3;
        asset.epc = "1234567890123456";
        asset.assetType = "旧类型";
        asset.assetModel = "型号";
        asset.serialNumber = "序列号";
        asset.custodian = "领用人";
        asset.dept = "部门";
        asset.rawRowJson = "{\"固资编号\":\"1234567890123456\",\"设备类型\":\"旧类型\"}";
        asset.inventoryTime = 123L;
        asset.createdAt = 456L;
        return asset;
    }
}

package com.huaxia.inventory.ui.asset;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.chip.Chip;
import com.huaxia.inventory.R;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 资产 tab 的所有 XML 必须在 Material 主题下可正常 inflate.
 * 覆盖 Chip 样式/ChipGroup/渐变背景/搜索框/分页条等资源引用错误.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class AssetOverviewLayoutTest {

    private ActivityController<AppCompatActivity> activityController;

    @After
    public void teardown() {
        if (activityController != null) activityController.pause().stop().destroy();
    }

    @Test
    public void fragmentAndChildLayoutsInflate() {
        activityController = Robolectric.buildActivity(AppCompatActivity.class);
        activityController.get().setTheme(R.style.Theme_HuaxiaInventory);
        activityController.create().start().resume();
        LayoutInflater inflater = LayoutInflater.from(activityController.get());

        View overview = inflater.inflate(R.layout.fragment_asset_overview, null, false);
        assertNotNull(overview.findViewById(R.id.assetHeroNumTv));
        assertNotNull(overview.findViewById(R.id.assetDeptBarsContainer));
        assertNotNull(overview.findViewById(R.id.assetTypeChipGroup));
        assertNotNull(overview.findViewById(R.id.assetSearchEt));
        assertNotNull(overview.findViewById(R.id.assetListContainer));
        assertNotNull(overview.findViewById(R.id.assetPagerRow));
        assertNotNull(overview.findViewById(R.id.assetListCard));

        View row = inflater.inflate(R.layout.view_asset_overview_row, null, false);
        assertNotNull(row.findViewById(R.id.assetRowTitleTv));
        assertNotNull(row.findViewById(R.id.assetRowMetaTv));

        View bar = inflater.inflate(R.layout.view_asset_dept_bar, null, false);
        assertNotNull(bar.findViewById(R.id.barDeptFill));
        assertNotNull(bar.findViewById(R.id.barDeptRemainder));

        View deptRow = inflater.inflate(R.layout.view_asset_dept_dialog_row, null, false);
        assertNotNull(deptRow.findViewById(R.id.deptDialogNameTv));

        View deptDialog = inflater.inflate(R.layout.dialog_asset_depts, null, false);
        assertNotNull(deptDialog.findViewById(R.id.deptDialogSearchEt));
        assertNotNull(deptDialog.findViewById(R.id.deptDialogListContainer));

        View exportDialog = inflater.inflate(R.layout.dialog_export_assets, null, false);
        assertNotNull(exportDialog.findViewById(R.id.exportAllOption));
        assertNotNull(exportDialog.findViewById(R.id.exportAllCountTv));
        assertNotNull(exportDialog.findViewById(R.id.exportFilteredOption));
        assertNotNull(exportDialog.findViewById(R.id.exportFilteredCountTv));
        assertNotNull(exportDialog.findViewById(R.id.exportFilterDescTv));
        assertNotNull(exportDialog.findViewById(R.id.exportCancelBtn));

        // 与 HTML mockup 一致：右侧为蓝色「导出」按钮，不再是 ›
        TextView allGo = exportDialog.findViewById(R.id.exportAllGoTv);
        TextView filteredGo = exportDialog.findViewById(R.id.exportFilteredGoTv);
        assertNotNull(allGo);
        assertNotNull(filteredGo);
        assertEquals("导出", allGo.getText().toString());
        assertEquals("导出", filteredGo.getText().toString());

        View typeChip = inflater.inflate(R.layout.view_asset_type_chip, null, false);
        assertTrue(typeChip instanceof Chip);

        View filterChip = inflater.inflate(R.layout.view_asset_filter_chip, null, false);
        assertTrue(filterChip instanceof Chip);
    }
}

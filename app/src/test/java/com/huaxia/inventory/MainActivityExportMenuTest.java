package com.huaxia.inventory;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归：齿轮下拉必须保留「导出资产」入口，并由 MainActivity 打开导出弹窗。
 */
public class MainActivityExportMenuTest {

    @Test
    public void popupSettings_hasExportAssetsItem() throws Exception {
        String menu = read("src/main/res/menu/popup_settings.xml");
        assertTrue("popup_settings.xml 缺 menu_action_export_assets. Source:\n" + menu,
                menu.contains("@+id/menu_action_export_assets"));
        assertTrue("popup_settings.xml 缺导出资产标题. Source:\n" + menu,
                menu.contains("@string/menu_action_export_assets"));
    }

    @Test
    public void mainActivity_opensExportDialog() throws Exception {
        String src = read("src/main/java/com/huaxia/inventory/MainActivity.java");
        assertTrue("MainActivity 必须处理 menu_action_export_assets. Source:\n" + src,
                src.contains("R.id.menu_action_export_assets"));
        assertTrue("MainActivity 必须打开 AssetExportDialogFragment. Source:\n" + src,
                src.contains("new AssetExportDialogFragment()"));
    }

    private static String read(String rel) throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File f = new File(moduleDir, rel);
        if (!f.exists()) f = new File(moduleDir, "app/" + rel);
        assertTrue("source not found: " + f.getAbsolutePath(), f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}

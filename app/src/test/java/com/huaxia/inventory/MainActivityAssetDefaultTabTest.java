package com.huaxia.inventory;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: 资产 tab 必须是底部第一项, 且冷启动默认落在资产 tab.
 *
 * <p>资产 tab 是第一项时, BottomNavigationView 可能在监听器挂上之前就自动选中它,
 * 导致 setSelectedItemId 不派发回调; 因此 MainActivity 还需要容器为空时手动
 * showFragment 的兜底.</p>
 */
public class MainActivityAssetDefaultTabTest {

    @Test
    public void bottomNav_hasAssetFirst() throws Exception {
        File f = resolve("src/main/res/menu/bottom_nav.xml");
        String text = read(f);
        int asset = text.indexOf("@+id/nav_asset");
        int task = text.indexOf("@+id/nav_task");
        assertTrue("bottom_nav.xml 缺 nav_asset. Source:\n" + text, asset >= 0);
        assertTrue("bottom_nav.xml 缺 nav_task. Source:\n" + text, task >= 0);
        assertTrue("nav_asset 必须是第一项 (出现在 nav_task 之前). Source:\n" + text,
                asset < task);
    }

    @Test
    public void mainActivity_defaultsToAssetWithFallback() throws Exception {
        File f = resolve("src/main/java/com/huaxia/inventory/MainActivity.java");
        String text = read(f);

        assertTrue("MainActivity 必须冷启动选中 nav_asset. Source:\n" + text,
                text.contains("setSelectedItemId(R.id.nav_asset)"));
        assertTrue("MainActivity 必须包含 nav_asset -> AssetOverviewFragment 分支. Source:\n" + text,
                text.contains("R.id.nav_asset") && text.contains("new AssetOverviewFragment()")
                        && text.contains("\"asset\""));
        assertTrue("MainActivity 必须有容器为空时手动 showFragment 的兜底. Source:\n" + text,
                text.contains("findFragmentById(R.id.fragmentContainer) == null"));
        assertTrue("nav_task 分支仍必须清除 viewingTaskId (查看已完成任务上下文). Source:\n" + text,
                text.contains("setViewingTaskId(null)"));

        // setSelectedItemId 会同步派发导航回调, 而 replace 是异步提交. 必须先默认选中
        // 再挂监听器, 否则容器判空兜底会再 show 一次, 冷启动创建两个资产 Fragment.
        int setDefault = text.indexOf("setSelectedItemId(R.id.nav_asset)");
        int listener = text.indexOf("setOnItemSelectedListener");
        assertTrue("默认选中必须放在 setOnItemSelectedListener 之前. Source:\n" + text,
                setDefault >= 0 && listener > setDefault);
    }

    private static File resolve(String rel) {
        File moduleDir = new File(System.getProperty("user.dir"));
        File f = new File(moduleDir, rel);
        if (!f.exists()) f = new File(moduleDir, "app/" + rel);
        assertTrue("source not found: " + f.getAbsolutePath(), f.exists());
        return f;
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}

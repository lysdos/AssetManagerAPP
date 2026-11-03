package com.huaxia.inventory.ui.asset;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 资产类型 emoji 映射, 对齐 mockup 的 assetIcon(). */
public class AssetIconsTest {

    private static final String DESKTOP = "🖥";
    private static final String PRINTER = "🖨";
    private static final String SERVER = "🗄";

    @Test
    public void mapsMockupCategories() {
        assertEquals("💻", AssetIcons.forType("笔记本电脑"));
        assertEquals("💻", AssetIcons.forType("便携机"));
        assertEquals("📺", AssetIcons.forType("显示器"));
        assertEquals(DESKTOP, AssetIcons.forType("台式电脑"));
        assertEquals(DESKTOP, AssetIcons.forType("微型机"));
        assertEquals(DESKTOP, AssetIcons.forType("计算机终端"));
        assertEquals(PRINTER, AssetIcons.forType("激光打印机"));
        assertEquals(PRINTER, AssetIcons.forType("复印机"));
        assertEquals(PRINTER, AssetIcons.forType("扫描仪"));
        assertEquals(SERVER, AssetIcons.forType("服务器"));
        assertEquals(SERVER, AssetIcons.forType("磁盘阵列"));
        assertEquals("📡", AssetIcons.forType("交换机"));
        assertEquals("📡", AssetIcons.forType("无线路由器"));
        assertEquals("📡", AssetIcons.forType("防火墙"));
        assertEquals("📱", AssetIcons.forType("平板"));
        assertEquals("📱", AssetIcons.forType("手持机"));
        assertEquals("🔋", AssetIcons.forType("UPS电源"));
        assertEquals("📦", AssetIcons.forType("其他"));
    }

    @Test
    public void nullAndBlankFallBackToBox() {
        assertEquals("📦", AssetIcons.forType(null));
        assertEquals("📦", AssetIcons.forType(""));
        assertEquals("📦", AssetIcons.forType("   "));
    }

    @Test
    public void moreSpecificCategoryWins() {
        // “网络打印机”应先命中打印而不是网络
        assertEquals(PRINTER, AssetIcons.forType("网络打印机"));
        // “台式显示器”应先命中显示而不是台式
        assertEquals("📺", AssetIcons.forType("台式显示器"));
    }
}

package com.huaxia.inventory.ui.asset;

/**
 * 资产类型 → emoji 图标, 对齐 资产管理系统HTML界面.html 的 assetIcon().
 * 顺序有讲究: 先匹配更具体的类别 (如“网络打印机”应先命中打印).
 */
public final class AssetIcons {

    // 显式码点, 避免源文件/编辑器把 emoji 的 variation selector 吃掉
    private static final String DESKTOP = "🖥";
    private static final String PRINTER = "🖨";
    private static final String SERVER = "🗄";

    private AssetIcons() {}

    public static String forType(String type) {
        if (type == null) return "📦"; // 📦
        String t = type;
        if (t.contains("便携") || t.contains("笔记本")) return "💻"; // 💻
        if (t.contains("显示")) return "📺"; // 📺
        if (t.contains("台式") || t.contains("微型机")
                || t.contains("计算机") || t.contains("终端")) return DESKTOP;
        if (t.contains("打印") || t.contains("复印") || t.contains("扫描")) return PRINTER;
        if (t.contains("服务器") || t.contains("小型机")
                || t.contains("磁盘阵列") || t.contains("机柜")) return SERVER;
        if (t.contains("交换") || t.contains("路由") || t.contains("网络")
                || t.contains("无线") || t.contains("防火墙") || t.contains("网关")
                || t.contains("入侵") || t.contains("加密") || t.contains("模块")) {
            return "📡"; // 📡
        }
        if (t.contains("平板") || t.contains("手持")) return "📱"; // 📱
        if (t.contains("UPS") || t.contains("电源")) return "🔋"; // 🔋
        return "📦"; // 📦
    }
}

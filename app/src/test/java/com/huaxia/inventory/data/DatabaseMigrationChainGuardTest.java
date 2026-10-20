package com.huaxia.inventory.data;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: 数据库迁移链必须完整, 且"漏写迁移"不许退化成静默清库。
 *
 * <p>背景: {@code AppDatabase.version = 1} 是首次发布基线 (交付前把开发期的 v2..v11
 * 迁移链整体收敛掉了, 详见 {@code AppDatabase} javadoc)。发布之后每改一次表结构就要
 * {@code version} +1 并补一段迁移, 漏掉的后果是<b>用户升级时数据全没</b> —— 而且
 * 不崩溃、不报错, 只表现为"数据没了"。这条守卫把两个方向都钉住:</p>
 * <ul>
 *   <li>bump 了版本却忘写 / 忘注册迁移 → 下面的链条断言失败;</li>
 *   <li>写了迁移却忘 bump 版本 (迁移永远不会执行) → 终点版本越界断言失败;</li>
 *   <li>{@code InventoryApp} 用了裸的 destructive fallback (缺迁移时清库而非报错)
 *       → 第二个测试失败。</li>
 * </ul>
 *
 * <p>注意: {@code version = 1} 时第一条测试天然通过 (1→1 没有段落要检查) —— 这是正确的,
 * 不是空转: 它的价值从第一次 bump 到 2 开始生效。第二条测试今天就有效。</p>
 */
public class DatabaseMigrationChainGuardTest {

    /** AppDatabase.java 里 @Database 注解内的 version (javadoc 里也提到 version, 只取注解内的). */
    private static final Pattern VERSION_IN_ANNOTATION =
            Pattern.compile("@Database\\(([^)]*)\\)", Pattern.DOTALL);
    private static final Pattern VERSION_FIELD = Pattern.compile("version\\s*=\\s*(\\d+)");
    private static final Pattern MIGRATION_DECLARED = Pattern.compile("MIGRATION_(\\d+)_(\\d+)");
    private static final Pattern MIGRATION_REGISTERED =
            Pattern.compile("AppDatabase\\.MIGRATION_(\\d+)_(\\d+)");

    @Test
    public void migrationChainFromBaselineIsComplete_andRegistered() throws Exception {
        String dbText = read("src/main/java/com/huaxia/inventory/data/AppDatabase.java");
        String appText = read("src/main/java/com/huaxia/inventory/InventoryApp.java");

        int version = parseVersion(dbText);
        assertTrue("AppDatabase 的 @Database 注解里必须能解析到 version. Source:\n" + dbText,
                version >= 1);

        Set<String> declared = extractKeys(dbText, MIGRATION_DECLARED);
        Set<String> registered = extractKeys(appText, MIGRATION_REGISTERED);

        // (1) 1 → version 的每一段都必须有声明, 且已注册.
        for (int v = 2; v <= version; v++) {
            String key = (v - 1) + "_" + v;
            assertTrue("AppDatabase 缺 MIGRATION_" + key + " 声明 —— version 已 bump 到 "
                    + version + " 却没写这一段迁移, 用户升级会丢数据. Source:\n" + dbText,
                    declared.contains(key));
            assertTrue("InventoryApp 没注册 AppDatabase.MIGRATION_" + key + " —— 声明了"
                    + "但没注册等于没写, 用户升级会丢数据. Source:\n" + appText,
                    registered.contains(key));
        }

        // (2) 不得存在终点版本超过 version 的声明 (写了迁移却忘 bump 版本号 → 迁移永不执行).
        for (String key : declared) {
            int to = Integer.parseInt(key.substring(key.indexOf('_') + 1));
            assertTrue("MIGRATION_" + key + " 的终点版本 " + to + " > AppDatabase.version "
                    + version + " —— 写了迁移却忘了 bump 版本号? 这段迁移永远不会执行."
                    + " Source:\n" + dbText, to <= version);
        }

        // (3) 每个声明都必须被注册; (4) 不得注册未声明的.
        for (String key : declared) {
            assertTrue("MIGRATION_" + key + " 未在 InventoryApp 的 builder 注册. Source:\n" + appText,
                    registered.contains(key));
        }
        for (String key : registered) {
            assertTrue("InventoryApp 注册了 AppDatabase.MIGRATION_" + key
                    + " 但 AppDatabase 没声明它. Source:\n" + dbText, declared.contains(key));
        }
    }

    @Test
    public void inventoryApp_fallsBackToDestructiveOnlyOnDowngrade() throws Exception {
        String appText = read("src/main/java/com/huaxia/inventory/InventoryApp.java");

        assertTrue("InventoryApp 必须用 fallbackToDestructiveMigrationOnDowngrade() —— 降级"
                + "安装清库是合理的, 但升级路径缺迁移必须抛异常而不是清空用户数据."
                + " Source:\n" + appText,
                appText.contains("fallbackToDestructiveMigrationOnDowngrade()"));

        // 注意: 这里查的是带左括号的裸 API 调用; OnDowngrade 变体中间隔着 "OnDowngrade",
        // 不会误命中.
        assertFalse("InventoryApp 不得使用裸的 fallbackToDestructiveMigration( —— 它会把"
                + "'升级缺迁移'变成静默清空全部用户数据 (不崩溃/不报错). Source:\n" + appText,
                appText.contains("fallbackToDestructiveMigration("));
    }

    private static int parseVersion(String dbText) {
        Matcher annotation = VERSION_IN_ANNOTATION.matcher(dbText);
        while (annotation.find()) {
            Matcher field = VERSION_FIELD.matcher(annotation.group(1));
            if (field.find()) return Integer.parseInt(field.group(1));
        }
        return -1;
    }

    private static Set<String> extractKeys(String text, Pattern pattern) {
        Set<String> out = new HashSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) out.add(m.group(1) + "_" + m.group(2));
        return out;
    }

    private static String read(String rel) throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File f = new File(moduleDir, rel);
        if (!f.exists()) f = new File(moduleDir, "app/" + rel);
        assertTrue("source not found: " + f.getAbsolutePath(), f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}

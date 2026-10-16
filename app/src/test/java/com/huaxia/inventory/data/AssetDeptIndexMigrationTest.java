package com.huaxia.inventory.data;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: Asset 的 {@code (taskId, dept)} 索引 + v8→v9 迁移必须齐备。
 *
 * <p>少了迁移、或没在 builder 注册 MIGRATION_8_9 → v8 升级走 destructive fallback 丢数据;
 * 索引名/列与 {@code @Index} 不一致 → Room 运行时 schema 校验抛异常。三处必须同时成立。</p>
 */
public class AssetDeptIndexMigrationTest {

    @Test
    public void assetDeclaresTaskIdDeptIndex() throws Exception {
        String text = read("src/main/java/com/huaxia/inventory/data/entity/Asset.java");
        assertTrue("Asset must declare @Index(value = {\"taskId\", \"dept\"}). Source:\n" + text,
                text.contains("@Index(value = {\"taskId\", \"dept\"})"));
    }

    @Test
    public void appDatabaseVersionBumped_andMigration8_9Declared() throws Exception {
        String text = read("src/main/java/com/huaxia/inventory/data/AppDatabase.java");
        // version 必须是当前最新 (现为 11; 版本号另由 ScanRecordIndexTest / AssetSnapshotMigrationTest 卫护).
        assertTrue("AppDatabase version must be 11. Source:\n" + text,
                text.contains("version = 11"));
        assertTrue("AppDatabase must declare MIGRATION_8_9. Source:\n" + text,
                text.contains("MIGRATION_8_9"));
        assertTrue("MIGRATION_8_9 must CREATE INDEX `index_Asset_taskId_dept` ON `Asset` "
                        + "(`taskId`, `dept`). Source:\n" + text,
                text.contains("`index_Asset_taskId_dept`")
                        && text.contains("`Asset` (`taskId`, `dept`)"));
    }

    @Test
    public void inventoryAppRegistersMigration8_9() throws Exception {
        String text = read("src/main/java/com/huaxia/inventory/InventoryApp.java");
        assertTrue("InventoryApp builder must register AppDatabase.MIGRATION_8_9 "
                + "(否则 v8 升级走 destructive fallback 丢数据). Source:\n" + text,
                text.contains("AppDatabase.MIGRATION_8_9"));
    }

    private static String read(String rel) throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File f = new File(moduleDir, rel);
        if (!f.exists()) f = new File(moduleDir, "app/" + rel);
        assertTrue("source not found: " + f.getAbsolutePath(), f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}

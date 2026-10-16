package com.huaxia.inventory.data;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: Asset 的 7 个"快照列"(v9→v10)+ 迁移 + 注册必须齐备。
 *
 * <p>这组列支撑"划拨建快照 → 进行中编辑=草稿 → 完成=提交 → 删除/重置=回滚"语义。
 * 三处必须同时成立, 否则:</p>
 * <ul>
 *   <li>少了 {@code MIGRATION_9_10} 的某条 ADD COLUMN, 或没在 builder 注册
 *       → v9 升级走 destructive fallback 丢数据 / Room 运行时 schema 校验抛异常;</li>
 *   <li>{@code Asset} 实体缺某列 {@code @ColumnInfo(name = "orig_*")}
 *       → Room 编译期无法匹配迁移列, prepare 阶段报错。</li>
 * </ul>
 */
public class AssetSnapshotMigrationTest {

    /** 快照列的列名 (与 Asset.java @ColumnInfo / MIGRATION_9_10 严格一致). */
    private static final String[] SNAPSHOT_COLUMNS = {
            "orig_epc", "orig_asset_type", "orig_asset_model", "orig_serial_number",
            "orig_custodian", "orig_dept", "orig_raw_row_json",
    };

    @Test
    public void assetDeclaresSevenSnapshotColumns() throws Exception {
        String text = read("src/main/java/com/huaxia/inventory/data/entity/Asset.java");
        for (String col : SNAPSHOT_COLUMNS) {
            assertTrue("Asset must declare @ColumnInfo(name = \"" + col + "\"). Source:\n" + text,
                    text.contains("@ColumnInfo(name = \"" + col + "\")"));
        }
    }

    @Test
    public void appDatabaseVersionBumped_andMigration9_10Declared() throws Exception {
        String text = read("src/main/java/com/huaxia/inventory/data/AppDatabase.java");
        assertTrue("AppDatabase version must be 11. Source:\n" + text,
                text.contains("version = 11"));
        assertTrue("AppDatabase must declare MIGRATION_9_10. Source:\n" + text,
                text.contains("MIGRATION_9_10"));
        for (String col : SNAPSHOT_COLUMNS) {
            assertTrue("MIGRATION_9_10 must ALTER TABLE Asset ADD COLUMN " + col
                            + " TEXT. Source:\n" + text,
                    text.contains("ALTER TABLE Asset ADD COLUMN " + col + " TEXT"));
        }
    }

    @Test
    public void inventoryAppRegistersMigration9_10() throws Exception {
        String text = read("src/main/java/com/huaxia/inventory/InventoryApp.java");
        assertTrue("InventoryApp builder must register AppDatabase.MIGRATION_9_10 "
                + "(否则 v9 升级走 destructive fallback 丢数据). Source:\n" + text,
                text.contains("AppDatabase.MIGRATION_9_10"));
    }

    private static String read(String rel) throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File f = new File(moduleDir, rel);
        if (!f.exists()) f = new File(moduleDir, "app/" + rel);
        assertTrue("source not found: " + f.getAbsolutePath(), f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}

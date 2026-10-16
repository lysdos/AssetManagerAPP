package com.huaxia.inventory.data;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: ScanRecord 必须保留 {@code @Index(value = {"taskId", "matchedAssetId"})}
 * 复合索引, 让 {@code InventoryTaskDao.recomputeScannedCount} 的
 * {@code COUNT(DISTINCT matchedAssetId) WHERE taskId=? AND matchedAssetId IS NOT NULL}
 * 走 index-only scan — 否则单列索引只能 index-OR, 1w+ ScanRecord 任务变成全索引扫描.
 * 同时 AppDatabase.version 必须 bump 到当前版本且 MIGRATION_5_6 必须注册 —
 * 否则旧库 (v5) 读到新 schema 后 Room 会因未知索引列名抛 IllegalStateException.
 */
public class ScanRecordIndexTest {

    @Test
    public void scanRecordEntity_hasCompositeTaskIdMatchedAssetIdIndex() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/data/entity/ScanRecord.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/data/entity/ScanRecord.java");
        }
        assertTrue("ScanRecord.java not found: " + source.getAbsolutePath(),
                source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 必须包含复合索引声明 (与声明顺序无关).
        assertTrue("ScanRecord must declare @Index(value = {\"taskId\", \"matchedAssetId\"}) "
                + "for COUNT(DISTINCT matchedAssetId) WHERE taskId=? performance. Source:\n"
                + text,
                text.contains("@Index(value = {\"taskId\", \"matchedAssetId\"})"));
    }

    @Test
    public void paginationIndices_declaredOnEntities() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        String scanRec = readFile(moduleDir,
                "src/main/java/com/huaxia/inventory/data/entity/ScanRecord.java");
        String asset = readFile(moduleDir,
                "src/main/java/com/huaxia/inventory/data/entity/Asset.java");
        assertTrue("ScanRecord must declare @Index(value = {\"taskId\", \"scannedAt\"}) "
                + "for pageExtra/pageRecent ORDER BY. Source:\n" + scanRec,
                scanRec.contains("@Index(value = {\"taskId\", \"scannedAt\"})"));
        assertTrue("Asset must declare @Index(value = {\"taskId\", \"excelRowIndex\"}) "
                + "for pageAll/export pagination. Source:\n" + asset,
                asset.contains("@Index(value = {\"taskId\", \"excelRowIndex\"})"));
    }

    private static String readFile(File moduleDir, String rel) throws Exception {
        File f = new File(moduleDir, rel);
        if (!f.exists()) f = new File(moduleDir, "app/" + rel);
        assertTrue("source not found: " + f.getAbsolutePath(), f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void appDatabase_versionBumped_andMigrationsRegistered() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File db = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/data/AppDatabase.java");
        if (!db.exists()) {
            db = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/data/AppDatabase.java");
        }
        File app = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/InventoryApp.java");
        if (!app.exists()) {
            app = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/InventoryApp.java");
        }
        assertTrue("AppDatabase.java not found: " + db.getAbsolutePath(), db.exists());
        assertTrue("InventoryApp.java not found: " + app.getAbsolutePath(), app.exists());

        String dbText = new String(Files.readAllBytes(db.toPath()), StandardCharsets.UTF_8);
        String appText = new String(Files.readAllBytes(app.toPath()), StandardCharsets.UTF_8);

        // version 必须是当前最新 (v10 = ... v8 分页索引 + v9 Asset(taskId,dept) 索引
        // + v10 Asset 快照列), 否则 Room compile 无法对老库迁移.
        assertTrue("AppDatabase.version must be 10. Source:\n" + dbText,
                dbText.contains("version = 10"));
        // 必须声明 MIGRATION_5_6 (ScanRecord 复合索引).
        assertTrue("AppDatabase must declare MIGRATION_5_6. Source:\n" + dbText,
                dbText.contains("MIGRATION_5_6"));
        // InventoryApp 必须注册 MIGRATION_5_6, 否则 Room 不会跑它会走 fallbackToDestructiveMigration.
        assertTrue("InventoryApp must register AppDatabase.MIGRATION_5_6 in builder. Source:\n"
                + appText,
                appText.contains("AppDatabase.MIGRATION_5_6"));
        // 必须声明并注册 MIGRATION_6_7 (Asset.created_at).
        assertTrue("AppDatabase must declare MIGRATION_6_7. Source:\n" + dbText,
                dbText.contains("MIGRATION_6_7"));
        assertTrue("InventoryApp must register AppDatabase.MIGRATION_6_7 in builder. Source:\n"
                + appText,
                appText.contains("AppDatabase.MIGRATION_6_7"));
        // 必须声明并注册 MIGRATION_7_8 (分页复合索引).
        assertTrue("AppDatabase must declare MIGRATION_7_8. Source:\n" + dbText,
                dbText.contains("MIGRATION_7_8"));
        assertTrue("InventoryApp must register AppDatabase.MIGRATION_7_8 in builder. Source:\n"
                + appText,
                appText.contains("AppDatabase.MIGRATION_7_8"));
        // 必须声明并注册 MIGRATION_8_9 (Asset (taskId,dept) 索引).
        assertTrue("AppDatabase must declare MIGRATION_8_9. Source:\n" + dbText,
                dbText.contains("MIGRATION_8_9"));
        assertTrue("InventoryApp must register AppDatabase.MIGRATION_8_9 in builder. Source:\n"
                + appText,
                appText.contains("AppDatabase.MIGRATION_8_9"));
        // 必须声明并注册 MIGRATION_9_10 (Asset 快照列).
        assertTrue("AppDatabase must declare MIGRATION_9_10. Source:\n" + dbText,
                dbText.contains("MIGRATION_9_10"));
        assertTrue("InventoryApp must register AppDatabase.MIGRATION_9_10 in builder. Source:\n"
                + appText,
                appText.contains("AppDatabase.MIGRATION_9_10"));
    }
}

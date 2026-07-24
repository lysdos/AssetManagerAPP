package com.huaxia.inventory.data;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: AppDatabase 不再保留空的 MIGRATION_3_4 / MIGRATION_4_5 占位 —
 * 它们的注释曾经声称"由 fallbackToDestructiveMigration 处理", 但 builder
 * 实际从未注册, 等于事实上的 destructive fallback. 这两条注释给维护者
 * 造成误导, 必须删除.
 */
public class AppDatabaseMigration35Test {

    @Test
    public void appDatabase_doesNotDeclareEmptyMigrationStubs() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/data/AppDatabase.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/data/AppDatabase.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        assertFalse("AppDatabase must NOT have empty MIGRATION_3_4 stub — was never "
                + "registered in InventoryApp builder, so the path actually went "
                + "destructive fallback despite the misleading comment. Source:\n"
                + text, text.contains("MIGRATION_3_4"));

        assertFalse("AppDatabase must NOT have empty MIGRATION_4_5 stub — same "
                + "issue as MIGRATION_3_4. Source:\n" + text,
                text.contains("MIGRATION_4_5"));

        // 必须保留 MIGRATION_5_6 (真正注册的)
        assertTrue("AppDatabase must still declare the registered MIGRATION_5_6. "
                + "Source:\n" + text, text.contains("MIGRATION_5_6"));
    }
}

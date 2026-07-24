package com.huaxia.inventory.ui.import_;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: P3#3 私有化 InventoryApp 字段后, 唯一允许外部注入 db 的入口是
 * {@link ImportViewModel#ImportViewModel(android.app.Application,
 * com.huaxia.inventory.data.AppDatabase)} 这个 {@code @VisibleForTesting}
 * 构造器. _DiagnoseViewModelTest 走这条路径注入 in-memory db,
 * ViewModelProvider 生产路径仍走单参构造器.
 */
public class ImportViewModelDbOverrideTest {

    @Test
    public void importViewModel_exposesDbOverrideConstructorForTesting() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/import_/ImportViewModel.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/import_/ImportViewModel.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 必须有单参构造器委托给两参构造器: this(app, null)
        assertTrue("ImportViewModel(Application) must delegate to ImportViewModel(Application, db) "
                + "so single-arg path still gets db via InventoryApp.getDb(). Source:\n" + text,
                text.contains("this(app, null)"));

        // 必须有 (Application, AppDatabase) 构造器, 且标 @VisibleForTesting
        // — dbOverride != null 时走 override, 否则 fallback app.getDb().
        // 接受 {@code @VisibleForTesting} 或 fully-qualified
        // {@code @androidx.annotation.VisibleForTesting} 两种写法.
        assertTrue("ImportViewModel must have a @VisibleForTesting (or fully qualified) "
                + "marker on its 2-arg constructor. Source:\n" + text,
                (text.contains("@VisibleForTesting") || text.contains(
                        "@androidx.annotation.VisibleForTesting")) && text.contains(
                        "public ImportViewModel(@NonNull Application app, "
                                + "@Nullable AppDatabase dbOverride)"));

        // dbOverride 分支: dbOverride != null ? dbOverride : app.getDb()
        assertTrue("ImportViewModel two-arg constructor body must select dbOverride when "
                + "non-null, else fall back to app.getDb(). Source:\n" + text,
                text.contains("dbOverride != null ? dbOverride : ")
                        || text.contains("dbOverride != null ? dbOverride :"));
    }
}
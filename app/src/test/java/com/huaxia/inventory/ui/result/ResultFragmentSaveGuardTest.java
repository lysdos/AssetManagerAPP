package com.huaxia.inventory.ui.result;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

public class ResultFragmentSaveGuardTest {

    @Test
    public void assetSave_handlesDuplicateEpcAndDatabaseErrors() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java");
        }

        assertTrue("ResultFragment.java not found: " + source.getAbsolutePath(), source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        assertTrue("asset save must precheck global EPC uniqueness",
                text.contains("findByEpcGlobal"));
        assertTrue("asset save must catch the database uniqueness race",
                text.contains("catch (android.database.sqlite.SQLiteConstraintException"));
        assertTrue("duplicate EPC must be returned to the open dialog",
                text.contains("completion.onDuplicateEpc()")
                        || text.contains("completion::onDuplicateEpc"));
        assertTrue("other database failures must be returned to the open dialog",
                text.contains("completion.onError()")
                        || text.contains("completion::onError"));
    }
}

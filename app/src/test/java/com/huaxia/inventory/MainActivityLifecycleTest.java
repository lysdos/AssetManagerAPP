package com.huaxia.inventory;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MainActivityLifecycleTest {

    @Test
    public void mainActivity_doesNotReleaseApplicationOwnedResources() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/MainActivity.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/MainActivity.java");
        }

        assertTrue("MainActivity.java not found: " + source.getAbsolutePath(), source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        assertFalse("Activity lifecycle must not release Application-owned RFID",
                text.contains("rfid.release()"));
        assertFalse("Activity lifecycle must not stop Application-owned executors",
                text.contains("executors.shutdown()"));
    }
}

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
}

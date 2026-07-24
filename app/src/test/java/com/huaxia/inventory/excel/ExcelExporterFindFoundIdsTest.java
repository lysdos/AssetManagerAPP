package com.huaxia.inventory.excel;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: ExcelExporter.exportStreamed 入口的 matchedIds 构造必须走
 * {@code assetDao.findFoundIds(taskId)} 仅 id 投影 —
 * 旧路径 {@code for (Asset a : assetDao.findFound(taskId)) matchedIds.add(a.id);}
 * 全表加载 Asset 行 (含 rawRowJson ~500B-1KB/行), 30w+ 任务 ~150MB 峰值
 * 直接 OOM. 改用纯 id 投影后 ~2.4MB.
 *
 * <p>配套: AssetDao.findFoundIds 已声明
 * {@code SELECT Asset.id FROM Asset WHERE ...} 走 {@code (taskId, matchedAssetId)}
 * 复合索引.</p>
 */
public class ExcelExporterFindFoundIdsTest {

    @Test
    public void exportStreamed_matchesViaFindFoundIdsNotFindFound() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File exporter = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/excel/ExcelExporter.java");
        if (!exporter.exists()) {
            exporter = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/excel/ExcelExporter.java");
        }
        assertTrue("exporter not found", exporter.exists());
        String exporterText = new String(Files.readAllBytes(exporter.toPath()),
                StandardCharsets.UTF_8);

        // 必须有 findFoundIds 调用
        assertTrue("ExcelExporter.exportStreamed must call assetDao.findFoundIds(taskId) "
                + "— the old assetDao.findFound(taskId) loads full Asset rows including "
                + "rawRowJson, OOMs at 30w+ assets. Source:\n" + exporterText,
                exporterText.contains("assetDao.findFoundIds(taskId)"));

        // 必须不再出现 for-loop over findFound(taskId)
        assertFalse("ExcelExporter must NOT iterate assetDao.findFound(taskId) "
                + "to build matchedIds. Source:\n" + exporterText,
                exporterText.contains("for (Asset a : assetDao.findFound(taskId))"));

        // AssetDao 必须声明 findFoundIds 投影
        File dao = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/data/dao/AssetDao.java");
        if (!dao.exists()) {
            dao = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/data/dao/AssetDao.java");
        }
        assertTrue("AssetDao.java not found", dao.exists());
        String daoText = new String(Files.readAllBytes(dao.toPath()),
                StandardCharsets.UTF_8);
        assertTrue("AssetDao must declare findFoundIds(long) returning List<Long>. "
                + "Source:\n" + daoText,
                daoText.contains("List<Long> findFoundIds(long taskId)"));
    }
}

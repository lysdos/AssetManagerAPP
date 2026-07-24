package com.huaxia.inventory.data.dao;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: AssetDao.backfillFoundInventoryTime 必须改写为两步:
 * (1) {@code findFoundMinScanTimes} 一次性 GROUP BY 拿 (assetId, minScannedAt) —
 *     走 {@code (taskId, matchedAssetId)} 复合索引;
 * (2) 逐条 {@code setInventoryTimeIfNull} UPDATE, 走 Asset PK 短持锁.
 *
 * <p>原合约是单条 UPDATE 内嵌相关子查询 — 1w found 任务下 UPDATE 长持锁, 进入结果页
 * 期间会阻塞 RFID 写入. 不允许未来重构回退到那个模式.</p>
 */
public class AssetDaoBackfillTwoStepTest {

    @Test
    public void backfillUsesTwoStepImplementation() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/data/dao/AssetDao.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/data/dao/AssetDao.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // default 方法 + @Transaction 包裹 backfillFoundInventoryTime.
        assertTrue("AssetDao must declare backfillFoundInventoryTime as a @Transaction "
                + "default method (was @Query with embedded subquery). Source:\n" + text,
                text.contains("@androidx.room.Transaction")
                        && text.contains("default int backfillFoundInventoryTime(long taskId)"));
        assertTrue("backfillFoundInventoryTime default body must call findFoundMinScanTimes. "
                + "Source:\n" + text,
                text.contains("findFoundMinScanTimes(taskId)"));
        assertTrue("backfillFoundInventoryTime default body must call setInventoryTimeIfNull "
                + "per row. Source:\n" + text,
                text.contains("setInventoryTimeIfNull(r.assetId,")
                        || text.contains("setInventoryTimeIfNull(r.assetId ,")
                        || text.contains("setInventoryTimeIfNull( r.assetId,"));

        // 新 @Query 投影 (matchedAssetId AS assetId, MIN(scannedAt) AS minScannedAt).
        assertTrue("AssetDao must declare findFoundMinScanTimes with the (taskId, "
                + "matchedAssetId) composite-index-friendly SELECT. Source:\n" + text,
                text.contains("SELECT matchedAssetId AS assetId, MIN(scannedAt) AS minScannedAt"));
        assertTrue("findFoundMinScanTimes must use GROUP BY matchedAssetId. Source:\n"
                + text,
                text.contains("GROUP BY matchedAssetId"));

        // 幂等单条 UPDATE.
        assertTrue("AssetDao must declare setInventoryTimeIfNull with WHERE id=:id AND "
                + "inventory_time IS NULL — short持锁 + 幂等. Source:\n" + text,
                text.contains("UPDATE Asset SET inventory_time = :ts")
                        && text.contains("WHERE id = :id AND inventory_time IS NULL"));
    }

    @Test
    public void legacySingleSqlBackfillIsRemoved() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/data/dao/AssetDao.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/data/dao/AssetDao.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 旧的单 SQL 内嵌子查询不允许再出现 — 那是 1w+ found UPDATE 长持锁的来源.
        // 我们检查 "UPDATE Asset SET inventory_time = ( SELECT MIN" 这种关键组合不能存在.
        assertTrue("Legacy single SQL backfill must NOT be re-introduced. Source:\n" + text,
                !text.contains("UPDATE Asset SET inventory_time = (")
                        || !text.contains("SELECT MIN(scannedAt) FROM ScanRecord"));
    }
}

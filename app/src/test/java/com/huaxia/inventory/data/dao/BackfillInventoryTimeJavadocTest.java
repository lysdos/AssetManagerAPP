package com.huaxia.inventory.data.dao;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: AssetDao.backfillFoundInventoryTime 的 Javadoc 必须覆盖全部合约点 —
 * 幂等 (已有 inventory_time 不覆盖) / NULL 来源处理 (matchedAssetId IS NOT
 * NULL) / 并发 (行级锁 + 先到先得) / 空任务边界 / 已知 trade-off (孤儿清理 race).
 *
 * <p>这是导出"盘点时间"列的关键算法; 维护者改写时容易改坏任意一点, 需明确文档.</p>
 */
public class BackfillInventoryTimeJavadocTest {

    @Test
    public void backfillFoundInventoryTime_javadocCoversContract() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/data/dao/AssetDao.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/data/dao/AssetDao.java");
        }
        assertTrue("AssetDao not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // backfillFoundInventoryTime 是 @Transaction default 方法, 在 Javadoc 之前的 default 前.
        int idx = text.indexOf("default int backfillFoundInventoryTime(long taskId)");
        assertTrue("backfillFoundInventoryTime default method must exist. Source:\n"
                + text, idx >= 0);
        int javadocEnd = idx;

        // 找到这个 default 方法前的最近 /** 起点
        int javadocStart = text.lastIndexOf("/**", javadocEnd);
        assertTrue("default method must have a Javadoc block above. Source:\n"
                + text, javadocStart >= 0);
        String javadoc = text.substring(javadocStart, idx);

        // 关键合约点必须出现在 Javadoc 内
        assertTrue("Javadoc must mention idempotent / 幂等: 已有 inventory_time "
                + "不被覆盖. Javadoc:\n" + javadoc,
                javadoc.contains("幂等"));
        assertTrue("Javadoc must mention minimum scannedAt / 首次扫描时间. "
                + "Javadoc:\n" + javadoc,
                javadoc.contains("MIN(scannedAt)") || javadoc.contains("首次"));
        assertTrue("Javadoc must mention NULL handling via matchedAssetId IS NOT NULL. "
                + "Javadoc:\n" + javadoc,
                javadoc.contains("matchedAssetId IS NOT NULL")
                        || javadoc.contains("NULL 扫描"));
        assertTrue("Javadoc must mention transaction boundary / 并发 / 行级锁. "
                + "Javadoc:\n" + javadoc,
                javadoc.contains("@Transaction") || javadoc.contains("事务"));
        assertTrue("Javadoc must mention 0 affected rows (no found assets) edge case. "
                + "Javadoc:\n" + javadoc,
                javadoc.contains("0") && (javadoc.contains("found 资产")
                        || javadoc.contains("边界")));
    }
}

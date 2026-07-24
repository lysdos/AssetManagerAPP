package com.huaxia.inventory.data.dao;

/**
 * {@code SELECT matchedAssetId AS assetId, MIN(scannedAt) AS minScannedAt}
 * 投影映射类 — {@link AssetDao#findFoundMinScanTimes(long)} 返回类型.
 *
 * <p>字段名必须与 SELECT 列名严格对应 ({@code assetId} / {@code minScannedAt}).
 * 必须放在 dao 同 package 下作为顶级 public class, Room 才能识别.</p>
 */
public class AssetFoundMinScanTime {
    public long assetId;
    public long minScannedAt;
}

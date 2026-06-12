package com.huaxia.inventory.data.dao;

/**
 * ScanRecord 聚合统计，{@link ScanRecordDao#aggregateStats(long)} 的返回类型。
 *
 * 一次 SQL 拿到原来需要全表扫描三次才能得到的数据。
 *
 * <pre>
 *   SELECT
 *     COUNT(DISTINCT matchedAssetId) AS matchedCount,
 *     COALESCE(SUM(CASE WHEN matchedAssetId IS NULL THEN 1 ELSE 0 END), 0) AS extraCount,
 *     COALESCE(MAX(scannedAt), 0) AS lastScannedAt
 *   FROM ScanRecord WHERE taskId = :taskId
 * </pre>
 *
 * 空任务：matchedCount=0、extraCount=0、lastScannedAt=0。
 */
public class ScanAggregate {
    public int matchedCount;
    public int extraCount;
    public long lastScannedAt;
}

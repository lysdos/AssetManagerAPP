package com.huaxia.inventory.data.entity;

import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(indices = {
        @Index("taskId"),
        @Index("matchedAssetId"),
        // 复合索引: 让 recomputeScannedCount 的
        // COUNT(DISTINCT matchedAssetId) WHERE taskId=? AND matchedAssetId IS NOT NULL
        // 走 index-only scan, 否则单列 taskId + 单列 matchedAssetId 只能各自走索引 OR,
        // 1w+ ScanRecord 全索引扫描. 复合顺序按 SQL WHERE 等值选择度决定:
        // taskId 决定子集大小, matchedAssetId 决定去重粒度.
        @Index(value = {"taskId", "matchedAssetId"}),
        // (taskId, scannedAt): 服务 pageExtra / pageRecent 的 ORDER BY scannedAt [DESC],
        // 无此索引时每次翻页都要任务下全部 ScanRecord 排序。
        @Index(value = {"taskId", "scannedAt"}),
        @Index(value = {"taskId", "epc"}, unique = true)
})
public class ScanRecord {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "taskId")
    public long taskId;

    public String epc;
    public String tid;
    public String rssi;

    @ColumnInfo(name = "scannedAt")
    public long scannedAt;

    @Nullable
    @ColumnInfo(name = "matchedAssetId")
    public Long matchedAssetId;
}

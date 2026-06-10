package com.huaxia.inventory.data.entity;

import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(indices = {
        @Index("taskId"),
        @Index("matchedAssetId"),
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

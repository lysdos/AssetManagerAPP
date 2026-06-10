package com.huaxia.inventory.data.entity;

import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(indices = {
        @Index("taskId"),
        @Index(value = {"taskId", "epc"})
})
public class Asset {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "taskId")
    public long taskId;

    @ColumnInfo(name = "excelRowIndex")
    public int excelRowIndex;

    public String epc;
    public String assetName;
    public String assetType;
    public String assetModel;
    public String org;
    public String detailedLocation;
    public String placementLocation;
    public String assetStatus;

    @Nullable
    @ColumnInfo(name = "raw_row_json")
    public String rawRowJson;
}

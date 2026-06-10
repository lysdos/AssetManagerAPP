package com.huaxia.inventory.data.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity
public class InventoryTask {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "sourceFileName")
    public String sourceFileName;

    @ColumnInfo(name = "importedAt")
    public long importedAt;

    public String status;  // "IN_PROGRESS" / "COMPLETED"

    @ColumnInfo(name = "expectedCount")
    public int expectedCount;

    @ColumnInfo(name = "scannedUniqueCount")
    public int scannedUniqueCount;
}

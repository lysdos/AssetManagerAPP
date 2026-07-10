package com.huaxia.inventory.data.entity;

import androidx.annotation.Nullable;
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

    /**
     * 领用部门。NULL 表示不限部门（盘点全部资产）。
     * UI 层 Spinner 选项来自 {@code Asset.dept} DISTINCT。
     */
    @Nullable
    @ColumnInfo(name = "dept")
    public String dept;

    @ColumnInfo(name = "expectedCount")
    public int expectedCount;

    @ColumnInfo(name = "scannedUniqueCount")
    public int scannedUniqueCount;
}

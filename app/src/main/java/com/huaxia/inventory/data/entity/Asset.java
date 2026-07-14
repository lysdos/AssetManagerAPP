package com.huaxia.inventory.data.entity;

import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(indices = {
        @Index("taskId"),
        // epc 单列唯一 — 重复导入时按 epc 覆盖 (AssetDao.upsertOne ON CONFLICT(epc) DO UPDATE).
        // 注意: ON CONFLICT 子句明确排除 taskId / inventory_time,
        // 保留 Asset.id 不变 → ScanRecord.matchedAssetId FK 持续有效; 盘点时间不被抹.
        @Index(value = {"epc"}, unique = true)
})
public class Asset {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "taskId")
    public long taskId;

    @ColumnInfo(name = "excelRowIndex")
    public int excelRowIndex;

    public String epc;
    public String assetType;
    public String assetModel;
    public String serialNumber;
    public String custodian;
    public String dept;

    @Nullable
    @ColumnInfo(name = "raw_row_json")
    public String rawRowJson;

    /**
     * 盘点时间（毫秒）。null 表示该资产尚未被人工复核/编辑过。
     * 点击详情弹窗保存时填充：有最早扫描记录用 MIN(scannedAt)，否则用当前时间。
     * 导出时写入 Excel 「盘点时间」列。
     */
    @Nullable
    @ColumnInfo(name = "inventory_time")
    public Long inventoryTime;
}
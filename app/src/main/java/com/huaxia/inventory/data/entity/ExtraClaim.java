package com.huaxia.inventory.data.entity;

import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 盘盈补全信息。
 * <p>ScanRecord.matchedAssetId = NULL 的盘盈记录，可以被用户手动补全（录入部门/领用人/类型等）。
 * 一个 ScanRecord 对应 0 或 1 条 ExtraClaim（无补全时整行不创建）。
 *
 * <p>ScanRecord 行不会被删除或修改 — 盘盈记录始终保留在盘盈 tab，
 * "已补全"状态由 ExtraClaim 的存在与否体现。
 */
@Entity(indices = {
        @Index(value = {"scanRecordId"}, unique = true)
})
public class ExtraClaim {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "scanRecordId")
    public long scanRecordId;

    public String epc;
    public String assetType;
    public String assetModel;
    public String serialNumber;
    public String custodian;
    public String dept;

    /**
     * 补全时间（毫秒）。UI 列表不展示，但保留审计价值。
     */
    @Nullable
    @ColumnInfo(name = "claimedAt")
    public Long claimedAt;
}
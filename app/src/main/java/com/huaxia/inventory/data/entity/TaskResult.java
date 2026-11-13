package com.huaxia.inventory.data.entity;

import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 一次盘点的冻结结果 —— 完成任务时逐行写入，之后永不再改。
 *
 * <p>存在的理由：完成任务要把资产释放回库存池（让新任务能重新选），
 * 但结果页/导出仍需按任务查询。{@code Asset.taskId} 是单一归属，无法同时表达
 * 「资产现在在池里」和「它属于某次历史盘点」，所以用本表冻结一份。</p>
 *
 * <p>盘盈行（{@code status = 'EXTRA'}）没有对应资产，{@code assetId} 为 null；
 * 其业务字段来自用户补全的 {@code ExtraClaim}。</p>
 */
@Entity(indices = {
        @Index("taskId"),
        @Index(value = {"taskId", "status"}),
        @Index(value = {"taskId", "rowIndex"})
})
public class TaskResult {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long taskId;

    /** 原 {@code Asset.excelRowIndex}，保证导出顺序与原表一致；盘盈行固定 0。 */
    public int rowIndex;

    /** 盘盈行为 null。仅用于追溯，不参与任何查询。 */
    @Nullable
    public Long assetId;

    public String epc;

    @Nullable public String assetType;
    @Nullable public String assetModel;
    @Nullable public String serialNumber;
    @Nullable public String custodian;
    @Nullable public String dept;

    /** FOUND / MISSING / EXTRA */
    public String status;

    /** 已盘 / 盘亏行的盘点时间。 */
    @Nullable public Long inventoryTime;

    /** 盘盈行的扫描时间。 */
    @Nullable public Long scannedAt;

    /** 盘盈行的 RSSI。 */
    @Nullable public String rssi;

    /** 盘盈行的 TID（导出「电子标签」列用）。 */
    @Nullable public String tid;

    /** 导出时重建原始表头顺序用。 */
    @Nullable public String rawRowJson;
}

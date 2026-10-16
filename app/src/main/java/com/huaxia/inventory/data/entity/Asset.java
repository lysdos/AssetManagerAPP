package com.huaxia.inventory.data.entity;

import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(indices = {
        @Index("taskId"),
        // (taskId, dept): 服务 countPoolByDept / distinctDeptPool / distinctDept 的
        // `WHERE taskId = 0 AND dept = ?` 过滤 — 建任务时按部门统计池内资产走索引,
        // 不再全表扫描。索引名沿用 Room 默认命名 index_Asset_taskId_dept (迁移对齐)。
        @Index(value = {"taskId", "dept"}),
        // (taskId, excelRowIndex): 服务 pageAll/pageFound/pageMissing 的 ORDER BY
        // excelRowIndex, 以及 ExcelExporter 分页导出的每一页。无此索引时 SQLite 每次
        // 都要把任务的 N 行整体排序 (OFFSET 无法跳过排序), 导出成本 ≈ (N/500)×sort(N),
        // 随 N 线性放大; 有此索引则每页退化为 O(500) 的索引 seek。
        @Index(value = {"taskId", "excelRowIndex"}),
        // epc 单列唯一 — 重复导入时按 epc 覆盖 (AssetDao.upsertOne: INSERT OR IGNORE + UPDATE).
        // 注意: 覆盖路径明确排除 id / taskId / inventory_time / created_at,
        // 保留 Asset.id 不变 → ScanRecord.matchedAssetId FK 持续有效; 盘点/导入时间不被抹.
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

    /**
     * 首次导入时间（毫秒）。流式导入时整批共用一个时间戳。
     * 重复导入同一 epc 时 upsertOne 的覆盖路径明确不更新此列 —
     * 保留"首次见到该资产"的时间，供扫描页「在库设备」副标「+N 今日」统计
     * （N = 本任务内今天首次导入的资产数）。v6 之前的历史行升级后为 null，
     * 不参与"今日"统计。
     */
    @Nullable
    @ColumnInfo(name = "created_at")
    public Long createdAt;

    // ---- 快照列 (v10): 划拨进任务时保存的业务字段原值, 供"删除/重置任务"回滚 ----
    // 非空 = 该资产正处在某未提交(进行中/暂停)任务中, 可被回滚; 完成任务(提交)
    // 或删除任务时清空. 结果页编辑改的是上面的"当前列", 不动这里 — 原值因此得
    // 以保留到回滚那一刻. 导出只读"当前列", 不读快照.
    @Nullable
    @ColumnInfo(name = "orig_epc")
    public String origEpc;
    @Nullable
    @ColumnInfo(name = "orig_asset_type")
    public String origAssetType;
    @Nullable
    @ColumnInfo(name = "orig_asset_model")
    public String origAssetModel;
    @Nullable
    @ColumnInfo(name = "orig_serial_number")
    public String origSerialNumber;
    @Nullable
    @ColumnInfo(name = "orig_custodian")
    public String origCustodian;
    @Nullable
    @ColumnInfo(name = "orig_dept")
    public String origDept;
    @Nullable
    @ColumnInfo(name = "orig_raw_row_json")
    public String origRawRowJson;
}
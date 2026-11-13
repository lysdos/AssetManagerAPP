package com.huaxia.inventory.data;

import androidx.annotation.Nullable;

import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ExtraClaim;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.data.entity.TaskResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务结果读取的唯一入口。
 *
 * <p>任务状态决定数据来源：</p>
 * <ul>
 *   <li><b>进行中 / 暂停</b> —— 实时查 {@code Asset} 与 {@code ScanRecord}</li>
 *   <li><b>已完成</b> —— 查冻结快照 {@code TaskResult}（资产早已释放回库存池）</li>
 * </ul>
 *
 * <p>这个分支只应出现在本类里。散落到 ViewModel / 导出器 / Web API 各处必然漂移。</p>
 */
public class ResultRepository {

    public enum Tab { ALL, FOUND, MISSING, EXTRA }

    /** 统一口径：{@code expected} = 已盘 + 盘亏，不含盘盈。 */
    public static class Counts {
        public final int expected;
        public final int found;
        public final int missing;
        public final int extra;

        public Counts(int expected, int found, int missing, int extra) {
            this.expected = expected;
            this.found = found;
            this.missing = missing;
            this.extra = extra;
        }
    }

    /** 扁平结果行 —— Asset / ScanRecord / TaskResult 三种来源统一成这一种。 */
    public static class ResultRow {
        public String epc;
        public String assetType;
        public String assetModel;
        public String serialNumber;
        public String custodian;
        public String dept;
        public String status;
        @Nullable public Long inventoryTime;
        @Nullable public Long scannedAt;
        @Nullable public String rssi;
        @Nullable public String rawRowJson;
    }

    private final AppDatabase db;

    public ResultRepository(AppDatabase db) {
        this.db = db;
    }

    public Counts counts(long taskId, String taskStatus) {
        if (isCompleted(taskStatus)) {
            return new Counts(
                    db.taskResultDao().countExpected(taskId),
                    db.taskResultDao().countByStatus(taskId, "FOUND"),
                    db.taskResultDao().countByStatus(taskId, "MISSING"),
                    db.taskResultDao().countByStatus(taskId, "EXTRA"));
        }
        return new Counts(
                db.assetDao().countByTask(taskId),
                db.assetDao().countFound(taskId),
                db.assetDao().countMissing(taskId),
                db.scanRecordDao().countExtras(taskId));
    }

    public List<ResultRow> page(long taskId, String taskStatus, Tab tab,
                                int limit, int offset) {
        return isCompleted(taskStatus)
                ? pageFromSnapshot(taskId, tab, limit, offset)
                : pageFromLiveTables(taskId, tab, limit, offset);
    }

    private static boolean isCompleted(String taskStatus) {
        return "COMPLETED".equals(taskStatus);
    }

    private List<ResultRow> pageFromSnapshot(long taskId, Tab tab, int limit, int offset) {
        List<TaskResult> rows;
        switch (tab) {
            case FOUND:   rows = db.taskResultDao().pageFound(taskId, limit, offset); break;
            case MISSING: rows = db.taskResultDao().pageMissing(taskId, limit, offset); break;
            case EXTRA:   rows = db.taskResultDao().pageExtra(taskId, limit, offset); break;
            case ALL:
            default:      rows = db.taskResultDao().pageAll(taskId, limit, offset); break;
        }
        List<ResultRow> out = new ArrayList<>(rows.size());
        for (TaskResult r : rows) out.add(fromTaskResult(r));
        return out;
    }

    private List<ResultRow> pageFromLiveTables(long taskId, Tab tab, int limit, int offset) {
        if (tab == Tab.EXTRA) return extras(taskId, limit, offset);

        List<Asset> assets;
        Set<Long> foundIds = null;
        switch (tab) {
            case FOUND:
                assets = db.assetDao().pageFound(taskId, limit, offset);
                break;
            case MISSING:
                assets = db.assetDao().pageMissing(taskId, limit, offset);
                break;
            case ALL:
            default:
                assets = db.assetDao().pageAll(taskId, limit, offset);
                // ALL 需要逐行区分已盘/盘亏，取一次已盘 id 集合
                foundIds = new HashSet<>(db.assetDao().findFoundIds(taskId));
                break;
        }
        List<ResultRow> out = new ArrayList<>(assets.size());
        for (Asset a : assets) {
            ResultRow row = fromAsset(a);
            row.status = tab == Tab.FOUND ? "FOUND"
                    : tab == Tab.MISSING ? "MISSING"
                    : (foundIds != null && foundIds.contains(a.id) ? "FOUND" : "MISSING");
            out.add(row);
        }
        return out;
    }

    private List<ResultRow> extras(long taskId, int limit, int offset) {
        List<ScanRecord> records = db.scanRecordDao().pageExtra(taskId, limit, offset);
        if (records.isEmpty()) return new ArrayList<>();

        List<Long> ids = new ArrayList<>(records.size());
        for (ScanRecord s : records) ids.add(s.id);
        Map<Long, ExtraClaim> claims = new HashMap<>();
        for (ExtraClaim c : db.extraClaimDao().findByScanRecordIds(ids)) {
            claims.put(c.scanRecordId, c);
        }

        List<ResultRow> out = new ArrayList<>(records.size());
        for (ScanRecord s : records) {
            ExtraClaim c = claims.get(s.id);
            ResultRow row = new ResultRow();
            row.epc = c != null && c.epc != null ? c.epc : s.epc;
            row.assetType = c == null ? null : c.assetType;
            row.assetModel = c == null ? null : c.assetModel;
            row.serialNumber = c == null ? null : c.serialNumber;
            row.custodian = c == null ? null : c.custodian;
            row.dept = c == null ? null : c.dept;
            row.status = "EXTRA";
            row.scannedAt = s.scannedAt;
            row.rssi = s.rssi;
            out.add(row);
        }
        return out;
    }

    private static ResultRow fromAsset(Asset a) {
        ResultRow row = new ResultRow();
        row.epc = a.epc;
        row.assetType = a.assetType;
        row.assetModel = a.assetModel;
        row.serialNumber = a.serialNumber;
        row.custodian = a.custodian;
        row.dept = a.dept;
        row.inventoryTime = a.inventoryTime;
        row.rawRowJson = a.rawRowJson;
        return row;
    }

    private static ResultRow fromTaskResult(TaskResult r) {
        ResultRow row = new ResultRow();
        row.epc = r.epc;
        row.assetType = r.assetType;
        row.assetModel = r.assetModel;
        row.serialNumber = r.serialNumber;
        row.custodian = r.custodian;
        row.dept = r.dept;
        row.status = r.status;
        row.inventoryTime = r.inventoryTime;
        row.scannedAt = r.scannedAt;
        row.rssi = r.rssi;
        row.rawRowJson = r.rawRowJson;
        return row;
    }
}

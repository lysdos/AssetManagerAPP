package com.huaxia.inventory.data;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.data.entity.TaskResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ResultRepositoryTest {

    private AppDatabase db;
    private ResultRepository repo;
    private long taskId;

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries().build();
        repo = new ResultRepository(db);

        InventoryTask t = new InventoryTask();
        t.sourceFileName = "T";
        t.status = "IN_PROGRESS";
        t.expectedCount = 3;
        taskId = db.inventoryTaskDao().insert(t);
    }

    @After
    public void tearDown() { db.close(); }

    private Asset asset(String epc, int rowIndex) {
        Asset a = new Asset();
        a.taskId = taskId;
        a.excelRowIndex = rowIndex;
        a.epc = epc;
        a.dept = "行政部";
        db.assetDao().upsertOne(a);
        return db.assetDao().findByEpc(taskId, epc);
    }

    private void scan(String epc, Long matchedAssetId) {
        ScanRecord s = new ScanRecord();
        s.taskId = taskId;
        s.epc = epc;
        s.scannedAt = 1000L + (matchedAssetId == null ? 0 : matchedAssetId);
        s.matchedAssetId = matchedAssetId;
        db.scanRecordDao().insert(s);
    }

    private void seedInProgress() {
        Asset a1 = asset("E1", 1);
        asset("E2", 2);
        asset("E3", 3);
        scan("E1", a1.id);
        scan("X9", null);
    }

    @Test
    public void inProgress_countsComeFromLiveTables() {
        seedInProgress();

        ResultRepository.Counts c = repo.counts(taskId, "IN_PROGRESS");

        assertThat(c.expected).isEqualTo(3);
        assertThat(c.found).isEqualTo(1);
        assertThat(c.missing).isEqualTo(2);
        assertThat(c.extra).isEqualTo(1);
    }

    @Test
    public void inProgress_allPageMarksStatusPerRow() {
        seedInProgress();

        List<ResultRepository.ResultRow> rows =
                repo.page(taskId, "IN_PROGRESS", ResultRepository.Tab.ALL, 10, 0);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).epc).isEqualTo("E1");
        assertThat(rows.get(0).status).isEqualTo("FOUND");
        assertThat(rows.get(1).status).isEqualTo("MISSING");
    }

    @Test
    public void completed_countsComeFromSnapshot() {
        List<TaskResult> rows = new ArrayList<>();
        rows.add(snapshotRow("E1", 1, "FOUND"));
        rows.add(snapshotRow("E2", 2, "MISSING"));
        rows.add(snapshotRow("X9", 0, "EXTRA"));
        db.taskResultDao().insertAll(rows);

        ResultRepository.Counts c = repo.counts(taskId, "COMPLETED");

        assertThat(c.expected).isEqualTo(2);
        assertThat(c.found).isEqualTo(1);
        assertThat(c.missing).isEqualTo(1);
        assertThat(c.extra).isEqualTo(1);
    }

    @Test
    public void completed_allPageExcludesExtraAndKeepsOrder() {
        List<TaskResult> rows = new ArrayList<>();
        rows.add(snapshotRow("E2", 2, "MISSING"));
        rows.add(snapshotRow("E1", 1, "FOUND"));
        rows.add(snapshotRow("X9", 0, "EXTRA"));
        db.taskResultDao().insertAll(rows);

        List<ResultRepository.ResultRow> all =
                repo.page(taskId, "COMPLETED", ResultRepository.Tab.ALL, 10, 0);

        assertThat(all).extracting(r -> r.epc).containsExactly("E1", "E2");
        assertThat(repo.page(taskId, "COMPLETED", ResultRepository.Tab.EXTRA, 10, 0))
                .extracting(r -> r.epc).containsExactly("X9");
    }

    /**
     * 回归：进行中任务的行必须携带原始 Asset。
     *
     * <p>详情弹窗保存走整行 {@code @Update}；若 UI 拿到的是手工拼的 Asset，
     * taskId 会变成 0 —— 资产被踢出当前任务，导出里也就没有了。</p>
     */
    @Test
    public void inProgress_rowsCarryOriginalAsset() {
        seedInProgress();

        List<ResultRepository.ResultRow> rows =
                repo.page(taskId, "IN_PROGRESS", ResultRepository.Tab.ALL, 10, 0);

        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).asset).isNotNull();
        assertThat(rows.get(0).asset.taskId).isEqualTo(taskId);
        assertThat(rows.get(0).asset.id).isEqualTo(rows.get(0).id);
    }

    /** 回归：盘盈行的 id 必须是 ScanRecord.id —— 补全弹窗用它落库。 */
    @Test
    public void inProgress_extraRowsCarryScanRecordId() {
        seedInProgress();

        List<ResultRepository.ResultRow> extras =
                repo.page(taskId, "IN_PROGRESS", ResultRepository.Tab.EXTRA, 10, 0);

        assertThat(extras).hasSize(1);
        assertThat(extras.get(0).id).isPositive();
    }

    private TaskResult snapshotRow(String epc, int rowIndex, String status) {
        TaskResult r = new TaskResult();
        r.taskId = taskId;
        r.rowIndex = rowIndex;
        r.epc = epc;
        r.dept = "行政部";
        r.status = status;
        return r;
    }
}

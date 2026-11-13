package com.huaxia.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ExtraClaim;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.data.entity.TaskResult;
import com.huaxia.inventory.session.InventorySession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class TaskServiceCompleteTest {

    private AppDatabase db;
    private InventorySession session;
    private TaskService service;
    private long taskId;

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries().build();
        session = new InventorySession();
        service = new TaskService(db, session);

        seedPool("E1", "行政部");
        seedPool("E2", "行政部");
        seedPool("E3", "行政部");
        taskId = service.createTask("T", "行政部", null);
    }

    @After
    public void tearDown() { db.close(); }

    private void seedPool(String epc, String dept) {
        Asset a = new Asset();
        a.taskId = 0L;
        a.excelRowIndex = 1;
        a.epc = epc;
        a.dept = dept;
        db.assetDao().upsertOne(a);
    }

    private Asset assetOf(String epc) {
        return db.assetDao().findByEpc(taskId, epc);
    }

    private void scanFound(String epc, long assetId) {
        ScanRecord s = new ScanRecord();
        s.taskId = taskId;
        s.epc = epc;
        s.scannedAt = 1000L;
        s.matchedAssetId = assetId;
        db.scanRecordDao().insert(s);
    }

    @Test
    public void complete_writesSnapshotForEveryAsset() {
        Asset a1 = assetOf("E1");
        a1.inventoryTime = 1234L;
        db.assetDao().update(a1);
        scanFound("E1", a1.id);

        service.completeTask(taskId);

        List<TaskResult> rows = db.taskResultDao().findAll(taskId);
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.epc).containsExactlyInAnyOrder("E1", "E2", "E3");
        assertThat(db.taskResultDao().countByStatus(taskId, "FOUND")).isEqualTo(1);
        assertThat(db.taskResultDao().countByStatus(taskId, "MISSING")).isEqualTo(2);

        TaskResult found = rows.stream().filter(r -> "E1".equals(r.epc)).findFirst().get();
        assertThat(found.status).isEqualTo("FOUND");
        assertThat(found.inventoryTime).isEqualTo(1234L);
    }

    /**
     * 回归：扫完直接点完成、从没进过结果页时，快照里的盘点时间不能是 null。
     * 以前 inventory_time 只靠「打开结果页」回填；完成时资产回池 + 扫描记录删除后，
     * 就再也没机会补，导出的「盘点时间」列会永久空白。
     */
    @Test
    public void complete_backfillsInventoryTimeBeforeSnapshot() {
        Asset a1 = assetOf("E1");
        assertThat(a1.inventoryTime).isNull();
        scanFound("E1", a1.id);   // scannedAt = 1000L

        service.completeTask(taskId);

        TaskResult found = db.taskResultDao().pageFound(taskId, 10, 0).get(0);
        assertThat(found.epc).isEqualTo("E1");
        assertThat(found.inventoryTime).isEqualTo(1000L);
    }

    @Test
    public void complete_releasesAssetsBackToPool() {
        service.completeTask(taskId);

        assertThat(db.assetDao().countPool()).isEqualTo(3);
        assertThat(db.assetDao().countByTask(taskId)).isZero();
    }

    @Test
    public void complete_deletesScanRecordsAndClaims() {
        Asset a1 = assetOf("E1");
        scanFound("E1", a1.id);

        ScanRecord extra = new ScanRecord();
        extra.taskId = taskId;
        extra.epc = "X9";
        extra.scannedAt = 2000L;
        long extraId = db.scanRecordDao().insert(extra);

        ExtraClaim claim = new ExtraClaim();
        claim.scanRecordId = extraId;
        claim.epc = "X9";
        claim.assetType = "打印机";
        db.extraClaimDao().insert(claim);

        service.completeTask(taskId);

        assertThat(db.scanRecordDao().countExtras(taskId)).isZero();
        assertThat(db.extraClaimDao().findByScanRecordIds(
                java.util.Collections.singletonList(extraId))).isEmpty();

        // 盘盈补全信息必须已经进快照，否则报表会缺列
        List<TaskResult> extras = db.taskResultDao().pageExtra(taskId, 10, 0);
        assertThat(extras).hasSize(1);
        assertThat(extras.get(0).epc).isEqualTo("X9");
        assertThat(extras.get(0).assetType).isEqualTo("打印机");
    }

    @Test
    public void complete_clearsInventoryTimeOnReleasedAssets() {
        Asset a1 = assetOf("E1");
        a1.inventoryTime = 999L;
        db.assetDao().update(a1);

        service.completeTask(taskId);

        assertThat(db.assetDao().findByEpcGlobal("E1").inventoryTime).isNull();
    }

    @Test
    public void complete_keepsScannedUniqueCountOnTaskRow() {
        Asset a1 = assetOf("E1");
        scanFound("E1", a1.id);
        db.inventoryTaskDao().recomputeScannedCount(taskId);
        assertThat(db.inventoryTaskDao().findById(taskId).scannedUniqueCount).isEqualTo(1);

        service.completeTask(taskId);

        // 删扫描记录后不能把任务行上的计数冲成 0，否则任务列表显示 0/3
        assertThat(db.inventoryTaskDao().findById(taskId).scannedUniqueCount).isEqualTo(1);
        assertThat(db.inventoryTaskDao().findById(taskId).status).isEqualTo("COMPLETED");
    }

    @Test
    public void complete_isIdempotent() {
        service.completeTask(taskId);
        service.completeTask(taskId);

        assertThat(db.taskResultDao().findAll(taskId)).hasSize(3);
    }

    @Test
    public void complete_clearsSessionWhenCurrentTask() {
        session.setCurrentTaskId(taskId);

        service.completeTask(taskId);

        assertThat(session.getCurrentTaskId()).isNull();
    }

    @Test
    public void clearCompleted_removesSnapshotAndTaskShell() {
        service.completeTask(taskId);

        service.clearCompleted();

        assertThat(db.taskResultDao().findAll(taskId)).isEmpty();
        assertThat(db.inventoryTaskDao().findAllCompleted()).isEmpty();
        assertThat(db.assetDao().countPool()).isEqualTo(3);
    }

    @Test
    public void deleteCompletedTask_removesSnapshotButKeepsAssetsInPool() {
        service.completeTask(taskId);

        service.deleteTask(taskId);

        assertThat(db.taskResultDao().findAll(taskId)).isEmpty();
        assertThat(db.inventoryTaskDao().findById(taskId)).isNull();
        assertThat(db.assetDao().countPool()).isEqualTo(3);
    }
}

package com.huaxia.inventory.data;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import com.huaxia.inventory.data.dao.AssetDao;
import com.huaxia.inventory.data.entity.Asset;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * 防回归 (行为): Asset 快照列的划拨打快照 / 回滚还原 / 清时间 / 清快照语义。
 *
 * <p>支撑"删除/重置任务回滚、完成任务提交"的核心 DAO 行为。重点包括:</p>
 * <ul>
 *   <li>{@code assignPoolToTask} 只对库存池 (taskId=0) 行打快照;</li>
 *   <li>{@code revertEditedFields} 从快照还原业务字段 + rawRowJson, 且哨兵
 *       ({@code orig_epc IS NOT NULL}) 跳过无快照行;</li>
 *   <li><b>epc 互换场景</b>回滚不抛 {@code SQLiteConstraintException} (验 {@code UPDATE OR IGNORE});</li>
 *   <li>清盘点时间 / 清快照都只作用本任务;</li>
 *   <li>先清快照再 release → 回池行无残留快照。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class AssetSnapshotDaoTest {

    @Rule
    public InstantTaskExecutorRule rule = new InstantTaskExecutorRule();

    private AppDatabase db;
    private AssetDao dao;

    @Before
    public void setup() {
        db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        dao = db.assetDao();
    }

    @After
    public void teardown() { db.close(); }

    private Asset poolAsset(String epc, String type, String rawJson) {
        Asset a = new Asset();
        a.taskId = 0;
        a.excelRowIndex = 1;
        a.epc = epc;
        a.assetType = type;
        a.rawRowJson = rawJson;
        return a;
    }

    @Test
    public void assignPoolToTask_snapshotsBusinessFields() {
        dao.insert(poolAsset("EPC1", "电脑", "{\"设备类型\":\"电脑\"}"));

        int n = dao.assignPoolToTask(7, null);

        assertEquals(1, n);
        Asset a = dao.findByEpc(7, "EPC1");
        assertNotNull(a);
        assertEquals(7L, a.taskId);
        assertEquals("EPC1", a.origEpc);
        assertEquals("电脑", a.origAssetType);
        assertEquals("{\"设备类型\":\"电脑\"}", a.origRawRowJson);
    }

    @Test
    public void assignPoolToTask_onlyTouchesPoolRows() {
        dao.insert(poolAsset("P1", "A", "{}"));
        Asset inTask = poolAsset("T1", "B", "{}");
        inTask.taskId = 5;
        dao.insert(inTask);

        dao.assignPoolToTask(7, null);

        Asset other = dao.findByEpc(5, "T1");
        assertNull("已在其它任务的行不应被打快照", other.origEpc);
        assertEquals("B", other.assetType);
    }

    @Test
    public void revertEditedFields_restoresColumnsAndRawJson() {
        dao.insert(poolAsset("EPC1", "旧类型", "{\"设备类型\":\"旧类型\"}"));
        dao.assignPoolToTask(7, null);

        Asset a = dao.findByEpc(7, "EPC1");
        a.assetType = "新类型";
        a.rawRowJson = "{\"设备类型\":\"新类型\"}";
        dao.update(a);

        dao.revertEditedFields(7);

        Asset r = dao.findByEpc(7, "EPC1");
        assertEquals("旧类型", r.assetType);
        assertEquals("{\"设备类型\":\"旧类型\"}", r.rawRowJson);
    }

    @Test
    public void revertEditedFields_skipsRowsWithoutSnapshot() {
        // taskId=7 但无快照 (orig_epc null) —— 哨兵应跳过, 字段不动.
        Asset a = poolAsset("EPC1", "原类型", "{}");
        a.taskId = 7;
        dao.insert(a);

        dao.revertEditedFields(7);

        assertEquals("原类型", dao.findByEpc(7, "EPC1").assetType);
    }

    @Test
    public void revertEditedFields_epcSwap_doesNotThrow() {
        dao.insert(poolAsset("A", "t", "{}"));
        dao.insert(poolAsset("B", "t", "{}"));
        dao.assignPoolToTask(7, null);

        // 互换: row(A).epc -> C, row(B).epc -> A. (每步单独都不冲突)
        Asset rowA = dao.findByEpc(7, "A");
        rowA.epc = "C";
        dao.update(rowA);
        Asset rowB = dao.findByEpc(7, "B");
        rowB.epc = "A";
        dao.update(rowB);

        // 回滚: 普通 UPDATE 会因 row(B) 已占 A 而抛唯一键冲突; OR IGNORE 应静默跳过.
        try {
            dao.revertEditedFields(7);
        } catch (android.database.sqlite.SQLiteConstraintException e) {
            fail("revertEditedFields must not throw on epc swap (UPDATE OR IGNORE): " + e);
        }
        assertEquals(2, dao.countByTask(7));
    }

    @Test
    public void clearInventoryTimeForTask_onlyAffectsTask() {
        dao.insert(poolAsset("P1", "t", "{}"));
        dao.assignPoolToTask(7, null);
        Asset a = dao.findByEpc(7, "P1");
        a.inventoryTime = 111L;
        dao.update(a);
        Asset other = poolAsset("O1", "t", "{}");
        other.inventoryTime = 222L;
        dao.insert(other);

        dao.clearInventoryTimeForTask(7);

        assertNull(dao.findByEpc(7, "P1").inventoryTime);
        assertEquals(Long.valueOf(222L), dao.findByEpc(0, "O1").inventoryTime);
    }

    @Test
    public void clearSnapshotForTask_clearsOnlyTaskSnapshots_preservingBusinessFields() {
        dao.insert(poolAsset("P1", "t", "{}"));
        dao.assignPoolToTask(7, null);

        dao.clearSnapshotForTask(7);

        Asset a = dao.findByEpc(7, "P1");
        assertNull(a.origEpc);
        assertNull(a.origAssetType);
        assertNull(a.origRawRowJson);
        assertEquals("业务字段不应被清", "t", a.assetType);
    }

    @Test
    public void clearSnapshotBeforeRelease_leavesPoolRowsWithoutSnapshot() {
        dao.insert(poolAsset("P1", "t", "{}"));
        dao.assignPoolToTask(7, null);

        // 删除任务事务的顺序: 先清快照, 再 release.
        dao.clearSnapshotForTask(7);
        dao.releaseTaskToPool(7);

        Asset a = dao.findByEpc(0, "P1");
        assertNotNull(a);
        assertNull("回池后不应残留快照", a.origEpc);
    }
}

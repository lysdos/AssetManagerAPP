package com.huaxia.inventory.data;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import com.huaxia.inventory.data.dao.ScanRecordDao;
import com.huaxia.inventory.data.entity.ScanRecord;
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

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ScanRecordDaoTest {

    @Rule public InstantTaskExecutorRule rule = new InstantTaskExecutorRule();

    private AppDatabase db;
    private ScanRecordDao dao;

    @Before public void setup() {
        db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries().build();
        dao = db.scanRecordDao();
    }
    @After public void teardown() { db.close(); }

    @Test public void uniqueConstraint_ignoresDuplicateEpc() {
        dao.insert(newRec(1, "EPC1", 100L));
        long second = dao.insert(newRec(1, "EPC1", 101L));
        assertEquals(-1L, second);
        assertEquals(1, dao.countByTask(1));
    }

    @Test public void extrasAreRecordsWithNullMatchedAssetId() {
        dao.insert(newRec(1, "EPC_EXTRA", null));
        assertEquals(1, dao.findExtras(1).size());
        assertNull(dao.findExtras(1).get(0).matchedAssetId);
    }

    @Test public void findByEpc_returnsNullWhenAbsent() {
        assertNull(dao.findByEpc(1, "MISSING"));
    }

    @Test public void countExtras_countsOnlyUnmatched() {
        long id = db.assetDao().insert(makeAsset(1, "EPC_MATCHED"));
        dao.insert(newRec(1, "EPC_MATCHED", id));   // matched
        dao.insert(newRec(1, "EPC_EXTRA_1", null)); // unmatched
        dao.insert(newRec(1, "EPC_EXTRA_2", null)); // unmatched
        assertEquals(2, dao.countExtras(1));
    }

    @Test public void countExtras_isolatesByTask() {
        dao.insert(newRec(1, "EPC_A", null));
        dao.insert(newRec(2, "EPC_B", null));
        assertEquals(1, dao.countExtras(1));
        assertEquals(1, dao.countExtras(2));
    }

    @Test public void pageExtra_paginatesInDescendingTime() {
        // Insert records with explicit timestamps
        for (int i = 1; i <= 10; i++) {
            ScanRecord r = new ScanRecord();
            r.taskId = 1;
            r.epc = "EXTRA_" + i;
            r.scannedAt = 1_000_000L + i; // increasing time
            r.rssi = "-50";
            r.matchedAssetId = null;
            dao.insert(r);
        }
        java.util.List<ScanRecord> page1 = dao.pageExtra(1, 4, 0);
        java.util.List<ScanRecord> page2 = dao.pageExtra(1, 4, 4);
        assertEquals(4, page1.size());
        assertEquals(4, page2.size());
        // Most recent first (DESC order): page1 starts with EXTRA_10
        assertEquals("EXTRA_10", page1.get(0).epc);
        assertEquals("EXTRA_6", page2.get(0).epc);
    }

    @Test public void deleteByTask_removesOnlyMatchingTask() {
        dao.insert(newRec(1, "EPC1", null));
        dao.insert(newRec(1, "EPC2", null));
        dao.insert(newRec(2, "EPC3", null));
        int deleted = dao.deleteByTask(1);
        assertEquals(2, deleted);
        assertEquals(0, dao.countByTask(1));
        assertEquals(1, dao.countByTask(2));
    }

    @Test public void aggregateStats_emptyTaskReturnsZeros() {
        com.huaxia.inventory.data.dao.ScanAggregate a = dao.aggregateStats(999);
        assertEquals(0, a.matchedCount);
        assertEquals(0, a.extraCount);
        assertEquals(0L, a.lastScannedAt);
    }

    @Test public void aggregateStats_separatesMatchedAndExtras() {
        long id1 = db.assetDao().insert(makeAsset(1, "EPC_MATCHED_A"));
        long id2 = db.assetDao().insert(makeAsset(1, "EPC_MATCHED_B"));
        // 2 个匹配（指向不同 asset）
        dao.insert(newRecAt(1, "EPC_MATCHED_A", 1_000L, id1));
        dao.insert(newRecAt(1, "EPC_MATCHED_B", 1_500L, id2));
        // 1 个盘盈（matchedAssetId=null）
        dao.insert(newRecAt(1, "EPC_EXTRA", 2_000L, null));
        com.huaxia.inventory.data.dao.ScanAggregate a = dao.aggregateStats(1);
        assertEquals(2, a.matchedCount);
        assertEquals(1, a.extraCount);
        assertEquals(2_000L, a.lastScannedAt);
    }

    @Test public void aggregateStats_matchedCountIsDistinct() {
        long id = db.assetDao().insert(makeAsset(1, "EPC_A"));
        // 同一 asset 被扫到 3 次（实际只 1 条入库，unique 约束 + OnConflictStrategy.IGNORE）
        dao.insert(newRecAt(1, "EPC_A", 1_000L, id));
        com.huaxia.inventory.data.dao.ScanAggregate a = dao.aggregateStats(1);
        assertEquals(1, a.matchedCount);  // distinct 必然 = 1
        assertEquals(0, a.extraCount);
    }

    @Test public void aggregateStats_isolatesByTask() {
        long id1 = db.assetDao().insert(makeAsset(1, "EPC_T1"));
        long id2 = db.assetDao().insert(makeAsset(2, "EPC_T2"));
        dao.insert(newRecAt(1, "EPC_T1", 1_000L, id1));
        dao.insert(newRecAt(2, "EPC_T2", 2_000L, id2));
        dao.insert(newRecAt(1, "EPC_EXTRA", 3_000L, null));  // task 1
        com.huaxia.inventory.data.dao.ScanAggregate a1 = dao.aggregateStats(1);
        com.huaxia.inventory.data.dao.ScanAggregate a2 = dao.aggregateStats(2);
        assertEquals(1, a1.matchedCount);
        assertEquals(1, a1.extraCount);
        assertEquals(3_000L, a1.lastScannedAt);
        assertEquals(1, a2.matchedCount);
        assertEquals(0, a2.extraCount);
        assertEquals(2_000L, a2.lastScannedAt);
    }

    private com.huaxia.inventory.data.entity.Asset makeAsset(long taskId, String epc) {
        com.huaxia.inventory.data.entity.Asset a = new com.huaxia.inventory.data.entity.Asset();
        a.taskId = taskId;
        a.excelRowIndex = 1;
        a.epc = epc;
        a.assetName = "Asset";
        a.rawRowJson = "{}";
        return a;
    }

    private ScanRecord newRec(long taskId, String epc, Long matchedAssetId) {
        return newRecAt(taskId, epc, System.currentTimeMillis(), matchedAssetId);
    }

    /** 用于测试显式控制 scannedAt，避免 System.currentTimeMillis() 的不确定性。 */
    private ScanRecord newRecAt(long taskId, String epc, long scannedAt, Long matchedAssetId) {
        ScanRecord r = new ScanRecord();
        r.taskId = taskId;
        r.epc = epc;
        r.scannedAt = scannedAt;
        r.rssi = "-50";
        r.matchedAssetId = matchedAssetId;
        return r;
    }
}

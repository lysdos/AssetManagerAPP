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
        ScanRecord r = new ScanRecord();
        r.taskId = taskId;
        r.epc = epc;
        r.scannedAt = System.currentTimeMillis();
        r.rssi = "-50";
        r.matchedAssetId = matchedAssetId;
        return r;
    }
}

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

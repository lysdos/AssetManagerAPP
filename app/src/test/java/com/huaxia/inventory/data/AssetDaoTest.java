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
import java.util.Arrays;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class AssetDaoTest {

    @Rule
    public InstantTaskExecutorRule rule = new InstantTaskExecutorRule();

    private AppDatabase db;
    private AssetDao dao;

    @Before
    public void setup() {
        db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        dao = db.assetDao();
    }

    @After
    public void teardown() { db.close(); }

    @Test
    public void insertAndFindByEpc() {
        Asset a = newAsset(1, "3034A1B2", "RFID Reader");
        long id = dao.insert(a);
        assertNotNull(dao.findByEpc(1, "3034A1B2"));
        assertEquals(id, dao.findByEpc(1, "3034A1B2").id);
    }

    @Test
    public void insertAllCountsCorrectly() {
        dao.insertAll(Arrays.asList(
                newAsset(1, "EPC1", "A"),
                newAsset(1, "EPC2", "B"),
                newAsset(1, "EPC3", "C")
        ));
        assertEquals(3, dao.countByTask(1));
    }

    @Test
    public void findByEpc_respectsTask() {
        dao.insert(newAsset(1, "EPC1", "A"));
        assertNotNull(dao.findByEpc(1, "EPC1"));
        assertEquals(null, dao.findByEpc(2, "EPC1"));
    }

    private Asset newAsset(long taskId, String epc, String name) {
        Asset a = new Asset();
        a.taskId = taskId;
        a.excelRowIndex = 1;
        a.epc = epc;
        a.assetName = name;
        a.rawRowJson = "{}";
        return a;
    }
}

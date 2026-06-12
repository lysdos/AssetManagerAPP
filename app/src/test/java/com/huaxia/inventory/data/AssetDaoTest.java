package com.huaxia.inventory.data;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import com.huaxia.inventory.data.dao.AssetDao;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ScanRecord;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Arrays;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void countByCategory_groupsAndOrders() {
        dao.insertAll(Arrays.asList(
                newAsset(1, "EPC1", "Computer A", "电脑"),
                newAsset(1, "EPC2", "Computer B", "电脑"),
                newAsset(1, "EPC3", "Furniture A", "家具"),
                newAsset(1, "EPC4", "Instrument A", "仪器")
        ));
        java.util.List<com.huaxia.inventory.data.dao.CategoryCount> cats = dao.countByCategory(1);
        assertEquals(3, cats.size());
        // "电脑" should be first (2 items)
        assertEquals("电脑", cats.get(0).category);
        assertEquals(2, cats.get(0).count);
        // "家具" and "仪器" tied at 1 each, order by count DESC then unspecified
        // just verify counts
        int total = 0;
        for (com.huaxia.inventory.data.dao.CategoryCount c : cats) total += c.count;
        assertEquals(4, total);
    }

    @Test
    public void countByCategory_excludesNullType() {
        Asset a1 = newAsset(1, "EPC1", "Computer A", "电脑");
        Asset a2 = newAsset(1, "EPC2", "Unknown", "未知");
        a2.assetType = null; // explicitly null
        dao.insertAll(Arrays.asList(a1, a2));
        java.util.List<com.huaxia.inventory.data.dao.CategoryCount> cats = dao.countByCategory(1);
        assertEquals(1, cats.size()); // null type excluded
        assertEquals("电脑", cats.get(0).category);
    }

    @Test
    public void countByCategory_isolatesByTask() {
        dao.insertAll(Arrays.asList(
                newAsset(1, "EPC1", "A", "A"),
                newAsset(2, "EPC2", "A", "A"),
                newAsset(2, "EPC3", "A", "A")
        ));
        assertEquals(1, dao.countByCategory(1).size());
        assertEquals(1, dao.countByCategory(2).size());
        assertEquals(2, dao.countByCategory(2).get(0).count);
    }

    @Test
    public void countFound_countsAssetsMatchedByScan() {
        long id1 = dao.insert(newAsset(1, "EPC1", "A"));
        long id2 = dao.insert(newAsset(1, "EPC2", "B"));
        dao.insert(newAsset(1, "EPC3", "C"));
        // Mark id1 and id2 as scanned (matched)
        db.scanRecordDao().insert(makeRec(1, "EPC1", id1));
        db.scanRecordDao().insert(makeRec(1, "EPC2", id2));
        // id3 not scanned
        assertEquals(2, dao.countFound(1));
    }

    @Test
    public void countMissing_countsAssetsNotScanned() {
        dao.insertAll(Arrays.asList(
                newAsset(1, "EPC1", "A"),
                newAsset(1, "EPC2", "B"),
                newAsset(1, "EPC3", "C")
        ));
        db.scanRecordDao().insert(makeRec(1, "EPC1", dao.findByEpc(1, "EPC1").id));
        assertEquals(2, dao.countMissing(1));
    }

    @Test
    public void pageAll_returnsLimitedResultsInOrder() {
        for (int i = 1; i <= 10; i++) {
            Asset a = new Asset();
            a.taskId = 1;
            a.excelRowIndex = i;
            a.epc = "EPC" + i;
            a.assetName = "Asset " + i;
            a.rawRowJson = "{}";
            dao.insert(a);
        }
        java.util.List<Asset> page1 = dao.pageAll(1, 4, 0);
        java.util.List<Asset> page2 = dao.pageAll(1, 4, 4);
        java.util.List<Asset> page3 = dao.pageAll(1, 4, 8);
        assertEquals(4, page1.size());
        assertEquals(4, page2.size());
        assertEquals(2, page3.size());  // 10 - 8 = 2
        assertEquals("EPC1", page1.get(0).epc);
        assertEquals("EPC5", page2.get(0).epc);
        assertEquals("EPC9", page3.get(0).epc);
    }

    @Test
    public void pageFound_paginatesOnlyMatched() {
        long id1 = dao.insert(newAsset(1, "EPC1", "A"));
        long id2 = dao.insert(newAsset(1, "EPC2", "B"));
        dao.insert(newAsset(1, "EPC3", "C"));
        dao.insert(newAsset(1, "EPC4", "D"));
        db.scanRecordDao().insert(makeRec(1, "EPC1", id1));
        db.scanRecordDao().insert(makeRec(1, "EPC2", id2));
        java.util.List<Asset> page = dao.pageFound(1, 10, 0);
        assertEquals(2, page.size());
    }

    @Test
    public void pageMissing_paginatesOnlyUnmatched() {
        dao.insert(newAsset(1, "EPC1", "A"));
        long id2 = dao.insert(newAsset(1, "EPC2", "B"));
        dao.insert(newAsset(1, "EPC3", "C"));
        db.scanRecordDao().insert(makeRec(1, "EPC2", id2));
        java.util.List<Asset> page = dao.pageMissing(1, 10, 0);
        assertEquals(2, page.size());
        java.util.Set<String> epcs = new java.util.HashSet<>();
        for (Asset a : page) epcs.add(a.epc);
        assertTrue(epcs.contains("EPC1"));
        assertTrue(epcs.contains("EPC3"));
        assertFalse(epcs.contains("EPC2"));
    }

    @Test
    public void deleteByTask_removesOnlyMatchingTask() {
        dao.insertAll(java.util.Arrays.asList(
                newAsset(1, "EPC1", "A"),
                newAsset(1, "EPC2", "B"),
                newAsset(2, "EPC3", "C")));
        int deleted = dao.deleteByTask(1);
        assertEquals(2, deleted);
        assertEquals(0, dao.countByTask(1));
        assertEquals(1, dao.countByTask(2));
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

    private Asset newAsset(long taskId, String epc, String name, String type) {
        Asset a = newAsset(taskId, epc, name);
        a.assetType = type;
        return a;
    }

    private ScanRecord makeRec(long taskId, String epc, Long matchedAssetId) {
        ScanRecord r = new ScanRecord();
        r.taskId = taskId;
        r.epc = epc;
        r.scannedAt = System.currentTimeMillis();
        r.rssi = "-50";
        r.matchedAssetId = matchedAssetId;
        return r;
    }
}

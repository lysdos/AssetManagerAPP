package com.huaxia.inventory.ui.asset;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.dao.AssetDao;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.InventoryTask;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 资产 tab ViewModel: 分页、筛选、页码钳制、编辑写入的 SUCCESS/FROZEN/MISSING.
 * 使用直接 executor + null Handler, 让查询/回调同步完成, 便于断言.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class AssetOverviewViewModelTest {

    @Rule
    public InstantTaskExecutorRule rule = new InstantTaskExecutorRule();

    private AppDatabase db;
    private AssetDao dao;
    private AssetOverviewViewModel vm;
    private final AtomicReference<AssetOverviewViewModel.OverviewState> latest =
            new AtomicReference<>();

    @Before
    public void setup() {
        db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        dao = db.assetDao();
    }

    @After
    public void teardown() {
        db.close();
    }

    private void newVm() {
        vm = new AssetOverviewViewModel(ApplicationProvider.getApplicationContext(),
                db, Runnable::run, null);
        vm.state().observeForever(latest::set);
    }

    private void seed(int count) {
        for (int i = 0; i < count; i++) {
            Asset a = new Asset();
            a.taskId = 0;
            a.excelRowIndex = i;
            a.epc = String.format(Locale.ROOT, "%024d", i + 1);
            a.assetType = (i % 3 == 0) ? null : (i % 2 == 0 ? "电脑" : "打印机");
            a.assetModel = "型号" + i;
            a.serialNumber = String.format(Locale.ROOT, "SN%03d", i);
            a.dept = (i % 5 == 0) ? null : (i % 2 == 0 ? "部门A" : "部门B");
            a.custodian = "人" + i;
            a.rawRowJson = "{}";
            dao.insert(a);
        }
    }

    private AssetOverviewViewModel.OverviewState state() {
        AssetOverviewViewModel.OverviewState s = latest.get();
        assertNotNull("ViewModel 应产出一个状态", s);
        return s;
    }

    @Test
    public void initialPage_has20RowsAndThreePages() {
        seed(45);
        newVm();
        AssetOverviewViewModel.OverviewState s = state();
        assertEquals(45, s.totalAll);
        assertEquals(45, s.filteredTotal);
        assertEquals(3, s.pageCount);
        assertEquals(0, s.page);
        assertEquals(20, s.assets.size());
    }

    @Test
    public void paging_returnsRemainderAndClamps() {
        seed(45);
        newVm();
        vm.nextPage();
        assertEquals(1, state().page);
        assertEquals(20, state().assets.size());
        vm.nextPage();
        assertEquals(2, state().page);
        assertEquals(5, state().assets.size());
        vm.nextPage(); // 已到最后一页, 不再前进
        assertEquals(2, state().page);
        vm.prevPage();
        assertEquals(1, state().page);
    }

    @Test
    public void keyword_resetsPageAndFiltersSixFields() {
        seed(45);
        newVm();
        vm.nextPage();
        assertEquals(1, state().page);

        vm.setKeyword("SN004");
        AssetOverviewViewModel.OverviewState s = state();
        assertEquals(1, s.filteredTotal);
        assertEquals(0, s.page);
        assertEquals(1, s.assets.size());
        assertEquals("SN004", s.assets.get(0).serialNumber);
    }

    @Test
    public void deptAndTypeFilters_matchDistributionCounts() {
        seed(45);
        newVm();
        AssetOverviewViewModel.OverviewState s = state();

        String dept = s.deptBuckets.get(0).category;
        int deptCount = s.deptBuckets.get(0).count;
        vm.toggleDeptBucket(dept);
        s = state();
        assertEquals(deptCount, s.filteredTotal);
        assertEquals(dept, s.deptBucket);

        vm.clearFilters();
        s = state();
        String type = s.typeBuckets.get(0).category;
        int typeCount = s.typeBuckets.get(0).count;
        vm.toggleTypeBucket(type);
        s = state();
        assertEquals(typeCount, s.filteredTotal);
        assertEquals(type, s.typeBucket);
    }

    @Test
    public void saveLedger_poolAsset_updatesBusinessFieldsOnly() {
        Asset a = new Asset();
        a.taskId = 0;
        a.epc = "000000003500310014100167";
        a.assetType = "旧类型";
        a.assetModel = "旧型号";
        a.serialNumber = "旧序列";
        a.dept = "旧部门";
        a.custodian = "旧人";
        a.rawRowJson = "{}";
        a.inventoryTime = 123L;
        a.createdAt = 456L;
        a.origEpc = a.epc;
        long id = dao.insert(a);

        Asset pending = new Asset();
        pending.id = id;
        pending.assetType = "新类型";
        pending.assetModel = "新型号";
        pending.serialNumber = "新序列";
        pending.dept = "新部门";
        pending.custodian = "新人";
        pending.rawRowJson = "{\"新\":\"值\"}";

        AtomicReference<AssetOverviewViewModel.SaveResult> result = new AtomicReference<>();
        vm = new AssetOverviewViewModel(ApplicationProvider.getApplicationContext(),
                db, Runnable::run, null);
        vm.saveLedger(pending, result::set);

        assertEquals(AssetOverviewViewModel.SaveResult.SUCCESS, result.get());
        Asset saved = dao.findById(id);
        assertNotNull(saved);
        assertEquals("新类型", saved.assetType);
        assertEquals("新部门", saved.dept);
        assertEquals("{\"新\":\"值\"}", saved.rawRowJson);
        assertEquals("000000003500310014100167", saved.epc);
        assertEquals(0L, saved.taskId);
        assertEquals(Long.valueOf(123L), saved.inventoryTime);
        assertEquals(Long.valueOf(456L), saved.createdAt);
        assertEquals("000000003500310014100167", saved.origEpc);
    }

    @Test
    public void saveLedger_completedTask_isFrozen() {
        InventoryTask task = new InventoryTask();
        task.sourceFileName = "已完成任务";
        task.status = "COMPLETED";
        long taskId = db.inventoryTaskDao().insert(task);

        Asset a = new Asset();
        a.taskId = taskId;
        a.epc = "000000003500310014100175";
        a.assetType = "旧类型";
        long id = dao.insert(a);

        Asset pending = new Asset();
        pending.id = id;
        pending.assetType = "新类型";

        vm = new AssetOverviewViewModel(ApplicationProvider.getApplicationContext(),
                db, Runnable::run, null);
        AtomicReference<AssetOverviewViewModel.SaveResult> result = new AtomicReference<>();
        vm.saveLedger(pending, result::set);

        assertEquals(AssetOverviewViewModel.SaveResult.FROZEN, result.get());
        assertEquals("旧类型", dao.findById(id).assetType);
    }

    @Test
    public void saveLedger_missingAsset_returnsMissing() {
        vm = new AssetOverviewViewModel(ApplicationProvider.getApplicationContext(),
                db, Runnable::run, null);
        Asset pending = new Asset();
        pending.id = 99999L;
        AtomicReference<AssetOverviewViewModel.SaveResult> result = new AtomicReference<>();
        vm.saveLedger(pending, result::set);
        assertEquals(AssetOverviewViewModel.SaveResult.MISSING, result.get());
    }

    @Test
    public void emptyLedger_hasZeroPagesAndNoRows() {
        newVm();
        AssetOverviewViewModel.OverviewState s = state();
        assertEquals(0, s.totalAll);
        assertEquals(0, s.filteredTotal);
        assertEquals(0, s.pageCount);
        assertTrue(s.assets.isEmpty());
    }
}

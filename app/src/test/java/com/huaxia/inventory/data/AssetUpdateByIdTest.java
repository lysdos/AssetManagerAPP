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

/**
 * 防回归: 资产 tab 的台账编辑只能写 6 个业务字段 + raw_row_json,
 * 绝不能碰 epc / taskId / excelRowIndex / inventory_time / created_at / orig_*.
 *
 * <p>这些列分别承载 RFID 匹配、任务归属、分页顺序、盘点时间、首次导入统计和
 * 删除/重置回滚快照; 任何一列被覆盖都会破坏任务语义.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class AssetUpdateByIdTest {

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
    public void teardown() {
        db.close();
    }

    @Test
    public void updateBusinessFieldsById_preservesIdentityTaskTimeAndSnapshot() {
        Asset a = new Asset();
        a.taskId = 7L;
        a.excelRowIndex = 42;
        a.epc = "000000003500310014100167";
        a.assetType = "旧类型";
        a.assetModel = "旧型号";
        a.serialNumber = "旧序列";
        a.dept = "旧部门";
        a.custodian = "旧领用人";
        a.rawRowJson = "{\"旧\":\"值\"}";
        a.inventoryTime = 123L;
        a.createdAt = 456L;
        a.origEpc = "000000003500310014100167";
        a.origAssetType = "划拨类型";
        a.origAssetModel = "划拨型号";
        a.origSerialNumber = "划拨序列";
        a.origDept = "划拨部门";
        a.origCustodian = "划拨人";
        a.origRawRowJson = "{\"划拨\":\"快照\"}";
        long id = dao.insert(a);

        int rows = dao.updateBusinessFieldsById(id,
                "新类型", "新型号", "新序列", "新领用人", "新部门", "{\"新\":\"值\"}");
        assertEquals(1, rows);

        Asset updated = dao.findById(id);
        assertNotNull(updated);
        assertEquals("新类型", updated.assetType);
        assertEquals("新型号", updated.assetModel);
        assertEquals("新序列", updated.serialNumber);
        assertEquals("新领用人", updated.custodian);
        assertEquals("新部门", updated.dept);
        assertEquals("{\"新\":\"值\"}", updated.rawRowJson);

        // 以下全部必须原样保留
        assertEquals("000000003500310014100167", updated.epc);
        assertEquals(7L, updated.taskId);
        assertEquals(42, updated.excelRowIndex);
        assertEquals(Long.valueOf(123L), updated.inventoryTime);
        assertEquals(Long.valueOf(456L), updated.createdAt);
        assertEquals("000000003500310014100167", updated.origEpc);
        assertEquals("划拨类型", updated.origAssetType);
        assertEquals("划拨型号", updated.origAssetModel);
        assertEquals("划拨序列", updated.origSerialNumber);
        assertEquals("划拨部门", updated.origDept);
        assertEquals("划拨人", updated.origCustodian);
        assertEquals("{\"划拨\":\"快照\"}", updated.origRawRowJson);
    }

    @Test
    public void updateBusinessFieldsById_missingRowReturnsZero() {
        assertEquals(0, dao.updateBusinessFieldsById(999L,
                "a", "b", "c", "d", "e", "{}"));
    }
}

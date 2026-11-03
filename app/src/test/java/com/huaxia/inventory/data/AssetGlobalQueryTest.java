package com.huaxia.inventory.data;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import com.huaxia.inventory.data.dao.AssetDao;
import com.huaxia.inventory.data.dao.CategoryCount;
import com.huaxia.inventory.data.entity.Asset;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * 全局资产台账查询: 资产 tab 的总数/关键词/部门类型交叉筛选/分页.
 *
 * <p>与 {@code AssetDaoTest} 不同, 这些查询不按 taskId 过滤, 并且用 '' 桶表示
 * “未填写部门/未分类”. 这里把 SQL 与 ViewModel 约定的 LIKE pattern 一起钉住.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class AssetGlobalQueryTest {

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

    private long insert(long taskId, String epc, String type, String model,
                        String serial, String dept, String custodian) {
        Asset a = new Asset();
        a.taskId = taskId;
        a.epc = epc;
        a.assetType = type;
        a.assetModel = model;
        a.serialNumber = serial;
        a.dept = dept;
        a.custodian = custodian;
        return dao.insert(a);
    }

    private void seed() {
        insert(0, "000000003500310014100167", "台式电脑", "联想 M720t", "SN001", "财务部", "张三");
        insert(1, "000000003500310014100175", "打印机", "惠普 M405dn", "SN002", "信息科技部", "李四");
        insert(2, "000000003500310014100183", null, null, null, null, null);
        insert(0, "000000003500310014100191", "打印机", "100%覆盖率", "A_B", "  ", "王五");
    }

    @Test
    public void countAll_includesPoolAndTaskAssets() {
        seed();
        assertEquals(4, dao.countAll());
        assertEquals(4, dao.countFiltered("%", null, null));
    }

    @Test
    public void keyword_matches16DigitSuffixAndAllSixFields() {
        seed();
        assertEquals(1, dao.countFiltered("%3500310014100167%", null, null));
        assertEquals(1, dao.countFiltered("%惠普%", null, null));       // model
        assertEquals(2, dao.countFiltered("%SN00%", null, null));       // serial: SN001+SN002
        assertEquals(1, dao.countFiltered("%信息科技%", null, null));   // dept
        assertEquals(1, dao.countFiltered("%王五%", null, null));       // custodian
        assertEquals(2, dao.countFiltered("%打印机%", null, null));     // type
    }

    @Test
    public void keyword_percentAndUnderscoreAreLiteral() {
        seed();
        // 字面 % : 只有 model 为 "100%覆盖率" 的那条
        assertEquals(1, dao.countFiltered("%!%%", null, null));
        // 字面 _ : 只有 serial 为 "A_B" 的那条
        assertEquals(1, dao.countFiltered("%!_%", null, null));
    }

    @Test
    public void blankDeptAndTypeCollapseIntoEmptyBucket() {
        seed();
        List<CategoryCount> depts = dao.deptDistribution("%", null);
        assertEquals(2, countOf(depts, "")); // null dept + "  "

        List<CategoryCount> types = dao.typeDistribution("%", null);
        assertEquals(1, countOf(types, "")); // null type
    }

    @Test
    public void distributions_areCrossFiltered() {
        seed();
        // 类型=打印机 时, 部门分布忽略部门筛选, 但受类型约束
        List<CategoryCount> depts = dao.deptDistribution("%", "打印机");
        assertEquals(1, countOf(depts, "信息科技部"));
        assertEquals(1, countOf(depts, ""));
        assertEquals(2, total(depts));

        // 部门=财务部 时, 类型分布忽略类型筛选, 但受部门约束
        List<CategoryCount> types = dao.typeDistribution("%", "财务部");
        assertEquals(1, countOf(types, "台式电脑"));
        assertEquals(1, total(types));
    }

    @Test
    public void filters_combineAndPageOrderIsStable() {
        seed();
        assertEquals(1, dao.countFiltered("%", "", "打印机"));

        List<Asset> page1 = dao.pageFiltered("%", null, null, 2, 0);
        List<Asset> page2 = dao.pageFiltered("%", null, null, 2, 2);
        assertEquals(2, page1.size());
        assertEquals(2, page2.size());
        assertEquals("000000003500310014100167", page1.get(0).epc);
        assertEquals("000000003500310014100175", page1.get(1).epc);
        assertEquals("000000003500310014100183", page2.get(0).epc);
        assertEquals("000000003500310014100191", page2.get(1).epc);
    }

    @Test
    public void findById_returnsGlobalRow() {
        seed();
        Asset a = dao.findById(1L);
        assertNotNull(a);
        assertEquals("000000003500310014100167", a.epc);
    }

    private static int total(List<CategoryCount> list) {
        int sum = 0;
        for (CategoryCount c : list) sum += c.count;
        return sum;
    }

    private static int countOf(List<CategoryCount> list, String category) {
        for (CategoryCount c : list) {
            if (category.equals(c.category)) return c.count;
        }
        return 0;
    }
}

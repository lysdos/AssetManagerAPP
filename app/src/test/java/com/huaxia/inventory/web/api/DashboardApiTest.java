package com.huaxia.inventory.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.session.InventorySession;
import com.huaxia.inventory.web.WebRequest;
import com.huaxia.inventory.web.WebResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class DashboardApiTest {

    private AppDatabase db;
    private DashboardApi api;
    private final ObjectMapper mapper = new ObjectMapper();

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries().build();
        api = new DashboardApi(db, new InventorySession());
    }

    @After
    public void tearDown() { db.close(); }

    private void seed(String epc, String dept, String type, long taskId) {
        Asset a = new Asset();
        a.taskId = taskId;
        a.excelRowIndex = 1;
        a.epc = epc;
        a.dept = dept;
        a.assetType = type;
        db.assetDao().upsertOne(a);
    }

    private JsonNode get() throws Exception {
        WebResponse res = api.handle(new WebRequest("GET", "/api/dashboard",
                Collections.emptyMap(), Collections.emptyMap(), new byte[0]));
        assertThat(res.status).isEqualTo(200);
        return mapper.readTree(res.bytes);
    }

    @Test
    public void emptyDatabase_returnsZerosAndNullActiveTask() throws Exception {
        JsonNode root = get();
        assertThat(root.at("/ledger/total").asInt()).isZero();
        assertThat(root.at("/deptDistribution").size()).isZero();
        assertThat(root.at("/activeTask").isNull()).isTrue();
    }

    @Test
    public void ledgerCountsIncludeAssignedAssets() throws Exception {
        seed("1", "行政部", "笔记本", 0L);
        seed("2", "财务部", "台式机", 7L);   // 已划拨到任务 7

        JsonNode root = get();

        assertThat(root.at("/ledger/total").asInt()).isEqualTo(2);
        assertThat(root.at("/ledger/deptCount").asInt()).isEqualTo(2);
        assertThat(root.at("/ledger/typeCount").asInt()).isEqualTo(2);
    }

    @Test
    public void distribution_mergesTailIntoOther() throws Exception {
        for (int i = 0; i < 12; i++) {
            seed("e" + i, "部门" + i, "类型" + i, 0L);
        }

        JsonNode root = get();
        JsonNode dept = root.at("/deptDistribution");

        assertThat(dept.size()).isEqualTo(11);   // Top10 + 「其他」
        assertThat(dept.get(10).get("name").asText()).isEqualTo("其他");
        assertThat(dept.get(10).get("count").asInt()).isEqualTo(2);
    }

    @Test
    public void blankDeptBecomesExplicitBucket() throws Exception {
        seed("1", "", "笔记本", 0L);
        seed("2", null, "笔记本", 0L);

        JsonNode dept = get().at("/deptDistribution");

        assertThat(dept.size()).isEqualTo(1);
        assertThat(dept.get(0).get("name").asText()).isEqualTo("未填写");
        assertThat(dept.get(0).get("count").asInt()).isEqualTo(2);
    }

    @Test
    public void repeatedCallsWithinOneSecond_shareCachedSnapshot() throws Exception {
        seed("1", "行政部", "笔记本", 0L);

        long first = get().get("updatedAt").asLong();
        long second = get().get("updatedAt").asLong();

        // 1 秒内两次请求返回同一份快照 —— 多标签轮询不会放大 PDA 负载
        assertThat(second).isEqualTo(first);
    }
}

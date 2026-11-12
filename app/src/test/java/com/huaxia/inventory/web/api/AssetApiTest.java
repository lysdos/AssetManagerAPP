package com.huaxia.inventory.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.web.WebRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public class AssetApiTest {

    private AppDatabase db;
    private AssetApi api;
    private final ObjectMapper mapper = new ObjectMapper();

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries().build();
        api = new AssetApi(db);
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
        a.custodian = "张三";
        db.assetDao().upsertOne(a);
    }

    private static WebRequest req(String path, Map<String, String> query) {
        return new WebRequest("GET", path, query, Collections.emptyMap(), new byte[0]);
    }

    @Test
    public void list_returnsPagedEnvelope() throws Exception {
        for (int i = 0; i < 25; i++) seed("e" + i, "行政部", "笔记本", 0L);

        Map<String, String> q = new HashMap<>();
        q.put("page", "2");
        q.put("pageSize", "10");
        JsonNode root = mapper.readTree(api.list(req("/api/assets", q)).bytes);

        assertThat(root.get("total").asInt()).isEqualTo(25);
        assertThat(root.get("totalPages").asInt()).isEqualTo(3);
        assertThat(root.get("items").size()).isEqualTo(10);
    }

    @Test
    public void list_filtersByKeyword() throws Exception {
        seed("1001", "行政部", "笔记本", 0L);
        seed("2002", "财务部", "台式机", 0L);

        Map<String, String> q = new HashMap<>();
        q.put("keyword", "财务");
        JsonNode root = mapper.readTree(api.list(req("/api/assets", q)).bytes);

        assertThat(root.get("total").asInt()).isEqualTo(1);
        assertThat(root.at("/items/0/dept").asText()).isEqualTo("财务部");
    }

    @Test
    public void filters_exposeDeptAndTypeOptions() throws Exception {
        seed("1001", "行政部", "笔记本", 0L);
        seed("2002", "行政部", "台式机", 0L);

        JsonNode root = mapper.readTree(
                api.filters(req("/api/assets/filters", Collections.emptyMap())).bytes);

        assertThat(root.get("depts").size()).isEqualTo(1);
        assertThat(root.at("/depts/0/name").asText()).isEqualTo("行政部");
        assertThat(root.at("/depts/0/count").asInt()).isEqualTo(2);
    }

    @Test
    public void results_returnsTaskScopedPage() throws Exception {
        com.huaxia.inventory.data.entity.InventoryTask t =
                new com.huaxia.inventory.data.entity.InventoryTask();
        t.id = 5L;
        t.sourceFileName = "T";
        t.importedAt = 1L;
        t.status = "IN_PROGRESS";
        t.expectedCount = 2;
        db.inventoryTaskDao().insert(t);

        seed("1001", "行政部", "笔记本", 5L);
        seed("2002", "行政部", "笔记本", 5L);

        Map<String, String> q = new HashMap<>();
        q.put("tab", "MISSING");
        WebRequest request = req("/api/tasks/5/results", q)
                .withPathParams(Collections.singletonMap("id", "5"));
        JsonNode root = mapper.readTree(api.results(request).bytes);

        assertThat(root.get("total").asInt()).isEqualTo(2);
    }
}

package com.huaxia.inventory.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.service.TaskService;
import com.huaxia.inventory.session.InventorySession;
import com.huaxia.inventory.web.ApiException;
import com.huaxia.inventory.web.WebRequest;
import com.huaxia.inventory.web.WebResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class TaskApiTest {

    private AppDatabase db;
    private InventorySession session;
    private TaskApi api;
    private final ObjectMapper mapper = new ObjectMapper();

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries().build();
        session = new InventorySession();
        api = new TaskApi(db, new TaskService(db, session), session);
    }

    @After
    public void tearDown() { db.close(); }

    private static WebRequest req(String method, String path, String json) {
        return new WebRequest(method, path, Collections.emptyMap(),
                Collections.emptyMap(), json.getBytes(StandardCharsets.UTF_8));
    }

    private static WebRequest withId(WebRequest base, long id) {
        return base.withPathParams(Collections.singletonMap("id", String.valueOf(id)));
    }

    private void seed(String epc, String dept) {
        Asset a = new Asset();
        a.taskId = 0L;
        a.excelRowIndex = 1;
        a.epc = epc;
        a.dept = dept;
        db.assetDao().upsertOne(a);
    }

    private long createTask(String json) throws Exception {
        return mapper.readTree(api.create(req("POST", "/api/tasks", json)).bytes)
                .get("id").asLong();
    }

    @Test
    public void create_returnsTaskIdAndAssignsAssets() throws Exception {
        seed("1001", "行政部");

        WebResponse res = api.create(req("POST", "/api/tasks",
                "{\"name\":\"Q4 盘点\",\"dept\":\"行政部\",\"remark\":\"备注\"}"));

        assertThat(res.status).isEqualTo(200);
        JsonNode root = mapper.readTree(res.bytes);
        long id = root.get("id").asLong();
        assertThat(id).isPositive();
        assertThat(root.get("assigned").asInt()).isEqualTo(1);
        assertThat(db.inventoryTaskDao().findById(id).remark).isEqualTo("备注");
    }

    @Test
    public void create_rejectsBlankName() throws Exception {
        try {
            api.create(req("POST", "/api/tasks", "{\"name\":\"  \"}"));
            org.junit.Assert.fail("expected ApiException");
        } catch (ApiException e) {
            assertThat(e.code).isEqualTo("BAD_REQUEST");
        }
    }

    @Test
    public void resume_setsSessionAndReportsChange() throws Exception {
        seed("1001", "行政部");
        long id = createTask("{\"name\":\"T\"}");
        api.pause(withId(req("POST", "/api/tasks/x/pause", ""), id));

        JsonNode root = mapper.readTree(
                api.resume(withId(req("POST", "/api/tasks/x/resume", ""), id)).bytes);

        assertThat(root.get("sessionChanged").asBoolean()).isTrue();
        assertThat(session.getCurrentTaskId()).isEqualTo(id);
    }

    @Test
    public void delete_clearsSessionWhenDeletingCurrentTask() throws Exception {
        seed("1001", "行政部");
        long id = createTask("{\"name\":\"T\"}");
        session.setCurrentTaskId(id);

        api.delete(withId(req("DELETE", "/api/tasks/x", ""), id));

        assertThat(session.getCurrentTaskId()).isNull();
        assertThat(db.inventoryTaskDao().findById(id)).isNull();
        assertThat(db.assetDao().countPool()).isEqualTo(1);
    }

    @Test
    public void list_includesProgressFields() throws Exception {
        seed("1001", "行政部");
        createTask("{\"name\":\"T\"}");

        JsonNode items = mapper.readTree(
                api.list(req("GET", "/api/tasks", "")).bytes).get("items");
        assertThat(items.size()).isEqualTo(1);        assertThat(items.get(0).get("expected").asInt()).isEqualTo(1);
        assertThat(items.get(0).get("scanned").asInt()).isZero();
        assertThat(items.get(0).get("status").asText()).isEqualTo("IN_PROGRESS");
    }

    @Test
    public void detail_returnsNotFoundForMissingTask() throws Exception {
        try {
            api.detail(withId(req("GET", "/api/tasks/x", ""), 999L));
            org.junit.Assert.fail("expected ApiException");
        } catch (ApiException e) {
            assertThat(e.status).isEqualTo(404);
            assertThat(e.code).isEqualTo("TASK_NOT_FOUND");
        }
    }
}

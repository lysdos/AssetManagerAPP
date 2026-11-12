package com.huaxia.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.session.InventorySession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 真实 socket 集成测试 —— 走 NanoHTTPD 完整的请求解析路径。
 *
 * <p>单元测试直接构造 {@link WebRequest} 绕过了 NanoHTTPD 的 body 解析，
 * 所以「POST JSON 读不到 body」这类问题只有真开端口才暴露。
 */
@RunWith(RobolectricTestRunner.class)
public class WebServerIntegrationTest {

    private AppDatabase db;
    private WebServer server;
    private int port;

    @Before
    public void setUp() throws Exception {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries().build();

        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        server = new WebServer(port, ApplicationProvider.getApplicationContext(),
                db, new InventorySession());
        server.start(5000, true);
    }

    @After
    public void tearDown() {
        if (server != null) server.stop();
        db.close();
    }

    private HttpURLConnection open(String method, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path)
                .openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        return c;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private String postJson(String path, String json) throws Exception {
        HttpURLConnection c = open("POST", path);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body);
        }
        int code = c.getResponseCode();
        String text = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        return code + " " + text;
    }

    @Test
    public void get_pingReturnsOk() throws Exception {
        HttpURLConnection c = open("GET", "/api/ping");
        assertThat(c.getResponseCode()).isEqualTo(200);
        assertThat(readAll(c.getInputStream())).contains("\"ok\":true");
    }

    @Test
    public void post_jsonBodyIsParsed() throws Exception {
        String res = postJson("/api/tasks", "{\"name\":\"集成测试任务\"}");
        assertThat(res).as("POST /api/tasks response").startsWith("200");
        assertThat(db.inventoryTaskDao().findAll()).hasSize(1);
    }

    /**
     * 把所有已注册的接口真跑一遍，断言都不是 404。
     *
     * <p>路由模板写错（例如带后缀的 {@code {id}.xlsx}）不会编译报错，只会在运行期
     * 静默 404。逐个接口打一遍是最省事的兜底。
     */
    @Test
    public void allRegisteredEndpoints_areReachable() throws Exception {
        com.huaxia.inventory.data.entity.InventoryTask t =
                new com.huaxia.inventory.data.entity.InventoryTask();
        t.sourceFileName = "路由探测";
        t.status = "IN_PROGRESS";
        t.expectedCount = 0;
        long id = db.inventoryTaskDao().insert(t);

        String[][] getRoutes = {
                {"/api/ping", "GET"},
                {"/api/dashboard", "GET"},
                {"/api/tasks", "GET"},
                {"/api/tasks/depts", "GET"},
                {"/api/tasks/" + id, "GET"},
                {"/api/tasks/" + id + "/results", "GET"},
                {"/api/assets", "GET"},
                {"/api/assets/filters", "GET"},
                {"/api/export/task/" + id + ".xlsx", "GET"},
                {"/api/export/ledger.xlsx", "GET"},
                {"/api/template.xlsx", "GET"},
                {"/api/tasks/" + id + "/pause", "POST"},
                {"/api/tasks/" + id + "/resume", "POST"},
                {"/api/tasks/" + id + "/complete", "POST"},
        };

        for (String[] route : getRoutes) {
            HttpURLConnection c = open(route[1], route[0]);
            if ("POST".equals(route[1])) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(2);
                try (OutputStream os = c.getOutputStream()) {
                    os.write("{}".getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            assertThat(code).as("route " + route[1] + " " + route[0]).isNotEqualTo(404);
            if (code >= 400) {
                readAll(c.getErrorStream());
            } else {
                readAll(c.getInputStream());
            }
        }

        // DELETE 单独跑，放在最后以免影响上面的任务路由
        HttpURLConnection del = open("DELETE", "/api/tasks/" + id);
        assertThat(del.getResponseCode()).as("DELETE /api/tasks/{id}").isNotEqualTo(404);
        readAll(del.getInputStream());

        HttpURLConnection clear = open("DELETE", "/api/tasks/completed");
        assertThat(clear.getResponseCode()).as("DELETE /api/tasks/completed").isNotEqualTo(404);
        readAll(clear.getInputStream());
    }
}

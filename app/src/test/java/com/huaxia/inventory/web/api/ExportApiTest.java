package com.huaxia.inventory.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.web.ApiException;
import com.huaxia.inventory.web.WebRequest;
import com.huaxia.inventory.web.WebResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class ExportApiTest {

    private AppDatabase db;
    private ExportApi api;
    private File exportDir;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase.class)
                .allowMainThreadQueries().build();
        exportDir = new File(ctx.getCacheDir(), "web-export-test");
        api = new ExportApi(db, ctx, exportDir);
    }

    @After
    public void tearDown() { db.close(); }

    private long seedTask() {
        InventoryTask t = new InventoryTask();
        t.sourceFileName = "行政部盘点";
        t.importedAt = System.currentTimeMillis();
        t.status = "IN_PROGRESS";
        t.expectedCount = 1;
        long id = db.inventoryTaskDao().insert(t);

        Asset a = new Asset();
        a.taskId = id;
        a.excelRowIndex = 1;
        a.epc = "1001";
        a.dept = "行政部";
        a.assetType = "笔记本";
        a.rawRowJson = "{\"序号\":\"1\",\"设备类型\":\"笔记本\",\"设备型号\":\"X1\","
                + "\"序列号\":\"SN1\",\"固资编号\":\"1001\",\"领用部门\":\"行政部\","
                + "\"领用人\":\"张三\",\"盘点日期\":\"\"}";
        db.assetDao().upsertOne(a);
        return id;
    }

    private static WebRequest req(String path, long id) {
        return new WebRequest("GET", path, Collections.emptyMap(), Collections.emptyMap(),
                new byte[0]).withPathParams(Collections.singletonMap("id", String.valueOf(id)));
    }

    @Test
    public void taskReport_setsXlsxMimeAndEncodedFilename() throws Exception {
        long id = seedTask();

        WebResponse res = api.taskReport(req("/api/export/task/x.xlsx", id));

        assertThat(res.mime).contains("spreadsheetml");
        assertThat(res.headers.get("Content-Disposition")).contains("filename*=UTF-8''");
        assertThat(res.length).isPositive();
        assertThat(res.stream).isNotNull();
        res.stream.close();
    }

    @Test
    public void taskReport_returns404ForMissingTask() throws Exception {
        try {
            api.taskReport(req("/api/export/task/x.xlsx", 999L));
            org.junit.Assert.fail("expected ApiException");
        } catch (ApiException e) {
            assertThat(e.code).isEqualTo("TASK_NOT_FOUND");
        }
    }

    @Test
    public void ledger_returnsXlsxForEmptyDatabase() throws Exception {
        WebResponse res = api.ledger(new WebRequest("GET", "/api/export/ledger.xlsx",
                Collections.emptyMap(), Collections.emptyMap(), new byte[0]));

        assertThat(res.mime).contains("spreadsheetml");
        assertThat(res.length).isPositive();
        res.stream.close();
    }
}

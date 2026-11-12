package com.huaxia.inventory.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.web.ApiException;
import com.huaxia.inventory.web.WebRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public class ImportApiTest {

    private AppDatabase db;
    private ImportApi api;
    private File uploadDir;

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries().build();
        uploadDir = new File(ApplicationProvider.getApplicationContext().getCacheDir(),
                "web-upload-test");
        api = new ImportApi(db, uploadDir);
    }

    @After
    public void tearDown() { db.close(); }

    private byte[] templateBytes() throws Exception {
        try (InputStream in = ApplicationProvider.getApplicationContext()
                .getAssets().open("import_template.xlsx")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private WebRequest upload(byte[] body) {
        Map<String, String> q = new HashMap<>();
        q.put("filename", "assets.xlsx");
        return new WebRequest("POST", "/api/import", q, Collections.emptyMap(), body);
    }

    @Test
    public void upload_rejectsEmptyBody() throws Exception {
        try {
            api.upload(upload(new byte[0]));
            org.junit.Assert.fail("expected ApiException");
        } catch (ApiException e) {
            assertThat(e.code).isEqualTo("BAD_REQUEST");
        }
    }

    @Test
    public void upload_rejectsNonXlsxBytes() throws Exception {
        try {
            api.upload(upload("not an excel file".getBytes("UTF-8")));
            org.junit.Assert.fail("expected ApiException");
        } catch (ApiException e) {
            assertThat(e.code).isEqualTo("IMPORT_PARSE_FAILED");
        }
    }

    @Test
    public void upload_cleansUpTempFile() throws Exception {
        try {
            api.upload(upload(templateBytes()));
        } catch (ApiException ignored) {
            // 模板文件可能没有数据行，失败也可接受 —— 本用例只校验临时文件清理
        }
        File[] left = uploadDir.listFiles();
        assertThat(left == null ? 0 : left.length).isZero();
    }
}

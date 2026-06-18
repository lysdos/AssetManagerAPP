package com.huaxia.inventory.ui.import_;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.net.Uri;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Observer;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.excel.DiagnoseRealFileTest;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = InventoryApp.class)
public class _DiagnoseViewModelTest {

    @Rule public InstantTaskExecutorRule instant = new InstantTaskExecutorRule();

    private InventoryApp app;
    private ImportViewModel vm;
    private AppDatabase inMemoryDb;

    @Before
    public void setUp() {
        Application application = ApplicationProvider.getApplicationContext();
        app = (InventoryApp) application;
        // 用 in-memory DB 替换真实 DB, 避免污染
        inMemoryDb = Room.inMemoryDatabaseBuilder(application, AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        app.db = inMemoryDb;

        vm = new ImportViewModel(application);
    }

    @After
    public void tearDown() {
        inMemoryDb.close();
    }

    @Test
    public void parseFile_realFile_20rows() throws Exception {
        parseReal("C:\\Users\\Asus\\Desktop\\固定资产测试\\20条补0数据测试.xlsx", "20条");
    }

    @Test
    public void parseFile_realFile_big() throws Exception {
        parseReal("C:\\Users\\Asus\\Desktop\\固定资产测试\\固定资产测试.xlsx", "big");
    }

    private void parseReal(String path, String tag) throws Exception {
        File f = new File(path);
        if (!f.exists()) {
            System.out.println("[" + tag + "] file not found, skip: " + path);
            return;
        }
        Uri uri = Uri.fromFile(f);
        System.out.println("[" + tag + "] uri=" + uri);

        AtomicReference<ImportViewModel.ParsedFile> captured = new AtomicReference<>();
        AtomicReference<String> parseErr = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        // Observer 必须用 main thread, InstantTaskExecutorRule 让 postValue 同步执行
        vm.parsedFile().observeForever(new Observer<ImportViewModel.ParsedFile>() {
            @Override public void onChanged(ImportViewModel.ParsedFile pf) {
                if (pf != null) {
                    captured.set(pf);
                    done.countDown();
                }
            }
        });
        vm.parseError().observeForever(err -> {
            if (err != null) {
                parseErr.set(err);
                done.countDown();
            }
        });

        vm.parseFile(uri, f.getName());

        if (!done.await(30, TimeUnit.SECONDS)) {
            fail("[" + tag + "] timeout — parseFile didn't deliver parsedFile or parseError within 30s");
        }

        ImportViewModel.ParsedFile pf = captured.get();
        String perr = parseErr.get();
        System.out.println("[" + tag + "] parseError=" + perr);
        System.out.println("[" + tag + "] parsedFile.successCount=" + (pf == null ? "null" : pf.successCount));
        System.out.println("[" + tag + "] parsedFile.errorCount=" + (pf == null ? "null" : pf.errorCount));
        if (pf != null) {
            System.out.println("[" + tag + "] preview=" + pf.preview);
            System.out.println("[" + tag + "] errorSample=" + pf.errorSample);
        }

        assertNotNull("[" + tag + "] parsedFile should be delivered", pf);
        assertTrue("[" + tag + "] successCount should > 0, got " + pf.successCount, pf.successCount > 0);
    }
}

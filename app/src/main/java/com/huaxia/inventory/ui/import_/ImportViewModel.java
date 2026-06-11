package com.huaxia.inventory.ui.import_;

import android.app.Application;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.excel.ExcelImporter;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.Executor;

public class ImportViewModel extends AndroidViewModel {

    public static class ParsedFile {
        public final String fileName;
        public final int successCount;
        public final List<String> errors;
        public final List<Asset> preview;
        public ParsedFile(String n, int s, List<String> e, List<Asset> p) {
            this.fileName = n; this.successCount = s; this.errors = e; this.preview = p;
        }
    }

    private final Executor diskIO;
    private final AppDatabase db;
    private final MutableLiveData<ParsedFile> parsedFile = new MutableLiveData<>();
    private final MutableLiveData<String> parseError = new MutableLiveData<>();
    public List<Asset> lastParsedAssets;

    public ImportViewModel(@NonNull Application app) {
        super(app);
        InventoryApp ia = (InventoryApp) app;
        this.diskIO = ia.executors.diskIO;
        this.db = ia.db;
    }

    public LiveData<ParsedFile> parsedFile() { return parsedFile; }
    public LiveData<String> parseError() { return parseError; }

    public void parseFile(Uri uri, String displayName) {
        diskIO.execute(() -> {
            try (InputStream in = getApplication().getContentResolver().openInputStream(uri)) {
                ExcelImporter.Result result = ExcelImporter.parse(in, 0L);
                List<Asset> preview = result.assets.subList(0, Math.min(10, result.assets.size()));
                lastParsedAssets = result.assets;
                parsedFile.postValue(new ParsedFile(displayName, result.success, result.errors, preview));
            } catch (Exception e) {
                parseError.postValue(e.getMessage());
                parsedFile.postValue(new ParsedFile(displayName, 0,
                        java.util.Collections.singletonList("Parse error: " + e.getMessage()),
                        java.util.Collections.emptyList()));
            }
        });
    }

    public void commitToTask(String fileName, List<Asset> assets, ImportCallback cb) {
        diskIO.execute(() -> {
            InventoryTask task = new InventoryTask();
            task.sourceFileName = fileName;
            task.importedAt = System.currentTimeMillis();
            task.status = "IN_PROGRESS";
            task.expectedCount = assets.size();
            long taskId = db.inventoryTaskDao().insert(task);

            for (Asset a : assets) {
                a.taskId = taskId;
            }
            db.assetDao().insertAll(assets);

            cb.onCommitted(taskId);
        });
    }

    public interface ImportCallback {
        void onCommitted(long taskId);
    }
}

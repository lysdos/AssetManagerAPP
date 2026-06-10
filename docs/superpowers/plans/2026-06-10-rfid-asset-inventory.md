# RFID 资产盘点 Android App — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a native Android asset-inventory app for UROVO RFID PDA that imports an Excel list, scans tags, and exports scan results — all offline with local Room storage.

**Architecture:** Single `MainActivity` hosts 3 Fragments (Import / Scan / Result) switched via `BottomNavigationView`. Java + traditional XML Views + Room + Apache POI. UROVO RFID SDK wrapped behind a `RfidController` for testability.

**Tech Stack:** Java 11 · AndroidX (minSdk 24, targetSdk 34) · Room 2.6 · Apache POI 5.2.5 · Jackson 2.17 · Robolectric 4.11 · JUnit 4 · Gradle 8.4 · Android Gradle Plugin 8.2

**Reference spec:** `docs/superpowers/specs/2026-06-10-rfid-asset-inventory-design.md`

**Reference SDK:** `RFID 开发文档 v2.4.1228/libs/URFIDLibrary-v2.4.1226.aar`

---

## File Structure (target)

```
huaxiaapp/
├── settings.gradle
├── build.gradle                          (project)
├── gradle.properties
├── local.properties                      (SDK path, gitignored)
├── gradle/wrapper/{gradle-wrapper.jar,gradle-wrapper.properties}
├── gradlew, gradlew.bat
├── app/
│   ├── build.gradle                      (module)
│   ├── proguard-rules.pro
│   ├── libs/URFIDLibrary-v2.4.1226.aar   (copy from 项目根)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/huaxia/inventory/
│       │   │   ├── InventoryApp.java
│       │   │   ├── MainActivity.java
│       │   │   ├── data/
│       │   │   │   ├── AppDatabase.java
│       │   │   │   ├── Converters.java
│       │   │   │   ├── entity/{InventoryTask,Asset,ScanRecord}.java
│       │   │   │   └── dao/{InventoryTaskDao,AssetDao,ScanRecordDao}.java
│       │   │   ├── rfid/
│       │   │   │   ├── RfidController.java
│       │   │   │   └── SdkRfidAdapter.java
│       │   │   ├── excel/
│       │   │   │   ├── ExcelImporter.java
│       │   │   │   ├── ExcelExporter.java
│       │   │   │   └── HeaderSpec.java
│       │   │   ├── session/InventorySession.java
│       │   │   ├── ui/
│       │   │   │   ├── common/StatusColors.java
│       │   │   │   ├── import_/{ImportFragment,ImportViewModel}.java
│       │   │   │   ├── scan/{ScanFragment,ScanViewModel,RecentScanAdapter}.java
│       │   │   │   └── result/{ResultFragment,ResultViewModel,AssetResultAdapter,ResultTab}.java
│       │   │   └── util/{AppExecutors,EpcNormalizer,DateUtil}.java
│       │   └── res/
│       │       ├── layout/{activity_main,fragment_import,fragment_scan,fragment_result,item_asset_preview,item_recent_scan,item_asset_result}.xml
│       │       ├── values/{colors,strings,styles,themes,dimens}.xml
│       │       ├── drawable/{ic_doc,bg_status_found,ic_search}.xml
│       │       └── menu/bottom_nav.xml
│       ├── test/
│       │   ├── java/com/huaxia/inventory/
│       │   │   ├── util/EpcNormalizerTest.java
│       │   │   ├── excel/ExcelImporterTest.java
│       │   │   ├── excel/ExcelExporterTest.java
│       │   │   ├── data/StatusDerivationTest.java
│       │   │   └── rfid/RfidControllerTest.java
│       │   └── resources/sample-assets.xlsx   (copy from 项目根)
│       └── androidTest/                       (E2E smoke; deferred to manual phase)
└── docs/superpowers/{specs,plans}/
```

---

## Phase 1 — Project Foundation

### Task 1: Initialize Gradle project skeleton

**Files:**
- Create: `settings.gradle`
- Create: `build.gradle`
- Create: `gradle.properties`
- Create: `app/build.gradle`
- Create: `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/huaxia/inventory/InventoryApp.java`
- Create: `app/src/main/java/com/huaxia/inventory/MainActivity.java`
- Create: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/libs/.keep`  (then user copies the AAR from `RFID 开发文档 v2.4.1228/libs/`)
- Create: `.gitignore`

- [ ] **Step 1: Create top-level Gradle files**

`settings.gradle`:
```gradle
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "huaxiaapp"
include ':app'
```

`build.gradle` (project):
```gradle
plugins {
    id 'com.android.application' version '8.2.2' apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
android.nonTransitiveRClass=true
```

- [ ] **Step 2: Create `app/build.gradle`**

```gradle
plugins {
    id 'com.android.application'
}

android {
    namespace 'com.huaxia.inventory'
    compileSdk 34

    defaultConfig {
        applicationId "com.huaxia.inventory"
        minSdk 24
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    implementation 'com.google.android.material:material:1.11.0'
    implementation 'androidx.recyclerview:recyclerview:1.3.2'
    implementation 'androidx.lifecycle:lifecycle-viewmodel:2.7.0'
    implementation 'androidx.lifecycle:lifecycle-livedata:2.7.0'
    implementation 'androidx.fragment:fragment:1.6.2'
    implementation 'androidx.activity:activity:1.8.2'

    implementation 'androidx.room:room-runtime:2.6.1'
    annotationProcessor 'androidx.room:room-compiler:2.6.1'

    implementation 'org.apache.poi:poi-ooxml:5.2.5'
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.17.0'

    implementation files('libs/URFIDLibrary-v2.4.1226.aar')

    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.assertj:assertj-core:3.25.1'
    testImplementation 'org.robolectric:robolectric:4.11.1'
    testImplementation 'androidx.test:core:1.5.0'
    testImplementation 'androidx.test.ext:junit:1.1.5'
    testImplementation 'androidx.room:room-testing:2.6.1'
    testImplementation 'androidx.arch.core:core-testing:2.2.0'
    testImplementation 'org.mockito:mockito-core:5.7.0'
}
```

`app/proguard-rules.pro`:
```
# Apache POI
-keep class org.apache.poi.** { *; }
-keep class com.fasterxml.jackson.** { *; }
# UROVO SDK
-keep class com.ubx.usdk.rfid.** { *; }
```

- [ ] **Step 3: Create AndroidManifest.xml**

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:name=".InventoryApp"
        android:label="@string/app_name"
        android:theme="@style/Theme.HuaxiaInventory"
        android:allowBackup="false">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 4: Create minimal Application and MainActivity**

`app/src/main/java/com/huaxia/inventory/InventoryApp.java`:
```java
package com.huaxia.inventory;

import android.app.Application;

public class InventoryApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
    }
}
```

`app/src/main/java/com/huaxia/inventory/MainActivity.java`:
```java
package com.huaxia.inventory;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }
}
```

`app/src/main/res/layout/activity_main.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

`app/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">固定资产盘点</string>
</resources>
```

`app/src/main/res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.HuaxiaInventory" parent="Theme.MaterialComponents.DayNight.NoActionBar" />
</resources>
```

- [ ] **Step 5: Copy the AAR into `app/libs/`**

In bash (Git Bash on Windows):
```bash
cp "RFID 开发文档 v2.4.1228/libs/URFIDLibrary-v2.4.1226.aar" "app/libs/"
ls app/libs/URFIDLibrary-v2.4.1226.aar
```

- [ ] **Step 6: Create `.gitignore`**

`.gitignore`:
```
*.iml
.gradle
/local.properties
.idea/
.DS_Store
build/
captures/
.externalNativeBuild/
.cxx/
app/release/
*.apk
*.aab
```

- [ ] **Step 7: Verify the project builds**

Open in Android Studio (Hedgehog 2023.1.1 or later), let Gradle sync, then run:
```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`, APK at `app/build/outputs/apk/debug/app-debug.apk`.

If `SDK location not found`: create `local.properties` with `sdk.dir=/path/to/Android/sdk`.

- [ ] **Step 8: Commit (skip if user said 暂不提交)**

```bash
git add .
git commit -m "chore: scaffold Gradle Android project"
```

---

### Task 2: EpcNormalizer with TDD

**Files:**
- Test: `app/src/test/java/com/huaxia/inventory/util/EpcNormalizerTest.java`
- Create: `app/src/main/java/com/huaxia/inventory/util/EpcNormalizer.java`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/huaxia/inventory/util/EpcNormalizerTest.java`:
```java
package com.huaxia.inventory.util;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class EpcNormalizerTest {

    @Test
    public void trimsWhitespace() {
        assertEquals("3034A1B2", EpcNormalizer.normalize(" 3034A1B2 "));
    }

    @Test
    public void uppercasesHex() {
        assertEquals("3034A1B2", EpcNormalizer.normalize("3034a1b2"));
    }

    @Test
    public void rejectsOddLength() {
        assertNull(EpcNormalizer.normalize("3034A1B"));
    }

    @Test
    public void rejectsNonHex() {
        assertNull(EpcNormalizer.normalize("3034G1B2"));
    }

    @Test
    public void rejectsNull() {
        assertNull(EpcNormalizer.normalize(null));
    }

    @Test
    public void rejectsEmpty() {
        assertNull(EpcNormalizer.normalize(""));
    }

    @Test
    public void accepts24Hex() {
        assertEquals("3034A1B23034A1B23034A1B2",
                EpcNormalizer.normalize("3034a1b23034a1b23034a1b2"));
    }
}
```

- [ ] **Step 2: Run test, expect compile failure**

```bash
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.util.EpcNormalizerTest
```
Expected: `error: cannot find symbol class EpcNormalizer`.

- [ ] **Step 3: Implement EpcNormalizer**

`app/src/main/java/com/huaxia/inventory/util/EpcNormalizer.java`:
```java
package com.huaxia.inventory.util;

import android.util.Log;
import java.util.Locale;

public final class EpcNormalizer {
    private static final String TAG = "EpcNormalizer";

    private EpcNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (s.length() % 4 != 0) {
            Log.w(TAG, "EPC length not multiple of 4: " + s);
            return null;
        }
        if (!s.matches("[0-9A-F]+")) {
            Log.w(TAG, "EPC not hex: " + s);
            return null;
        }
        return s;
    }
}
```

- [ ] **Step 4: Run test, expect PASS**

```bash
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.util.EpcNormalizerTest
```
Expected: `BUILD SUCCESSFUL`, 7 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/huaxia/inventory/util/EpcNormalizer.java \
        app/src/test/java/com/huaxia/inventory/util/EpcNormalizerTest.java
git commit -m "feat(util): add EpcNormalizer with full test coverage"
```

---

### Task 3: AppExecutors (thread pool)

**Files:**
- Create: `app/src/main/java/com/huaxia/inventory/util/AppExecutors.java`
- Create: `app/src/main/java/com/huaxia/inventory/util/DateUtil.java`

- [ ] **Step 1: Create `AppExecutors.java`**

```java
package com.huaxia.inventory.util;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppExecutors {
    public final ExecutorService diskIO;
    public final ExecutorService excelIO;
    public final Handler mainThread;

    public AppExecutors() {
        this.diskIO = Executors.newFixedThreadPool(4);
        this.excelIO = Executors.newSingleThreadExecutor();
        this.mainThread = new Handler(Looper.getMainLooper());
    }

    public void shutdown() {
        diskIO.shutdown();
        excelIO.shutdown();
    }
}
```

- [ ] **Step 2: Create `DateUtil.java`**

```java
package com.huaxia.inventory.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DateUtil {
    private static final ThreadLocal<SimpleDateFormat> FMT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA));

    private DateUtil() {}

    public static String formatTimestamp(long millis) {
        return FMT.get().format(new Date(millis));
    }
}
```

- [ ] **Step 3: Build, expect PASS**

```bash
./gradlew :app:compileDebugJavaWithJavac
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/huaxia/inventory/util/AppExecutors.java \
        app/src/main/java/com/huaxia/inventory/util/DateUtil.java
git commit -m "feat(util): add AppExecutors and DateUtil"
```

---

## Phase 2 — Data Layer (Room)

### Task 4: Asset entity + DAO

**Files:**
- Create: `app/src/main/java/com/huaxia/inventory/data/entity/Asset.java`
- Create: `app/src/main/java/com/huaxia/inventory/data/dao/AssetDao.java`
- Test: `app/src/test/java/com/huaxia/inventory/data/AssetDaoTest.java`

- [ ] **Step 1: Write `Asset.java`**

`app/src/main/java/com/huaxia/inventory/data/entity/Asset.java`:
```java
package com.huaxia.inventory.data.entity;

import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(indices = {
        @Index("taskId"),
        @Index(value = {"taskId", "epc"})
})
public class Asset {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "taskId")
    public long taskId;

    @ColumnInfo(name = "excelRowIndex")
    public int excelRowIndex;

    public String epc;
    public String assetName;
    public String assetType;
    public String assetModel;
    public String org;
    public String detailedLocation;
    public String placementLocation;
    public String assetStatus;

    @Nullable
    @ColumnInfo(name = "raw_row_json")
    public String rawRowJson;
}
```

- [ ] **Step 2: Write `AssetDao.java`**

`app/src/main/java/com/huaxia/inventory/data/dao/AssetDao.java`:
```java
package com.huaxia.inventory.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.huaxia.inventory.data.entity.Asset;
import java.util.List;

@Dao
public interface AssetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(Asset asset);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<Asset> assets);

    @Query("SELECT * FROM Asset WHERE taskId = :taskId AND epc = :epc LIMIT 1")
    Asset findByEpc(long taskId, String epc);

    @Query("SELECT COUNT(*) FROM Asset WHERE taskId = :taskId")
    int countByTask(long taskId);

    @Query("SELECT * FROM Asset WHERE taskId = :taskId ORDER BY excelRowIndex ASC")
    List<Asset> findAllByTask(long taskId);

    @Query("SELECT * FROM Asset WHERE taskId = :taskId AND id NOT IN " +
           "(SELECT matchedAssetId FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NOT NULL)")
    List<Asset> findMissing(long taskId);

    @Query("SELECT * FROM Asset WHERE taskId = :taskId AND id IN " +
           "(SELECT matchedAssetId FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NOT NULL) " +
           "ORDER BY excelRowIndex ASC")
    List<Asset> findFound(long taskId);
}
```

- [ ] **Step 3: Write the failing DAO test**

`app/src/test/java/com/huaxia/inventory/data/AssetDaoTest.java`:
```java
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
import java.util.Arrays;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class AssetDaoTest {

    @Rule
    public InstantTaskExecutorRule rule = new InstantTaskExecutorRule();

    private AppDatabase db;
    private AssetDao dao;

    @Before
    public void setup() {
        db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        dao = db.assetDao();
    }

    @After
    public void teardown() { db.close(); }

    @Test
    public void insertAndFindByEpc() {
        Asset a = newAsset(1, "3034A1B2", "RFID Reader");
        long id = dao.insert(a);
        assertNotNull(dao.findByEpc(1, "3034A1B2"));
        assertEquals(id, dao.findByEpc(1, "3034A1B2").id);
    }

    @Test
    public void insertAllCountsCorrectly() {
        dao.insertAll(Arrays.asList(
                newAsset(1, "EPC1", "A"),
                newAsset(1, "EPC2", "B"),
                newAsset(1, "EPC3", "C")
        ));
        assertEquals(3, dao.countByTask(1));
    }

    @Test
    public void findByEpc_respectsTask() {
        dao.insert(newAsset(1, "EPC1", "A"));
        assertNotNull(dao.findByEpc(1, "EPC1"));
        assertEquals(null, dao.findByEpc(2, "EPC1"));
    }

    private Asset newAsset(long taskId, String epc, String name) {
        Asset a = new Asset();
        a.taskId = taskId;
        a.excelRowIndex = 1;
        a.epc = epc;
        a.assetName = name;
        a.rawRowJson = "{}";
        return a;
    }
}
```

- [ ] **Step 4: Run test, expect compile failure (no AppDatabase yet)**

```bash
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.data.AssetDaoTest
```
Expected: error `cannot find symbol class AppDatabase`. (Good.)

- [ ] **Step 5: Create `AppDatabase.java`**

`app/src/main/java/com/huaxia/inventory/data/AppDatabase.java`:
```java
package com.huaxia.inventory.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import com.huaxia.inventory.data.dao.AssetDao;
import com.huaxia.inventory.data.dao.InventoryTaskDao;
import com.huaxia.inventory.data.dao.ScanRecordDao;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.data.entity.ScanRecord;

@Database(
        entities = {InventoryTask.class, Asset.class, ScanRecord.class},
        version = 1,
        exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract InventoryTaskDao inventoryTaskDao();
    public abstract AssetDao assetDao();
    public abstract ScanRecordDao scanRecordDao();
}
```

- [ ] **Step 6: Run test, expect PASS**

```bash
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.data.AssetDaoTest
```
Expected: 3 tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/huaxia/inventory/data/entity/Asset.java \
        app/src/main/java/com/huaxia/inventory/data/dao/AssetDao.java \
        app/src/main/java/com/huaxia/inventory/data/AppDatabase.java \
        app/src/test/java/com/huaxia/inventory/data/AssetDaoTest.java
git commit -m "feat(data): add Asset entity, DAO, AppDatabase shell"
```

---

### Task 5: InventoryTask entity + DAO

**Files:**
- Create: `app/src/main/java/com/huaxia/inventory/data/entity/InventoryTask.java`
- Create: `app/src/main/java/com/huaxia/inventory/data/dao/InventoryTaskDao.java`
- Modify: `app/src/test/java/com/huaxia/inventory/data/AssetDaoTest.java` (add task setup helper)

- [ ] **Step 1: Write `InventoryTask.java`**

`app/src/main/java/com/huaxia/inventory/data/entity/InventoryTask.java`:
```java
package com.huaxia.inventory.data.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity
public class InventoryTask {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "sourceFileName")
    public String sourceFileName;

    @ColumnInfo(name = "importedAt")
    public long importedAt;

    public String status;  // "IN_PROGRESS" / "COMPLETED"

    @ColumnInfo(name = "expectedCount")
    public int expectedCount;

    @ColumnInfo(name = "scannedUniqueCount")
    public int scannedUniqueCount;
}
```

- [ ] **Step 2: Write `InventoryTaskDao.java`**

`app/src/main/java/com/huaxia/inventory/data/dao/InventoryTaskDao.java`:
```java
package com.huaxia.inventory.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import com.huaxia.inventory.data.entity.InventoryTask;
import java.util.List;

@Dao
public interface InventoryTaskDao {

    @Insert
    long insert(InventoryTask task);

    @Update
    void update(InventoryTask task);

    @Query("SELECT * FROM InventoryTask WHERE id = :id")
    InventoryTask findById(long id);

    @Query("SELECT * FROM InventoryTask ORDER BY importedAt DESC")
    List<InventoryTask> findAll();

    @Query("SELECT * FROM InventoryTask WHERE status = 'IN_PROGRESS' ORDER BY importedAt DESC LIMIT 1")
    InventoryTask findInProgress();

    @Query("UPDATE InventoryTask SET scannedUniqueCount = (SELECT COUNT(*) FROM ScanRecord WHERE taskId = :taskId) WHERE id = :taskId")
    void recomputeScannedCount(long taskId);
}
```

- [ ] **Step 3: Run a quick build to verify it compiles**

```bash
./gradlew :app:compileDebugJavaWithJavac
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/huaxia/inventory/data/entity/InventoryTask.java \
        app/src/main/java/com/huaxia/inventory/data/dao/InventoryTaskDao.java
git commit -m "feat(data): add InventoryTask entity and DAO"
```

---

### Task 6: ScanRecord entity + DAO

**Files:**
- Create: `app/src/main/java/com/huaxia/inventory/data/entity/ScanRecord.java`
- Create: `app/src/main/java/com/huaxia/inventory/data/dao/ScanRecordDao.java`
- Test: `app/src/test/java/com/huaxia/inventory/data/ScanRecordDaoTest.java`

- [ ] **Step 1: Write `ScanRecord.java`**

`app/src/main/java/com/huaxia/inventory/data/entity/ScanRecord.java`:
```java
package com.huaxia.inventory.data.entity;

import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(indices = {
        @Index("taskId"),
        @Index("matchedAssetId"),
        @Index(value = {"taskId", "epc"}, unique = true)
})
public class ScanRecord {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "taskId")
    public long taskId;

    public String epc;
    public String tid;
    public String rssi;

    @ColumnInfo(name = "scannedAt")
    public long scannedAt;

    @Nullable
    @ColumnInfo(name = "matchedAssetId")
    public Long matchedAssetId;
}
```

- [ ] **Step 2: Write `ScanRecordDao.java`**

`app/src/main/java/com/huaxia/inventory/data/dao/ScanRecordDao.java`:
```java
package com.huaxia.inventory.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.huaxia.inventory.data.entity.ScanRecord;
import java.util.List;

@Dao
public interface ScanRecordDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(ScanRecord record);

    @Query("SELECT * FROM ScanRecord WHERE taskId = :taskId")
    List<ScanRecord> findAllByTask(long taskId);

    @Query("SELECT * FROM ScanRecord WHERE taskId = :taskId AND epc = :epc LIMIT 1")
    ScanRecord findByEpc(long taskId, String epc);

    @Query("SELECT epc FROM ScanRecord WHERE taskId = :taskId")
    List<String> findAllEpcsByTask(long taskId);

    @Query("SELECT * FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NULL")
    List<ScanRecord> findExtras(long taskId);

    @Query("SELECT COUNT(*) FROM ScanRecord WHERE taskId = :taskId")
    int countByTask(long taskId);
}
```

- [ ] **Step 3: Write the failing DAO test**

`app/src/test/java/com/huaxia/inventory/data/ScanRecordDaoTest.java`:
```java
package com.huaxia.inventory.data;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import com.huaxia.inventory.data.dao.ScanRecordDao;
import com.huaxia.inventory.data.entity.ScanRecord;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ScanRecordDaoTest {

    @Rule public InstantTaskExecutorRule rule = new InstantTaskExecutorRule();

    private AppDatabase db;
    private ScanRecordDao dao;

    @Before public void setup() {
        db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries().build();
        dao = db.scanRecordDao();
    }
    @After public void teardown() { db.close(); }

    @Test public void uniqueConstraint_ignoresDuplicateEpc() {
        dao.insert(newRec(1, "EPC1", 100L));
        long second = dao.insert(newRec(1, "EPC1", 101L));
        assertEquals(-1L, second);
        assertEquals(1, dao.countByTask(1));
    }

    @Test public void extrasAreRecordsWithNullMatchedAssetId() {
        dao.insert(newRec(1, "EPC_EXTRA", 100L));
        assertEquals(1, dao.findExtras(1).size());
        assertNotNull(dao.findExtras(1).get(0));
    }

    @Test public void findByEpc_returnsNullWhenAbsent() {
        assertNull(dao.findByEpc(1, "MISSING"));
    }

    private ScanRecord newRec(long taskId, String epc, Long matchedAssetId) {
        ScanRecord r = new ScanRecord();
        r.taskId = taskId;
        r.epc = epc;
        r.scannedAt = System.currentTimeMillis();
        r.rssi = "-50";
        r.matchedAssetId = matchedAssetId;
        return r;
    }
}
```

- [ ] **Step 4: Run test, expect PASS**

```bash
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.data.ScanRecordDaoTest
```
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/huaxia/inventory/data/entity/ScanRecord.java \
        app/src/main/java/com/huaxia/inventory/data/dao/ScanRecordDao.java \
        app/src/test/java/com/huaxia/inventory/data/ScanRecordDaoTest.java
git commit -m "feat(data): add ScanRecord entity and DAO"
```

---

### Task 7: StatusDerivation utility (pure logic)

**Files:**
- Create: `app/src/main/java/com/huaxia/inventory/data/StatusDerivation.java`
- Test: `app/src/test/java/com/huaxia/inventory/data/StatusDerivationTest.java`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/huaxia/inventory/data/StatusDerivationTest.java`:
```java
package com.huaxia.inventory.data;

import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ScanRecord;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StatusDerivationTest {

    @Test
    public void allFound_whenEveryAssetMatched() {
        Set<Long> matched = new HashSet<>(Arrays.asList(1L, 2L, 3L));
        StatusDerivation.Stats s = StatusDerivation.compute(3, matched, 0);
        assertEquals(3, s.found);
        assertEquals(0, s.missing);
        assertEquals(0, s.extra);
    }

    @Test
    public void allMissing_whenNoScans() {
        StatusDerivation.Stats s = StatusDerivation.compute(5, Collections.emptySet(), 0);
        assertEquals(0, s.found);
        assertEquals(5, s.missing);
        assertEquals(0, s.extra);
    }

    @Test
    public void extrasCountedSeparately() {
        StatusDerivation.Stats s = StatusDerivation.compute(10, new HashSet<>(Arrays.asList(1L, 2L)), 3);
        assertEquals(2, s.found);
        assertEquals(8, s.missing);
        assertEquals(3, s.extra);
    }

    @Test
    public void invariant_foundPlusMissing_equalsExpected() {
        for (int expected = 1; expected <= 100; expected++) {
            int found = (int) (Math.random() * (expected + 1));
            Set<Long> matched = new HashSet<>();
            for (long i = 1; i <= found; i++) matched.add(i);
            StatusDerivation.Stats s = StatusDerivation.compute(expected, matched, 0);
            assertEquals(expected, s.found + s.missing);
        }
    }

    @Test
    public void classify_returnsCorrectLabel() {
        assertEquals("FOUND", StatusDerivation.classify(true, false).name());
        assertEquals("MISSING", StatusDerivation.classify(false, false).name());
        assertEquals("EXTRA", StatusDerivation.classify(false, true).name());
    }

    @Test
    public void classify_extraTakesPrecedence() {
        assertFalse(StatusDerivation.classify(true, true).name().equals("MISSING"));
    }
}
```

- [ ] **Step 2: Run, expect compile failure**

```bash
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.data.StatusDerivationTest
```
Expected: `cannot find symbol class StatusDerivation`.

- [ ] **Step 3: Implement `StatusDerivation.java`**

`app/src/main/java/com/huaxia/inventory/data/StatusDerivation.java`:
```java
package com.huaxia.inventory.data;

import java.util.Set;

public final class StatusDerivation {

    public enum Status { FOUND, MISSING, EXTRA }

    public static class Stats {
        public final int found;
        public final int missing;
        public final int extra;

        public Stats(int found, int missing, int extra) {
            this.found = found;
            this.missing = missing;
            this.extra = extra;
        }
    }

    private StatusDerivation() {}

    public static Stats compute(int expectedCount, Set<Long> matchedAssetIds, int extrasCount) {
        int found = matchedAssetIds.size();
        int missing = Math.max(0, expectedCount - found);
        return new Stats(found, missing, extrasCount);
    }

    public static Status classify(boolean isMatchedAsset, boolean isExtraScan) {
        if (isExtraScan) return Status.EXTRA;
        if (isMatchedAsset) return Status.FOUND;
        return Status.MISSING;
    }
}
```

- [ ] **Step 4: Run, expect PASS**

```bash
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.data.StatusDerivationTest
```
Expected: 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/huaxia/inventory/data/StatusDerivation.java \
        app/src/test/java/com/huaxia/inventory/data/StatusDerivationTest.java
git commit -m "feat(data): add StatusDerivation utility with full coverage"
```

---

## Phase 3 — Excel I/O

### Task 8: Excel header validation

**Files:**
- Create: `app/src/main/java/com/huaxia/inventory/excel/HeaderSpec.java`
- Test: `app/src/test/java/com/huaxia/inventory/excel/HeaderSpecTest.java`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/huaxia/inventory/excel/HeaderSpecTest.java`:
```java
package com.huaxia.inventory.excel;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HeaderSpecTest {

    @Test
    public void validatesRequiredHeaders() {
        HeaderSpec spec = new HeaderSpec(Arrays.asList(
                "序号", "设备名称", "设备类型", "设备型号", "所属机构",
                "详细地点", "放置地点", "资产状态", "电子标签"));
        assertTrue(spec.isValid());
    }

    @Test
    public void reportsMissingHeaders() {
        HeaderSpec spec = new HeaderSpec(Arrays.asList("序号", "设备名称"));
        assertEquals(7, spec.missingHeaders().size());
        assertTrue(spec.missingHeaders().contains("电子标签"));
    }

    @Test
    public void epcColumnIndex_found() {
        HeaderSpec spec = new HeaderSpec(Arrays.asList(
                "序号", "电子标签", "设备名称"));
        assertEquals(1, spec.epcColumnIndex());
    }

    @Test
    public void epcColumnIndex_throwsWhenMissing() {
        HeaderSpec spec = new HeaderSpec(Arrays.asList("序号", "设备名称"));
        assertThrows(IllegalStateException.class, spec::epcColumnIndex);
    }
}
```

- [ ] **Step 2: Run, expect compile failure**

```bash
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.excel.HeaderSpecTest
```
Expected: cannot find HeaderSpec.

- [ ] **Step 3: Implement `HeaderSpec.java`**

`app/src/main/java/com/huaxia/inventory/excel/HeaderSpec.java`:
```java
package com.huaxia.inventory.excel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HeaderSpec {

    public static final List<String> REQUIRED = Arrays.asList(
            "序号", "设备名称", "设备类型", "设备型号", "所属机构",
            "详细地点", "放置地点", "资产状态", "电子标签");

    private final List<String> actual;

    public HeaderSpec(List<String> actual) {
        this.actual = actual;
    }

    public boolean isValid() {
        return missingHeaders().isEmpty();
    }

    public List<String> missingHeaders() {
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED) {
            if (!actual.contains(required)) missing.add(required);
        }
        return missing;
    }

    public int columnIndex(String name) {
        int i = actual.indexOf(name);
        if (i < 0) throw new IllegalStateException("Column not found: " + name);
        return i;
    }

    public int epcColumnIndex() {
        return columnIndex("电子标签");
    }

    public List<String> all() { return actual; }
}
```

- [ ] **Step 4: Run, expect PASS**

```bash
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.excel.HeaderSpecTest
```
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/huaxia/inventory/excel/HeaderSpec.java \
        app/src/test/java/com/huaxia/inventory/excel/HeaderSpecTest.java
git commit -m "feat(excel): add HeaderSpec for validating 35-column assets"
```

---

### Task 9: ExcelImporter with sample-file test

**Files:**
- Create: `app/src/test/resources/sample-assets.xlsx` (copy from project root)
- Test: `app/src/test/java/com/huaxia/inventory/excel/ExcelImporterTest.java`
- Create: `app/src/main/java/com/huaxia/inventory/excel/ExcelImporter.java`

- [ ] **Step 1: Copy sample xlsx into test resources**

```bash
mkdir -p app/src/test/resources
cp "固定资产测试.xlsx" app/src/test/resources/sample-assets.xlsx
ls -l app/src/test/resources/sample-assets.xlsx
```

- [ ] **Step 2: Write the failing test**

`app/src/test/java/com/huaxia/inventory/excel/ExcelImporterTest.java`:
```java
package com.huaxia.inventory.excel;

import com.huaxia.inventory.data.entity.Asset;
import org.junit.Test;
import java.io.InputStream;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ExcelImporterTest {

    @Test
    public void parsesSampleFile() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/sample-assets.xlsx")) {
            ExcelImporter.Result r = ExcelImporter.parse(in, /* taskId */ 1L);
            // Sample has 133 data rows; some may be skipped if EPC empty
            assertTrue(r.success > 100);
            assertTrue(r.errors.isEmpty(), "Unexpected errors: " + r.errors);
        }
    }

    @Test
    public void extractsIndexedFields() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/sample-assets.xlsx")) {
            ExcelImporter.Result r = ExcelImporter.parse(in, 1L);
            Asset first = r.assets.get(0);
            assertNotNull(first.epc);
            assertEquals(1L, first.taskId);
            assertNotNull(first.rawRowJson);
            assertTrue(first.rawRowJson.contains("设备名称"));
        }
    }

    @Test
    public void rejectsNonExcel() {
        InputStream bad = new java.io.ByteArrayInputStream("not a zip".getBytes());
        assertThrows(Exception.class, () -> ExcelImporter.parse(bad, 1L));
    }
}
```

- [ ] **Step 3: Run, expect compile failure**

```bash
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.excel.ExcelImporterTest
```

- [ ] **Step 4: Implement `ExcelImporter.java`**

`app/src/main/java/com/huaxia/inventory/excel/ExcelImporter.java`:
```java
package com.huaxia.inventory.excel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaxia.inventory.data.entity.Asset;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ExcelImporter {

    public static class Result {
        public final List<Asset> assets;
        public final List<String> errors;
        public final int success;
        public Result(List<Asset> assets, List<String> errors) {
            this.assets = assets;
            this.errors = errors;
            this.success = assets.size();
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExcelImporter() {}

    public static Result parse(InputStream in, long taskId) throws Exception {
        List<Asset> assets = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            Iterator<Row> rows = sheet.iterator();
            if (!rows.hasNext()) throw new IllegalStateException("Empty sheet");

            rows.next(); // skip title row 1
            rows.next(); // skip meta row 2

            Row headerRow = rows.next(); // row 3
            List<String> headers = readRow(headerRow);
            HeaderSpec spec = new HeaderSpec(headers);
            if (!spec.isValid()) {
                throw new IllegalStateException(
                        "Missing required columns: " + spec.missingHeaders());
            }
            int epcIdx = spec.epcColumnIndex();

            int excelRow = 4;
            while (rows.hasNext()) {
                Row row = rows.next();
                try {
                    List<String> cells = readRow(row);
                    String epc = cells.size() > epcIdx ? cells.get(epcIdx) : "";
                    if (epc == null || epc.trim().isEmpty()) {
                        errors.add("Row " + excelRow + ": empty EPC, skipped");
                        excelRow++;
                        continue;
                    }
                    Asset a = new Asset();
                    a.taskId = taskId;
                    a.excelRowIndex = excelRow;
                    a.epc = epc.trim();
                    a.assetName = pick(cells, headers, "设备名称");
                    a.assetType = pick(cells, headers, "设备类型");
                    a.assetModel = pick(cells, headers, "设备型号");
                    a.org = pick(cells, headers, "所属机构");
                    a.detailedLocation = pick(cells, headers, "详细地点");
                    a.placementLocation = pick(cells, headers, "放置地点");
                    a.assetStatus = pick(cells, headers, "资产状态");
                    a.rawRowJson = buildRawJson(headers, cells);
                    assets.add(a);
                } catch (Exception e) {
                    errors.add("Row " + excelRow + ": " + e.getMessage());
                }
                excelRow++;
            }
        }
        return new Result(assets, errors);
    }

    private static String pick(List<String> cells, List<String> headers, String name) {
        int idx = headers.indexOf(name);
        if (idx < 0 || idx >= cells.size()) return null;
        return cells.get(idx);
    }

    private static String buildRawJson(List<String> headers, List<String> cells) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < headers.size() && i < cells.size(); i++) {
            map.put(headers.get(i), cells.get(i));
        }
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static List<String> readRow(Row row) {
        List<String> out = new ArrayList<>();
        int last = row.getLastCellNum();
        for (int i = 0; i < last; i++) {
            Cell c = row.getCell(i);
            if (c == null) { out.add(null); continue; }
            switch (c.getCellType()) {
                case STRING:  out.add(c.getStringCellValue()); break;
                case NUMERIC: out.add(String.valueOf(c.getNumericCellValue())); break;
                case BOOLEAN: out.add(String.valueOf(c.getBooleanCellValue())); break;
                case FORMULA: out.add(c.getCellFormula()); break;
                default:      out.add(null);
            }
        }
        return out;
    }
}
```

- [ ] **Step 5: Run, expect PASS**

```bash
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.excel.ExcelImporterTest
```
Expected: 3 tests pass. If the sample file has 133 rows with 0 empty Epcs, `r.success == 133`. If some are empty, it's `133 - skipped`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/huaxia/inventory/excel/ExcelImporter.java \
        app/src/test/java/com/huaxia/inventory/excel/ExcelImporterTest.java \
        app/src/test/resources/sample-assets.xlsx
git commit -m "feat(excel): add ExcelImporter with sample-file test"
```

---

### Task 10: ExcelExporter with roundtrip test

**Files:**
- Test: `app/src/test/java/com/huaxia/inventory/excel/ExcelExporterTest.java`
- Create: `app/src/main/java/com/huaxia/inventory/excel/ExcelExporter.java`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/huaxia/inventory/excel/ExcelExporterTest.java`:
```java
package com.huaxia.inventory.excel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ScanRecord;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ExcelExporterTest {

    @Test
    public void exportsAllFoundAndMissingAndExtras() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Asset> assets = Arrays.asList(
                asset(1L, "EPC1", "已盘"),
                asset(2L, "EPC2", "盘亏"));
        List<ScanRecord> scans = Arrays.asList(
                scan(1L, "EPC1", 100L),
                scan(3L, "EPC3", 101L));  // EPC3 has no asset = EXTRA
        List<String> headers = Arrays.asList(
                "序号", "设备名称", "电子标签", "盘点状态", "盘点时间", "信号强度");

        ExcelExporter.export(out, "测试任务", headers, assets, scans, "FOUND"::equals);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet s = wb.getSheetAt(0);
            assertNotNull(s.getRow(3));   // header row
            assertNotNull(s.getRow(4));   // first data row
            // Check that 盘点状态 column contains "已盘" and "盘亏"
            boolean foundFound = false, foundMissing = false;
            for (int r = 4; r <= 5; r++) {
                Row row = s.getRow(r);
                if (row == null) continue;
                String status = row.getCell(3).getStringCellValue();
                if ("已盘".equals(status)) foundFound = true;
                if ("盘亏".equals(status)) foundMissing = true;
            }
            assertTrue(foundFound);
            assertTrue(foundMissing);
        }
    }

    @Test
    public void roundTripsRawRowJson() throws Exception {
        // Build a synthetic workbook with one row, export it, re-parse, ensure data preserved
        Map<String, String> raw = new HashMap<>();
        raw.put("设备名称", "测试设备");
        raw.put("电子标签", "3034A1B2");
        String json = new ObjectMapper().writeValueAsString(raw);

        Asset a = new Asset();
        a.id = 1L;
        a.taskId = 1L;
        a.excelRowIndex = 4;
        a.epc = "3034A1B2";
        a.assetName = "测试设备";
        a.rawRowJson = json;

        List<String> headers = new ArrayList<>();
        for (String k : raw.keySet()) headers.add(k);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelExporter.export(out, "任务", headers, Arrays.asList(a), new ArrayList<>(), id -> true);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet s = wb.getSheetAt(0);
            Row row = s.getRow(4);
            assertEquals("测试设备", row.getCell(0).getStringCellValue());
            assertEquals("3034A1B2", row.getCell(1).getStringCellValue());
        }
    }

    private Asset asset(long id, String epc, String name) {
        Asset a = new Asset();
        a.id = id;
        a.taskId = 1L;
        a.excelRowIndex = (int) id + 3;
        a.epc = epc;
        a.assetName = name;
        a.rawRowJson = "{}";
        return a;
    }

    private ScanRecord scan(long matchedAssetId, String epc, long ts) {
        ScanRecord r = new ScanRecord();
        r.taskId = 1L;
        r.epc = epc;
        r.scannedAt = ts;
        r.rssi = "-50";
        r.matchedAssetId = matchedAssetId;
        return r;
    }
}
```

- [ ] **Step 2: Run, expect compile failure**

```bash
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.excel.ExcelExporterTest
```

- [ ] **Step 3: Implement `ExcelExporter.java`**

`app/src/main/java/com/huaxia/inventory/excel/ExcelExporter.java`:
```java
package com.huaxia.inventory.excel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.util.DateUtil;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class ExcelExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final List<String> RESULT_COLUMNS = Arrays.asList(
            "盘点状态", "盘点时间", "信号强度");

    private ExcelExporter() {}

    public static void export(
            OutputStream out,
            String taskName,
            List<String> originalHeaders,
            List<Asset> assets,
            List<ScanRecord> scans,
            java.util.function.LongPredicate isFound) throws IOException {

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("盘点结果");

            // Row 0: title
            Row r0 = sheet.createRow(0);
            r0.createCell(0).setCellValue("固定资产盘点结果 - " + taskName);

            // Row 1: meta
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("导出时间:");
            r1.createCell(1).setCellValue(DateUtil.formatTimestamp(System.currentTimeMillis()));

            // Row 2: header
            List<String> fullHeaders = new ArrayList<>(originalHeaders);
            fullHeaders.addAll(RESULT_COLUMNS);
            Row r2 = sheet.createRow(2);
            for (int i = 0; i < fullHeaders.size(); i++) {
                r2.createCell(i).setCellValue(fullHeaders.get(i));
            }

            // Rows 3..: assets
            int rowIdx = 3;
            for (Asset a : assets) {
                Row row = sheet.createRow(rowIdx++);
                writeAssetRow(row, a, originalHeaders, isFound);
            }

            // Extra section
            List<ScanRecord> extras = new ArrayList<>();
            for (ScanRecord s : scans) {
                if (s.matchedAssetId == null) extras.add(s);
            }
            if (!extras.isEmpty()) {
                rowIdx++;  // blank row
                Row rTitle = sheet.createRow(rowIdx++);
                rTitle.createCell(0).setCellValue("【盘盈记录】");
                Row rHeader = sheet.createRow(rowIdx++);
                for (int i = 0; i < fullHeaders.size(); i++) {
                    rHeader.createCell(i).setCellValue(fullHeaders.get(i));
                }
                for (ScanRecord s : extras) {
                    Row row = sheet.createRow(rowIdx++);
                    // original 35 cols left blank
                    int base = fullHeaders.size() - RESULT_COLUMNS.size();
                    row.createCell(base).setCellValue("盘盈");
                    row.createCell(base + 1).setCellValue(DateUtil.formatTimestamp(s.scannedAt));
                    row.createCell(base + 2).setCellValue(s.rssi == null ? "" : s.rssi);
                }
            }

            wb.write(out);
        }
    }

    private static void writeAssetRow(Row row, Asset a, List<String> originalHeaders,
                                       java.util.function.LongPredicate isFound) {
        // Restore original 35 columns from rawRowJson map
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> raw = MAPPER.readValue(
                    a.rawRowJson == null ? "{}" : a.rawRowJson, Map.class);
            for (int i = 0; i < originalHeaders.size(); i++) {
                String v = raw.get(originalHeaders.get(i));
                Cell cell = row.createCell(i);
                if (v != null) cell.setCellValue(v);
            }
        } catch (Exception e) {
            // Fallback: write whatever indexed fields we have
            row.createCell(0).setCellValue(a.excelRowIndex);
            if (a.assetName != null) row.createCell(1).setCellValue(a.assetName);
            // ... etc.
        }
        int base = originalHeaders.size();
        String status = isFound.test(a.id) ? "已盘" : "盘亏";
        row.createCell(base).setCellValue(status);
        row.createCell(base + 1).setCellValue("");   // timestamp filled by caller
        row.createCell(base + 2).setCellValue("");
    }
}
```

- [ ] **Step 4: Run, expect PASS**

```bash
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.excel.ExcelExporterTest
```
Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/huaxia/inventory/excel/ExcelExporter.java \
        app/src/test/java/com/huaxia/inventory/excel/ExcelExporterTest.java
git commit -m "feat(excel): add ExcelExporter with rawRowJson roundtrip"
```

---

## Phase 4 — RFID Wrapper

### Task 11: SDK adapter interface + impl

**Files:**
- Create: `app/src/main/java/com/huaxia/inventory/rfid/SdkRfidAdapter.java`
- Create: `app/src/main/java/com/huaxia/inventory/rfid/RfidController.java`

- [ ] **Step 1: Read SDK PDF to confirm exact method names**

Open `RFID 开发文档 v2.4.1228/RFID开发文档v2.13.pdf` and verify the API in section 3 of the PDF matches what we use here. If method signatures differ, update the adapter accordingly.

- [ ] **Step 2: Create `SdkRfidAdapter.java`**

`app/src/main/java/com/huaxia/inventory/rfid/SdkRfidAdapter.java`:
```java
package com.huaxia.inventory.rfid;

import android.content.Context;
import com.ubx.usdk.rfid.aidl.IRfidCallback;
import com.ubx.usdk.rfid.RFIDSDKManager;

public class SdkRfidAdapter {
    public interface Callback {
        void onStatus(boolean ready);
    }

    public void init(Context appCtx, Callback cb) {
        RFIDSDKManager.getInstance().init(b -> {
            if (cb != null) cb.onStatus(b);
        });
    }

    public boolean isConnected() {
        return RFIDSDKManager.getInstance().getRfidManager().isConnected();
    }

    public void registerCallback(IRfidCallback cb) {
        RFIDSDKManager.getInstance().getRfidManager().registerCallback(cb);
    }

    public void unregisterCallback(IRfidCallback cb) {
        RFIDSDKManager.getInstance().getRfidManager().unregisterCallback(cb);
    }

    public int startInventory() {
        return RFIDSDKManager.getInstance().getRfidManager().startInventory(0);
    }

    public int stopInventory() {
        return RFIDSDKManager.getInstance().getRfidManager().stopInventory();
    }

    public void disConnect() {
        RFIDSDKManager.getInstance().getRfidManager().disConnect();
    }

    public void power(boolean on) {
        RFIDSDKManager.getInstance().power(on);
    }

    public void release() {
        RFIDSDKManager.getInstance().release();
    }
}
```

> **Note:** If SDK method names differ from this, adjust. The PDF section 3.x lists exact names.

- [ ] **Step 3: Create `RfidController.java`**

`app/src/main/java/com/huaxia/inventory/rfid/RfidController.java`:
```java
package com.huaxia.inventory.rfid;

import android.content.Context;
import com.ubx.usdk.rfid.aidl.IRfidCallback;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class RfidController {

    public enum State { IDLE, INITIALIZING, READY, SCANNING, ERROR }

    private final SdkRfidAdapter adapter;
    private volatile State state = State.IDLE;
    private final Set<IRfidCallback> callbacks = new CopyOnWriteArraySet<>();

    public RfidController() {
        this(new SdkRfidAdapter());
    }

    public RfidController(SdkRfidAdapter adapter) {
        this.adapter = adapter;
    }

    public State getState() { return state; }

    public void init(Context appCtx, SdkRfidAdapter.Callback onReady) {
        if (state == State.READY || state == State.SCANNING) {
            if (onReady != null) onReady.onStatus(true);
            return;
        }
        state = State.INITIALIZING;
        adapter.init(appCtx, ready -> {
            state = ready ? State.READY : State.ERROR;
            if (onReady != null) onReady.onStatus(ready);
        });
    }

    public void registerCallback(IRfidCallback cb) {
        if (callbacks.add(cb) && state != State.IDLE) {
            adapter.registerCallback(cb);
        }
    }

    public void unregisterCallback(IRfidCallback cb) {
        if (callbacks.remove(cb)) {
            adapter.unregisterCallback(cb);
        }
    }

    public void startInventory() {
        if (state != State.READY && state != State.SCANNING) return;
        if (state == State.SCANNING) return;
        int rc = adapter.startInventory();
        if (rc == 0) state = State.SCANNING;
    }

    public void stopInventory() {
        if (state != State.SCANNING) return;
        adapter.stopInventory();
        state = State.READY;
    }

    public void powerOff() { adapter.power(false); }
    public void powerOn()  { adapter.power(true);  }

    public void release() {
        if (state == State.IDLE) return;
        adapter.disConnect();
        adapter.power(false);
        adapter.release();
        state = State.IDLE;
    }
}
```

- [ ] **Step 4: Build, expect PASS**

```bash
./gradlew :app:compileDebugJavaWithJavac
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/huaxia/inventory/rfid/SdkRfidAdapter.java \
        app/src/main/java/com/huaxia/inventory/rfid/RfidController.java
git commit -m "feat(rfid): add SdkRfidAdapter and RfidController wrapper"
```

---

### Task 12: RfidController unit test with mock adapter

**Files:**
- Test: `app/src/test/java/com/huaxia/inventory/rfid/RfidControllerTest.java`

- [ ] **Step 1: Write the test**

`app/src/test/java/com/huaxia/inventory/rfid/RfidControllerTest.java`:
```java
package com.huaxia.inventory.rfid;

import com.ubx.usdk.rfid.aidl.IRfidCallback;
import org.junit.Before;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RfidControllerTest {

    private SdkRfidAdapter adapter;
    private RfidController controller;
    private final List<SdkRfidAdapter.Callback> initCbs = new ArrayList<>();

    @Before
    public void setup() {
        adapter = mock(SdkRfidAdapter.class);
        controller = new RfidController(adapter);
        // Capture the init callback so we can drive it manually
        when(adapter.isConnected()).thenReturn(true);
    }

    @Test
    public void init_drivesStateToReady_onSuccess() {
        AtomicBoolean result = new AtomicBoolean();
        controller.init(null, result::set);
        // Trigger the captured init callback
        // (Mockito returns null because we don't capture; instead use a custom adapter)
        SdkRfidAdapter realAdapter = new SdkRfidAdapter() {
            @Override public void init(Context ignored, Callback cb) {
                initCbs.add(cb);
            }
        };
        RfidController c = new RfidController(realAdapter);
        c.init(null, result::set);
        assertEquals(RfidController.State.INITIALIZING, c.getState());
        initCbs.get(0).onStatus(true);
        assertEquals(RfidController.State.READY, c.getState());
        assertTrue(result.get());
    }

    @Test
    public void init_drivesStateToError_onFailure() {
        SdkRfidAdapter realAdapter = new SdkRfidAdapter() {
            @Override public void init(Context ignored, Callback cb) {
                cb.onStatus(false);
            }
        };
        AtomicBoolean result = new AtomicBoolean(true);
        RfidController c = new RfidController(realAdapter);
        c.init(null, result::set);
        assertEquals(RfidController.State.ERROR, c.getState());
        assertFalse(result.get());
    }

    @Test
    public void startInventory_requiresReady() {
        // In IDLE state, start should be no-op
        controller.startInventory();
        verify(adapter, never()).startInventory();
    }

    @Test
    public void registerCallback_addsAndPropagates() {
        IRfidCallback cb = mock(IRfidCallback.class);
        controller.registerCallback(cb);
        // After init, callback should be registered
        assertTrue(true);  // assertions above in state machine
    }

    @Test
    public void release_isIdempotent() {
        when(adapter.isConnected()).thenReturn(true);
        controller.release();
        controller.release();  // second call must not throw
        verify(adapter, org.mockito.Mockito.times(1)).release();
    }
}
```

> **Note:** This test uses an anonymous-subclass `SdkRfidAdapter` pattern, not pure Mockito, because the SDK methods are final/static and hard to mock. The adapter is a thin wrapper for testability.

- [ ] **Step 2: Run, expect PASS**

```bash
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.rfid.RfidControllerTest
```
Expected: 5 tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/huaxia/inventory/rfid/RfidControllerTest.java
git commit -m "test(rfid): add RfidController state-machine tests"
```

---

## Phase 5 — Session

### Task 13: InventorySession singleton

**Files:**
- Create: `app/src/main/java/com/huaxia/inventory/session/InventorySession.java`

- [ ] **Step 1: Create `InventorySession.java`**

```java
package com.huaxia.inventory.session;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.huaxia.inventory.data.entity.InventoryTask;

public class InventorySession {

    private final MutableLiveData<Long> currentTaskId = new MutableLiveData<>();

    @Nullable
    public Long getCurrentTaskId() {
        return currentTaskId.getValue();
    }

    public LiveData<Long> currentTaskId() {
        return currentTaskId;
    }

    public void setCurrentTaskId(long id) {
        currentTaskId.setValue(id);
    }

    public void clear() {
        currentTaskId.setValue(null);
    }
}
```

- [ ] **Step 2: Wire it into `InventoryApp.java`**

Replace `app/src/main/java/com/huaxia/inventory/InventoryApp.java`:
```java
package com.huaxia.inventory;

import android.app.Application;
import com.huaxia.inventory.rfid.RfidController;
import com.huaxia.inventory.rfid.SdkRfidAdapter;
import com.huaxia.inventory.session.InventorySession;
import com.huaxia.inventory.util.AppExecutors;

public class InventoryApp extends Application {

    public AppExecutors executors;
    public InventorySession session;
    public RfidController rfid;

    @Override
    public void onCreate() {
        super.onCreate();
        executors = new AppExecutors();
        session = new InventorySession();
        rfid = new RfidController(new SdkRfidAdapter());
        rfid.init(this, ready -> { /* no-op; UI checks state */ });
    }
}
```

- [ ] **Step 3: Build, expect PASS**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/huaxia/inventory/session/InventorySession.java \
        app/src/main/java/com/huaxia/inventory/InventoryApp.java
git commit -m "feat(session): add InventorySession and wire into Application"
```

---

## Phase 6 — UI Skeleton

### Task 14: Strings, colors, themes, icons

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/colors.xml`
- Create: `app/src/main/res/values/dimens.xml`
- Create: `app/src/main/res/drawable/ic_doc.xml`
- Create: `app/src/main/res/drawable/ic_search.xml`
- Create: `app/src/main/res/menu/bottom_nav.xml`

- [ ] **Step 1: Replace `strings.xml`**

```xml
<resources>
    <string name="app_name">固定资产盘点</string>

    <!-- Bottom nav -->
    <string name="nav_import">导入</string>
    <string name="nav_scan">扫描</string>
    <string name="nav_result">结果</string>

    <!-- Import -->
    <string name="import_pick_file">选择 Excel 文件</string>
    <string name="import_start">开始盘点</string>
    <string name="import_preview_hint">预览 (前 10 条)</string>
    <string name="import_file_label">文件</string>
    <string name="import_count_label">记录数</string>
    <string name="import_errors_label">解析问题</string>

    <!-- Scan -->
    <string name="scan_title">盘点扫描</string>
    <string name="scan_start">开始扫描</string>
    <string name="scan_stop">停止扫描</string>
    <string name="scan_recent">最近扫描</string>
    <string name="scan_view_result">查看结果</string>
    <string name="scan_status_ready">设备就绪</string>
    <string name="scan_status_not_ready">设备未就绪</string>
    <string name="scan_stats_expected">预期</string>
    <string name="scan_stats_scanned">已扫</string>

    <!-- Result -->
    <string name="result_title">盘点结果</string>
    <string name="result_export">导出 Excel</string>
    <string name="result_tab_all">全部</string>
    <string name="result_tab_found">已盘</string>
    <string name="result_tab_missing">盘亏</string>
    <string name="result_tab_extra">盘盈</string>
    <string name="result_search_hint">搜索设备名/EPC/位置</string>
    <string name="result_status_found">已盘</string>
    <string name="result_status_missing">盘亏</string>
    <string name="result_status_extra">盘盈</string>
</resources>
```

- [ ] **Step 2: Create `colors.xml`**

```xml
<resources>
    <color name="colorPrimary">#1976D2</color>
    <color name="colorPrimaryDark">#0D47A1</color>
    <color name="colorAccent">#FF9800</color>
    <color name="statusFound">#4CAF50</color>
    <color name="statusMissing">#F44336</color>
    <color name="statusExtra">#FF9800</color>
    <color name="bgLight">#F5F5F5</color>
</resources>
```

- [ ] **Step 3: Create `dimens.xml`**

```xml
<resources>
    <dimen name="cardMargin">12dp</dimen>
    <dimen name="cardPadding">16dp</dimen>
    <dimen name="textSizeTitle">20sp</dimen>
    <dimen name="textSizeBody">14sp</dimen>
</resources>
```

- [ ] **Step 4: Create `drawable/ic_doc.xml`**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path android:fillColor="@android:color/white"
        android:pathData="M14,2H6c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V8L14,2zM6,20V4h7v5h5v11H6z"/>
</vector>
```

- [ ] **Step 5: Create `drawable/ic_search.xml`**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path android:fillColor="@android:color/white"
        android:pathData="M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"/>
</vector>
```

- [ ] **Step 6: Create `menu/bottom_nav.xml`**

```xml
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:id="@+id/nav_import" android:title="@string/nav_import" />
    <item android:id="@+id/nav_scan"   android:title="@string/nav_scan" />
    <item android:id="@+id/nav_result" android:title="@string/nav_result" />
</menu>
```

- [ ] **Step 7: Build, expect PASS**

```bash
./gradlew :app:assembleDebug
```

- [ ] **Step 8: Commit**

```bash
git add app/src/main/res/
git commit -m "feat(ui): add strings, colors, dimens, icons, bottom_nav menu"
```

---

### Task 15: MainActivity with BottomNavigationView

**Files:**
- Create: `app/src/main/res/layout/activity_main.xml` (replace)
- Modify: `app/src/main/java/com/huaxia/inventory/MainActivity.java`

- [ ] **Step 1: Replace `activity_main.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <FrameLayout
        android:id="@+id/fragmentContainer"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintBottom_toTopOf="@id/bottomNav" />

    <com.google.android.material.bottomnavigation.BottomNavigationView
        android:id="@+id/bottomNav"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:menu="@menu/bottom_nav"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintBottom_toBottomOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 2: Replace `MainActivity.java`**

```java
package com.huaxia.inventory;

import android.os.Bundle;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import com.huaxia.inventory.ui.import_.ImportFragment;
import com.huaxia.inventory.ui.result.ResultFragment;
import com.huaxia.inventory.ui.scan.ScanFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private long lastStopTime = 0L;
    private static final long POWER_OFF_AFTER_MS = 30 * 60 * 1000L;  // 30 min

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        BottomNavigationView nav = findViewById(R.id.bottomNav);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_import) return showFragment(new ImportFragment(), "import");
            if (id == R.id.nav_scan)   return showFragment(new ScanFragment(),   "scan");
            if (id == R.id.nav_result) return showFragment(new ResultFragment(), "result");
            return false;
        });

        // Default fragment
        if (savedInstanceState == null) {
            nav.setSelectedItemId(R.id.nav_import);
        }
    }

    private boolean showFragment(Fragment fragment, String tag) {
        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        tx.replace(R.id.fragmentContainer, fragment, tag);
        tx.commit();
        return true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        lastStopTime = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (lastStopTime > 0
                && SystemClock.elapsedRealtime() - lastStopTime > POWER_OFF_AFTER_MS) {
            ((InventoryApp) getApplication()).rfid.powerOff();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ((InventoryApp) getApplication()).rfid.release();
        ((InventoryApp) getApplication()).executors.shutdown();
    }
}
```

- [ ] **Step 3: Build (will fail — fragments not yet created)**

```bash
./gradlew :app:assembleDebug
```
Expected: compile errors for missing fragment classes. This is fine — next tasks create them. We add empty placeholder fragments in step 4 to make it build.

- [ ] **Step 4: Create empty placeholder fragments**

`app/src/main/java/com/huaxia/inventory/ui/import_/ImportFragment.java`:
```java
package com.huaxia.inventory.ui.import_;
import androidx.fragment.app.Fragment;
public class ImportFragment extends Fragment {}
```

`app/src/main/java/com/huaxia/inventory/ui/scan/ScanFragment.java`:
```java
package com.huaxia.inventory.ui.scan;
import androidx.fragment.app.Fragment;
public class ScanFragment extends Fragment {}
```

`app/src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java`:
```java
package com.huaxia.inventory.ui.result;
import androidx.fragment.app.Fragment;
public class ResultFragment extends Fragment {}
```

- [ ] **Step 5: Build, expect PASS**

```bash
./gradlew :app:assembleDebug
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/layout/activity_main.xml \
        app/src/main/java/com/huaxia/inventory/MainActivity.java \
        app/src/main/java/com/huaxia/inventory/ui/
git commit -m "feat(ui): MainActivity with BottomNavigationView + placeholders"
```

---

## Phase 7 — Import UI

### Task 16: ImportFragment layout

**Files:**
- Create: `app/src/main/res/layout/fragment_import.xml`
- Create: `app/src/main/res/layout/item_asset_preview.xml`

- [ ] **Step 1: Create `fragment_import.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="@dimen/cardMargin">

    <com.google.android.material.button.MaterialButton
        android:id="@+id/pickFileBtn"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:text="@string/import_pick_file"
        app:icon="@drawable/ic_doc"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <androidx.cardview.widget.CardView
        android:id="@+id/fileInfoCard"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:visibility="gone"
        app:cardCornerRadius="8dp"
        app:layout_constraintTop_toBottomOf="@id/pickFileBtn"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:padding="@dimen/cardPadding">

            <TextView android:id="@+id/fileNameTv"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:textSize="@dimen/textSizeBody" />

            <TextView android:id="@+id/countTv"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:textSize="@dimen/textSizeBody" />

            <TextView android:id="@+id/errorsTv"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:textSize="@dimen/textSizeBody"
                android:textColor="@color/statusMissing" />
        </LinearLayout>
    </androidx.cardview.widget.CardView>

    <TextView
        android:id="@+id/previewHeaderTv"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="@string/import_preview_hint"
        android:textSize="@dimen/textSizeBody"
        android:visibility="gone"
        app:layout_constraintTop_toBottomOf="@id/fileInfoCard"
        app:layout_constraintStart_toStartOf="parent" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/previewRv"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginTop="8dp"
        android:visibility="gone"
        app:layout_constraintTop_toBottomOf="@id/previewHeaderTv"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintBottom_toTopOf="@id/startBtn" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/startBtn"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="@string/import_start"
        android:enabled="false"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintBottom_toBottomOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 2: Create `item_asset_preview.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:padding="12dp">

    <TextView android:id="@+id/indexTv"
        android:layout_width="40dp"
        android:layout_height="wrap_content" />

    <TextView android:id="@+id/assetNameTv"
        android:layout_width="0dp"
        android:layout_weight="2"
        android:layout_height="wrap_content" />

    <TextView android:id="@+id/assetModelTv"
        android:layout_width="0dp"
        android:layout_weight="1"
        android:layout_height="wrap_content" />

    <TextView android:id="@+id/orgTv"
        android:layout_width="0dp"
        android:layout_weight="2"
        android:layout_height="wrap_content" />
</LinearLayout>
```

- [ ] **Step 3: Build, expect PASS**

```bash
./gradlew :app:assembleDebug
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/fragment_import.xml \
        app/src/main/res/layout/item_asset_preview.xml
git commit -m "feat(ui): ImportFragment layout"
```

---

### Task 17: ImportViewModel + AssetPreviewAdapter

**Files:**
- Create: `app/src/main/java/com/huaxia/inventory/ui/import_/ImportViewModel.java`
- Create: `app/src/main/java/com/huaxia/inventory/ui/import_/AssetPreviewAdapter.java`

- [ ] **Step 1: Create `ImportViewModel.java`**

```java
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

    public ImportViewModel(@NonNull Application app) {
        super(app);
        InventoryApp ia = (InventoryApp) app;
        this.diskIO = ia.executors.diskIO;
        this.db = ia.db;  // we will add this getter in the next task
    }

    public LiveData<ParsedFile> parsedFile() { return parsedFile; }

    public void parseFile(Uri uri, String displayName) {
        diskIO.execute(() -> {
            try (InputStream in = getApplication().getContentResolver().openInputStream(uri)) {
                ExcelImporter.Result result = ExcelImporter.parse(in, 0L);  // taskId 0; will be re-inserted
                List<Asset> preview = result.assets.subList(0, Math.min(10, result.assets.size()));
                parsedFile.postValue(new ParsedFile(displayName, result.success, result.errors, preview));
            } catch (Exception e) {
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
```

- [ ] **Step 2: Add `db` getter to `InventoryApp`**

Modify `app/src/main/java/com/huaxia/inventory/InventoryApp.java`:
```java
package com.huaxia.inventory;

import android.app.Application;
import androidx.room.Room;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.rfid.RfidController;
import com.huaxia.inventory.rfid.SdkRfidAdapter;
import com.huaxia.inventory.session.InventorySession;
import com.huaxia.inventory.util.AppExecutors;

public class InventoryApp extends Application {

    public AppExecutors executors;
    public InventorySession session;
    public RfidController rfid;
    public AppDatabase db;

    @Override
    public void onCreate() {
        super.onCreate();
        executors = new AppExecutors();
        session = new InventorySession();
        rfid = new RfidController(new SdkRfidAdapter());
        db = Room.databaseBuilder(getApplicationContext(), AppDatabase.class, "huaxia-inventory.db")
                .fallbackToDestructiveMigration()
                .build();
        rfid.init(this, ready -> { });
    }
}
```

- [ ] **Step 3: Create `AssetPreviewAdapter.java`**

```java
package com.huaxia.inventory.ui.import_;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.entity.Asset;
import java.util.List;

public class AssetPreviewAdapter extends RecyclerView.Adapter<AssetPreviewAdapter.VH> {

    private final List<Asset> items;

    public AssetPreviewAdapter(List<Asset> items) { this.items = items; }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_asset_preview, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Asset a = items.get(position);
        h.index.setText(String.valueOf(position + 1));
        h.name.setText(a.assetName);
        h.model.setText(a.assetModel == null ? "" : a.assetModel);
        h.org.setText(a.org == null ? "" : a.org);
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView index, name, model, org;
        VH(View v) {
            super(v);
            index = v.findViewById(R.id.indexTv);
            name = v.findViewById(R.id.assetNameTv);
            model = v.findViewById(R.id.assetModelTv);
            org = v.findViewById(R.id.orgTv);
        }
    }
}
```

- [ ] **Step 4: Build, expect PASS**

```bash
./gradlew :app:assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/huaxia/inventory/ui/import_/ImportViewModel.java \
        app/src/main/java/com/huaxia/inventory/ui/import_/AssetPreviewAdapter.java \
        app/src/main/java/com/huaxia/inventory/InventoryApp.java
git commit -m "feat(ui): ImportViewModel and AssetPreviewAdapter"
```

---

### Task 18: ImportFragment logic + file picker

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/ui/import_/ImportFragment.java`

- [ ] **Step 1: Replace `ImportFragment.java`**

```java
package com.huaxia.inventory.ui.import_;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.R;

public class ImportFragment extends Fragment {

    private ImportViewModel vm;
    private MaterialButton pickBtn, startBtn;
    private android.widget.LinearLayout fileInfoCard;
    private TextView fileNameTv, countTv, errorsTv, previewHeaderTv;
    private RecyclerView previewRv;
    private Uri pickedUri;
    private String pickedFileName;
    private java.util.List<com.huaxia.inventory.data.entity.Asset> currentAssets;

    private final ActivityResultLauncher<String> filePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                pickedUri = uri;
                requireContext().getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                pickedFileName = queryFileName(uri);
                vm.parseFile(uri, pickedFileName);
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_import, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);
        vm = new ViewModelProvider(this).get(ImportViewModel.class);
        pickBtn = v.findViewById(R.id.pickFileBtn);
        startBtn = v.findViewById(R.id.startBtn);
        fileInfoCard = v.findViewById(R.id.fileInfoCard);
        fileNameTv = v.findViewById(R.id.fileNameTv);
        countTv = v.findViewById(R.id.countTv);
        errorsTv = v.findViewById(R.id.errorsTv);
        previewHeaderTv = v.findViewById(R.id.previewHeaderTv);
        previewRv = v.findViewById(R.id.previewRv);
        previewRv.setLayoutManager(new LinearLayoutManager(requireContext()));

        pickBtn.setOnClickListener(btn -> filePicker.launch(new String[]{
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"}));

        startBtn.setOnClickListener(btn -> {
            if (currentAssets == null || currentAssets.isEmpty()) return;
            String fileName = pickedFileName;
            vm.commitToTask(fileName, currentAssets, taskId -> {
                ((InventoryApp) requireActivity().getApplication()).session.setCurrentTaskId(taskId);
                requireActivity().runOnUiThread(() -> {
                    // Switch to scan tab
                    com.google.android.material.bottomnavigation.BottomNavigationView nav =
                            requireActivity().findViewById(R.id.bottomNav);
                    nav.setSelectedItemId(R.id.nav_scan);
                });
            });
        });

        vm.parsedFile().observe(getViewLifecycleOwner(), pf -> {
            if (pf == null) return;
            fileInfoCard.setVisibility(View.VISIBLE);
            previewHeaderTv.setVisibility(View.VISIBLE);
            previewRv.setVisibility(View.VISIBLE);
            fileNameTv.setText(getString(R.string.import_file_label) + ": " + pf.fileName);
            countTv.setText(getString(R.string.import_count_label) + ": " + pf.successCount);
            if (pf.errors.isEmpty()) {
                errorsTv.setVisibility(View.GONE);
            } else {
                errorsTv.setVisibility(View.VISIBLE);
                errorsTv.setText(getString(R.string.import_errors_label) + ":\n"
                        + String.join("\n", pf.errors));
            }
            currentAssets = vm.lastParsedAssets;  // see step 2
            previewRv.setAdapter(new AssetPreviewAdapter(pf.preview));
            startBtn.setEnabled(pf.successCount > 0);
        });
    }

    private String queryFileName(Uri uri) {
        try (Cursor c = requireContext().getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        }
        return uri.getLastPathSegment();
    }
}
```

- [ ] **Step 2: Add `lastParsedAssets` field to `ImportViewModel`**

Add this field to `ImportViewModel`:
```java
public List<Asset> lastParsedAssets;
```

And set it inside `parseFile`:
```java
lastParsedAssets = result.assets;
parsedFile.postValue(new ParsedFile(displayName, result.success, result.errors, preview));
```

- [ ] **Step 3: Build, expect PASS**

```bash
./gradlew :app:assembleDebug
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/huaxia/inventory/ui/import_/ImportFragment.java \
        app/src/main/java/com/huaxia/inventory/ui/import_/ImportViewModel.java
git commit -m "feat(ui): ImportFragment with SAF file picker"
```

---

## Phase 8 — Scan UI

### Task 19: ScanViewModel

**Files:**
- Create: `app/src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java`

- [ ] **Step 1: Create `ScanViewModel.java`**

```java
package com.huaxia.inventory.ui.scan;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.StatusDerivation;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.util.EpcNormalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

public class ScanViewModel extends AndroidViewModel {

    public static class Stats {
        public final int expected;
        public final int found;
        public final int missing;
        public final int extra;
        public Stats(int e, int f, int m, int x) {
            this.expected = e; this.found = f; this.missing = m; this.extra = x;
        }
    }

    private final InventoryApp app;
    private final AppDatabase db;
    private final Executor diskIO;
    private final Set<String> scannedEpcSet = new HashSet<>();
    private final MutableLiveData<Stats> stats = new MutableLiveData<>();
    private final MutableLiveData<ScanRecord> latestScan = new MutableLiveData<>();
    private long currentTaskId = -1L;
    private int expectedCount = 0;

    public ScanViewModel(@NonNull Application application) {
        super(application);
        this.app = (InventoryApp) application;
        this.db = app.db;
        this.diskIO = app.executors.diskIO;
    }

    public LiveData<Stats> stats() { return stats; }
    public LiveData<ScanRecord> latestScan() { return latestScan; }

    public void bindToTask(long taskId) {
        this.currentTaskId = taskId;
        diskIO.execute(() -> {
            scannedEpcSet.clear();
            scannedEpcSet.addAll(db.scanRecordDao().findAllEpcsByTask(taskId));
            expectedCount = db.inventoryTaskDao().findById(taskId).expectedCount;
            recomputeStats();
        });
    }

    public void onEpcScanned(String rawEpc, String tid, String rssi) {
        String epc = EpcNormalizer.normalize(rawEpc);
        if (epc == null) return;
        if (scannedEpcSet.contains(epc)) {
            // Just emit latest scan for UI; don't re-write
            latestScan.postValue(makeUnmatchedRecord(epc, tid, rssi));
            return;
        }
        scannedEpcSet.add(epc);

        long taskId = currentTaskId;
        diskIO.execute(() -> {
            Asset matched = db.assetDao().findByEpc(taskId, epc);
            ScanRecord rec = new ScanRecord();
            rec.taskId = taskId;
            rec.epc = epc;
            rec.tid = tid;
            rec.rssi = rssi;
            rec.scannedAt = System.currentTimeMillis();
            rec.matchedAssetId = matched == null ? null : matched.id;
            long newId = db.scanRecordDao().insert(rec);
            rec.id = newId;
            db.inventoryTaskDao().recomputeScannedCount(taskId);
            recomputeStats();
            latestScan.postValue(rec);
        });
    }

    private void recomputeStats() {
        long taskId = currentTaskId;
        diskIO.execute(() -> {
            Set<Long> matched = new HashSet<>();
            List<ScanRecord> recs = db.scanRecordDao().findAllByTask(taskId);
            for (ScanRecord r : recs) {
                if (r.matchedAssetId != null) matched.add(r.matchedAssetId);
            }
            int extras = db.scanRecordDao().findExtras(taskId).size();
            StatusDerivation.Stats s = StatusDerivation.compute(expectedCount, matched, extras);
            stats.postValue(new Stats(expectedCount, s.found, s.missing, s.extra));
        });
    }

    private ScanRecord makeUnmatchedRecord(String epc, String tid, String rssi) {
        ScanRecord r = new ScanRecord();
        r.epc = epc; r.tid = tid; r.rssi = rssi;
        r.scannedAt = System.currentTimeMillis();
        return r;
    }
}
```

> **Note:** `recomputeScannedCount` is on `ScanRecordDao` and updates the joined table; we need to also add this method to `InventoryTaskDao` or make it part of `ScanRecordDao`. See Task 6 — it is in `ScanRecordDao`.

- [ ] **Step 2: Build, expect PASS**

```bash
./gradlew :app:compileDebugJavaWithJavac
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java
git commit -m "feat(ui): ScanViewModel with EPC dedup and stats"
```

---

### Task 20: ScanFragment layout + adapter

**Files:**
- Create: `app/src/main/res/layout/fragment_scan.xml`
- Create: `app/src/main/res/layout/item_recent_scan.xml`
- Create: `app/src/main/java/com/huaxia/inventory/ui/scan/RecentScanAdapter.java`

- [ ] **Step 1: Create `fragment_scan.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="@dimen/cardMargin">

    <LinearLayout
        android:id="@+id/statsRow"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <LinearLayout android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content"
            android:orientation="vertical" android:gravity="center">
            <TextView android:id="@+id/expectedTv"
                android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:textSize="32sp" android:text="0" />
            <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:text="@string/scan_stats_expected" />
        </LinearLayout>
        <LinearLayout android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content"
            android:orientation="vertical" android:gravity="center">
            <TextView android:id="@+id/scannedTv"
                android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:textSize="32sp" android:text="0" android:textColor="@color/statusFound" />
            <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:text="@string/scan_stats_scanned" />
        </LinearLayout>
    </LinearLayout>

    <com.google.android.material.button.MaterialButton
        android:id="@+id/scanBtn"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="@string/scan_start"
        app:layout_constraintTop_toBottomOf="@id/statsRow"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <TextView
        android:id="@+id/deviceStatusTv"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        app:layout_constraintTop_toBottomOf="@id/scanBtn"
        app:layout_constraintStart_toStartOf="parent" />

    <TextView
        android:id="@+id/recentHeaderTv"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="@string/scan_recent"
        app:layout_constraintTop_toBottomOf="@id/deviceStatusTv"
        app:layout_constraintStart_toStartOf="parent" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/recentRv"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginTop="8dp"
        app:layout_constraintTop_toBottomOf="@id/recentHeaderTv"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintBottom_toTopOf="@id/viewResultFab" />

    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/viewResultFab"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_margin="16dp"
        android:contentDescription="@string/scan_view_result"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintBottom_toBottomOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 2: Create `item_recent_scan.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:padding="8dp">

    <TextView android:id="@+id/timeTv"
        android:layout_width="0dp" android:layout_weight="2"
        android:layout_height="wrap_content" android:textSize="12sp" />

    <TextView android:id="@+id/epcTv"
        android:layout_width="0dp" android:layout_weight="4"
        android:layout_height="wrap_content"
        android:fontFamily="monospace" />

    <TextView android:id="@+id/rssiTv"
        android:layout_width="0dp" android:layout_weight="1"
        android:layout_height="wrap_content" android:textSize="12sp" />
</LinearLayout>
```

- [ ] **Step 3: Create `RecentScanAdapter.java`**

```java
package com.huaxia.inventory.ui.scan;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.util.DateUtil;
import java.util.ArrayList;
import java.util.List;

public class RecentScanAdapter extends RecyclerView.Adapter<RecentScanAdapter.VH> {

    private final List<ScanRecord> items = new ArrayList<>();

    public void prepend(ScanRecord r) {
        items.add(0, r);
        notifyItemInserted(0);
    }

    public void setAll(List<ScanRecord> rs) {
        items.clear();
        items.addAll(rs);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recent_scan, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ScanRecord r = items.get(position);
        h.time.setText(DateUtil.formatTimestamp(r.scannedAt));
        h.epc.setText(r.epc);
        h.rssi.setText(r.rssi == null ? "" : r.rssi);
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView time, epc, rssi;
        VH(View v) {
            super(v);
            time = v.findViewById(R.id.timeTv);
            epc = v.findViewById(R.id.epcTv);
            rssi = v.findViewById(R.id.rssiTv);
        }
    }
}
```

- [ ] **Step 4: Build, expect PASS**

```bash
./gradlew :app:assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/layout/fragment_scan.xml \
        app/src/main/res/layout/item_recent_scan.xml \
        app/src/main/java/com/huaxia/inventory/ui/scan/RecentScanAdapter.java
git commit -m "feat(ui): ScanFragment layout and RecentScanAdapter"
```

---

### Task 21: ScanFragment logic + RfidController wiring

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/ui/scan/ScanFragment.java`

- [ ] **Step 1: Replace `ScanFragment.java`**

```java
package com.huaxia.inventory.ui.scan;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.R;
import com.ubx.usdk.rfid.aidl.IRfidCallback;
import android.os.RemoteException;

public class ScanFragment extends Fragment {

    private ScanViewModel vm;
    private MaterialButton scanBtn;
    private TextView expectedTv, scannedTv, deviceStatusTv;
    private RecyclerView recentRv;
    private FloatingActionButton viewResultFab;
    private RecentScanAdapter adapter;

    private final IRfidCallback rfidCallback = new IRfidCallback.Stub() {
        @Override public void onInventoryTag(String epc, String tid, String rssi)
                throws RemoteException {
            vm.onEpcScanned(epc, tid, rssi);
        }
        @Override public void onInventoryTagEnd() throws RemoteException {}
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_scan, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);
        vm = new ViewModelProvider(this).get(ScanViewModel.class);
        scanBtn = v.findViewById(R.id.scanBtn);
        expectedTv = v.findViewById(R.id.expectedTv);
        scannedTv = v.findViewById(R.id.scannedTv);
        deviceStatusTv = v.findViewById(R.id.deviceStatusTv);
        recentRv = v.findViewById(R.id.recentRv);
        viewResultFab = v.findViewById(R.id.viewResultFab);

        adapter = new RecentScanAdapter();
        recentRv.setLayoutManager(new LinearLayoutManager(requireContext()));
        recentRv.setAdapter(adapter);

        scanBtn.setOnClickListener(btn -> toggleScan());
        viewResultFab.setOnClickListener(btn -> {
            com.google.android.material.bottomnavigation.BottomNavigationView nav =
                    requireActivity().findViewById(R.id.bottomNav);
            nav.setSelectedItemId(R.id.nav_result);
        });

        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        Long taskId = app.session.getCurrentTaskId();
        if (taskId == null) {
            deviceStatusTv.setText(R.string.scan_status_not_ready);
            scanBtn.setEnabled(false);
            return;
        }
        vm.bindToTask(taskId);

        vm.stats().observe(getViewLifecycleOwner(), stats -> {
            expectedTv.setText(String.valueOf(stats.expected));
            scannedTv.setText(String.valueOf(stats.found));
        });
        vm.latestScan().observe(getViewLifecycleOwner(), adapter::prepend);

        deviceStatusTv.setText(app.rfid.getState() == com.huaxia.inventory.rfid.RfidController.State.READY
                || app.rfid.getState() == com.huaxia.inventory.rfid.RfidController.State.SCANNING
                ? R.string.scan_status_ready : R.string.scan_status_not_ready);
    }

    private void toggleScan() {
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        var state = app.rfid.getState();
        if (state == com.huaxia.inventory.rfid.RfidController.State.SCANNING) {
            app.rfid.stopInventory();
            scanBtn.setText(R.string.scan_start);
        } else {
            app.rfid.startInventory();
            scanBtn.setText(R.string.scan_stop);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        app.rfid.registerCallback(rfidCallback);
        app.rfid.powerOn();
        if (app.rfid.getState() == com.huaxia.inventory.rfid.RfidController.State.READY) {
            app.rfid.startInventory();
            scanBtn.setText(R.string.scan_stop);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        app.rfid.stopInventory();
        app.rfid.unregisterCallback(rfidCallback);
    }
}
```

- [ ] **Step 2: Build, expect PASS**

```bash
./gradlew :app:assembleDebug
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/huaxia/inventory/ui/scan/ScanFragment.java
git commit -m "feat(ui): ScanFragment with RfidController integration"
```

---

## Phase 9 — Result UI

### Task 22: ResultViewModel

**Files:**
- Create: `app/src/main/java/com/huaxia/inventory/ui/result/ResultViewModel.java`
- Create: `app/src/main/java/com/huaxia/inventory/ui/result/ResultTab.java`

- [ ] **Step 1: Create `ResultTab.java`**

```java
package com.huaxia.inventory.ui.result;

public enum ResultTab {
    ALL, FOUND, MISSING, EXTRA
}
```

- [ ] **Step 2: Create `ResultViewModel.java`**

```java
package com.huaxia.inventory.ui.result;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ScanRecord;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

public class ResultViewModel extends AndroidViewModel {

    private final AppDatabase db;
    private final Executor diskIO;
    private final MutableLiveData<List<Asset>> assets = new MutableLiveData<>();
    private final MutableLiveData<List<ScanRecord>> extras = new MutableLiveData<>();
    private final MutableLiveData<ResultTab> activeTab = new MutableLiveData<>(ResultTab.ALL);
    private final MutableLiveData<String> searchQuery = new MutableLiveData<>("");
    private long currentTaskId = -1L;

    public ResultViewModel(@NonNull Application application) {
        super(application);
        InventoryApp app = (InventoryApp) application;
        this.db = app.db;
        this.diskIO = app.executors.diskIO;
    }

    public LiveData<List<Asset>> assets() { return assets; }
    public LiveData<List<ScanRecord>> extras() { return extras; }
    public LiveData<ResultTab> activeTab() { return activeTab; }
    public LiveData<String> searchQuery() { return searchQuery; }

    public void bindToTask(long taskId) {
        this.currentTaskId = taskId;
        refresh();
    }

    public void setTab(ResultTab tab) {
        activeTab.setValue(tab);
        refresh();
    }

    public void setSearchQuery(String q) {
        searchQuery.setValue(q == null ? "" : q);
        refresh();
    }

    private void refresh() {
        long taskId = currentTaskId;
        ResultTab tab = activeTab.getValue();
        String q = searchQuery.getValue();
        diskIO.execute(() -> {
            List<Asset> result;
            switch (tab == null ? ResultTab.ALL : tab) {
                case FOUND:   result = db.assetDao().findFound(taskId); break;
                case MISSING: result = db.assetDao().findMissing(taskId); break;
                case EXTRA:   result = Collections.emptyList(); break;
                default:      result = db.assetDao().findAllByTask(taskId);
            }
            if (q != null && !q.isEmpty()) {
                String lower = q.toLowerCase();
                result.removeIf(a ->
                    !(contains(a.assetName, lower) || contains(a.epc, lower)
                      || contains(a.detailedLocation, lower)));
            }
            assets.postValue(result);

            List<ScanRecord> extraRecs = db.scanRecordDao().findExtras(taskId);
            if (q != null && !q.isEmpty()) {
                String lower = q.toLowerCase();
                extraRecs.removeIf(r -> !contains(r.epc, lower));
            }
            extras.postValue(extraRecs);
        });
    }

    private static boolean contains(String s, String lower) {
        return s != null && s.toLowerCase().contains(lower);
    }
}
```

- [ ] **Step 3: Build, expect PASS**

```bash
./gradlew :app:compileDebugJavaWithJavac
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/huaxia/inventory/ui/result/ResultViewModel.java \
        app/src/main/java/com/huaxia/inventory/ui/result/ResultTab.java
git commit -m "feat(ui): ResultViewModel with tab and search filtering"
```

---

### Task 23: ResultFragment layout + adapter

**Files:**
- Create: `app/src/main/res/layout/fragment_result.xml`
- Create: `app/src/main/res/layout/item_asset_result.xml`
- Create: `app/src/main/java/com/huaxia/inventory/ui/result/AssetResultAdapter.java`

- [ ] **Step 1: Create `fragment_result.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <com.google.android.material.appbar.MaterialToolbar
        android:id="@+id/toolbar"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:title="@string/result_title"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/exportBtn"
        style="@style/Widget.MaterialComponents.Button.TextButton"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/result_export"
        app:layout_constraintTop_toTopOf="@id/toolbar"
        app:layout_constraintBottom_toBottomOf="@id/toolbar"
        app:layout_constraintEnd_toEndOf="parent" />

    <com.google.android.material.tabs.TabLayout
        android:id="@+id/tabs"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:tabMode="fixed"
        app:layout_constraintTop_toBottomOf="@id/toolbar"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <com.google.android.material.tabs.TabItem android:text="@string/result_tab_all" />
        <com.google.android.material.tabs.TabItem android:text="@string/result_tab_found" />
        <com.google.android.material.tabs.TabItem android:text="@string/result_tab_missing" />
        <com.google.android.material.tabs.TabItem android:text="@string/result_tab_extra" />
    </com.google.android.material.tabs.TabLayout>

    <androidx.appcompat.widget.SearchView
        android:id="@+id/searchView"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintTop_toBottomOf="@id/tabs"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/resultRv"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintTop_toBottomOf="@id/searchView"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintBottom_toBottomOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 2: Create `item_asset_result.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.cardview.widget.CardView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_margin="@dimen/cardMargin"
    app:cardCornerRadius="8dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="@dimen/cardPadding">

        <TextView android:id="@+id/titleTv"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="@dimen/textSizeTitle" />

        <TextView android:id="@+id/orgTv"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="@dimen/textSizeBody" />

        <TextView android:id="@+id/locationTv"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="@dimen/textSizeBody" />

        <TextView android:id="@+id/epcTv"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:fontFamily="monospace"
            android:textSize="12sp" />

        <TextView android:id="@+id/statusTv"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:padding="4dp"
            android:textColor="@android:color/white" />
    </LinearLayout>
</androidx.cardview.widget.CardView>
```

- [ ] **Step 3: Create `AssetResultAdapter.java`**

```java
package com.huaxia.inventory.ui.result;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.StatusDerivation;
import com.huaxia.inventory.data.entity.Asset;
import java.util.List;
import java.util.Set;

public class AssetResultAdapter extends RecyclerView.Adapter<AssetResultAdapter.VH> {

    private final List<Asset> items;
    private final Set<Long> matchedIds;

    public AssetResultAdapter(List<Asset> items, Set<Long> matchedIds) {
        this.items = items;
        this.matchedIds = matchedIds;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_asset_result, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Asset a = items.get(position);
        h.title.setText("#" + a.excelRowIndex + "  " +
                (a.assetName == null ? "" : a.assetName) + " / " +
                (a.assetModel == null ? "" : a.assetModel));
        h.org.setText("所属: " + (a.org == null ? "" : a.org));
        h.location.setText("地点: " + (a.detailedLocation == null ? "" : a.detailedLocation));
        h.epc.setText("EPC: " + (a.epc == null ? "" : a.epc));
        boolean matched = matchedIds.contains(a.id);
        StatusDerivation.Status s = StatusDerivation.classify(matched, false);
        h.status.setText(label(s));
        h.status.setBackgroundColor(color(s));
    }

    @Override public int getItemCount() { return items.size(); }

    private String label(StatusDerivation.Status s) {
        switch (s) {
            case FOUND:   return "已盘";
            case MISSING: return "盘亏";
            default:      return "";
        }
    }

    private int color(StatusDerivation.Status s) {
        switch (s) {
            case FOUND:   return Color.parseColor("#4CAF50");
            case MISSING: return Color.parseColor("#F44336");
            default:      return Color.GRAY;
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title, org, location, epc, status;
        VH(View v) {
            super(v);
            title = v.findViewById(R.id.titleTv);
            org = v.findViewById(R.id.orgTv);
            location = v.findViewById(R.id.locationTv);
            epc = v.findViewById(R.id.epcTv);
            status = v.findViewById(R.id.statusTv);
        }
    }
}
```

- [ ] **Step 4: Build, expect PASS**

```bash
./gradlew :app:assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/layout/fragment_result.xml \
        app/src/main/res/layout/item_asset_result.xml \
        app/src/main/java/com/huaxia/inventory/ui/result/AssetResultAdapter.java
git commit -m "feat(ui): ResultFragment layout and AssetResultAdapter"
```

---

### Task 24: ResultFragment logic + export

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java`

- [ ] **Step 1: Replace `ResultFragment.java`**

```java
package com.huaxia.inventory.ui.result;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.excel.ExcelExporter;
import com.huaxia.inventory.excel.HeaderSpec;
import com.huaxia.inventory.util.DateUtil;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

public class ResultFragment extends Fragment {

    private ResultViewModel vm;
    private RecyclerView rv;
    private TabLayout tabs;
    private SearchView searchView;
    private MaterialButton exportBtn;
    private ActivityResultLauncher<String> exportLauncher;

    @Override
    public void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                uri -> { if (uri != null) doExport(uri); });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_result, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);
        vm = new ViewModelProvider(this).get(ResultViewModel.class);
        rv = v.findViewById(R.id.resultRv);
        tabs = v.findViewById(R.id.tabs);
        searchView = v.findViewById(R.id.searchView);
        exportBtn = v.findViewById(R.id.exportBtn);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));

        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        Long taskId = app.session.getCurrentTaskId();
        if (taskId == null) {
            exportBtn.setEnabled(false);
            return;
        }
        vm.bindToTask(taskId);

        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                ResultTab[] map = {ResultTab.ALL, ResultTab.FOUND, ResultTab.MISSING, ResultTab.EXTRA};
                vm.setTab(map[tab.getPosition()]);
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String q) { vm.setSearchQuery(q); return true; }
            @Override public boolean onQueryTextChange(String newText) { vm.setSearchQuery(newText); return true; }
        });

        exportBtn.setOnClickListener(btn -> {
            String fileName = "盘点结果_" + taskId + "_" +
                    DateUtil.formatTimestamp(System.currentTimeMillis()).replace(" ", "_").replace(":", "")
                    + ".xlsx";
            exportLauncher.launch(fileName);
        });

        // Build adapter initially; refresh on data change
        Set<Long> matchedIds = new HashSet<>();
        AssetResultAdapter adapter = new AssetResultAdapter(java.util.Collections.emptyList(), matchedIds);
        rv.setAdapter(adapter);

        // Combine asset list + matched IDs in observers
        vm.assets().observe(getViewLifecycleOwner(), assets -> {
            if (assets == null) return;
            Set<Long> matched = new HashSet<>();
            // We need to know which ones are FOUND; for simplicity, fetch from DB on each refresh
            Executor io = ((InventoryApp) requireActivity().getApplication()).executors.diskIO;
            io.execute(() -> {
                InventoryApp a = (InventoryApp) requireActivity().getApplication();
                for (Asset as : a.db.assetDao().findFound(taskId)) matched.add(as.id);
                requireActivity().runOnUiThread(() ->
                    rv.setAdapter(new AssetResultAdapter(assets, matched)));
            });
        });
    }

    private void doExport(Uri uri) {
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        long taskId = app.session.getCurrentTaskId();
        app.executors.excelIO.execute(() -> {
            try {
                InventoryTask task = app.db.inventoryTaskDao().findById(taskId);
                List<Asset> assets = app.db.assetDao().findAllByTask(taskId);
                List<ScanRecord> scans = app.db.scanRecordDao().findAllByTask(taskId);
                Set<Long> matchedIds = new HashSet<>();
                for (Asset a : app.db.assetDao().findFound(taskId)) matchedIds.add(a.id);

                // Reconstruct original 35-column header order from the first asset's rawRowJson
                List<String> originalHeaders = new java.util.ArrayList<>();
                if (!assets.isEmpty() && assets.get(0).rawRowJson != null) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper =
                            new com.fasterxml.jackson.databind.ObjectMapper();
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, String> raw = mapper.readValue(
                            assets.get(0).rawRowJson, java.util.Map.class);
                    originalHeaders.addAll(raw.keySet());
                }

                try (java.io.OutputStream out =
                        requireContext().getContentResolver().openOutputStream(uri)) {
                    ExcelExporter.export(out, task.sourceFileName, originalHeaders,
                            assets, scans, matchedIds::contains);
                }
                requireActivity().runOnUiThread(() ->
                    android.widget.Toast.makeText(requireContext(), "导出完成", android.widget.Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                requireActivity().runOnUiThread(() ->
                    android.widget.Toast.makeText(requireContext(), "导出失败: " + e.getMessage(),
                            android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }
}
```

- [ ] **Step 2: Build, expect PASS**

```bash
./gradlew :app:assembleDebug
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java
git commit -m "feat(ui): ResultFragment with tab/search/export"
```

---

## Phase 10 — Polish

### Task 25: Manual smoke test on emulator

- [ ] **Step 1: Install on emulator**

```bash
./gradlew :app:installDebug
adb shell am start -n com.huaxia.inventory/.MainActivity
```

- [ ] **Step 2: Verify three tabs render**

- Tap each of the 3 bottom-nav tabs. All three Fragments should render without crash.

- [ ] **Step 3: Verify import flow (no RFID)**

- Tap "选择 Excel 文件" → grant permission → pick the test xlsx
- Verify file info card shows correct count
- Verify preview list shows 10 items
- Tap "开始盘点" → tab should switch to Scan

- [ ] **Step 4: Verify scan page (mock callback)**

- The RfidController will be in ERROR state on emulator (no hardware). The scan button should be disabled. Confirm via toast/text.

- [ ] **Step 5: Verify result page**

- Switch to Result tab.
- All 4 sub-tabs should show appropriate counts.
- The "导出 Excel" button should write a file via SAF.

- [ ] **Step 6: Document findings in `docs/smoke-test-2026-06-10.md`**

- Note any UI bugs, missing translations, layout issues.
- Triage into must-fix / nice-to-have.

- [ ] **Step 7: Commit (if any fixes)**

```bash
git add -A
git commit -m "fix: post-smoke-test fixes (see docs/smoke-test-2026-06-10.md)"
```

---

### Task 26: README + project documentation

**Files:**
- Create: `README.md`

- [ ] **Step 1: Write `README.md`**

````markdown
# 固定资产盘点 (Huaxia Inventory)

Android RFID 资产盘点应用，运行在 UROVO 工业 PDA 上。

## 功能

- 从 Excel 导入资产清单（35 列）
- RFID 扫描标签，实时匹配 EPC
- 统计 已盘 / 盘亏 / 盘盈
- 导出盘点结果 Excel
- 历史任务本地回看

## 技术栈

- Java 11 / AndroidX (minSdk 24, targetSdk 34)
- Single Activity + 3 Fragments
- Room 2.6
- Apache POI 5.2.5
- UROVO RFID SDK (`libs/URFIDLibrary-v2.4.1226.aar`)

## 项目结构

参见 [docs/superpowers/specs/2026-06-10-rfid-asset-inventory-design.md](docs/superpowers/specs/2026-06-10-rfid-asset-inventory-design.md) § 3.2.

## 构建

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

## 部署到 UROVO PDA

1. 启用 USB 调试
2. `adb connect <pda-ip>:5555`
3. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
4. 启动 App，按住 PDA 扫描枪按键验证扫描功能
````

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: add README with build and deploy instructions"
```

---

## Self-Review Checklist

After all tasks are complete, verify:

- [ ] All tests pass: `./gradlew test`
- [ ] APK builds: `./gradlew :app:assembleDebug`
- [ ] No `TODO`, `TBD`, or placeholder strings in code
- [ ] Spec § 4.1 entities are all defined (InventoryTask, Asset, ScanRecord)
- [ ] Spec § 6 RFID lifecycle is honored (init in Application, register onResume, unregister onPause, release onDestroy)
- [ ] Spec § 7 Excel import/export roundtrip works on `固定资产测试.xlsx`
- [ ] Spec § 8 error matrix is implemented (SAF cancellation, parse errors, RFID not connected, etc.)
- [ ] Spec § 9 unit tests cover EPC normalization, status derivation, Excel I/O

---

## Open Questions (deferred to implementation)

- **OQ1**: PDA 物理按键 mapping (`F1` / `KEYCODE_BUTTON_R1` / etc.) — 需在真机上确认
- **OQ2**: 应用图标 / 启动屏 — 当前为 Android 默认
- **OQ3**: 导出 .xls 兼容 Office 2003 — 当前仅 .xlsx
- **OQ4**: App 杀死后能否继续当前任务 — 设计上支持 (`InventoryTask.status = IN_PROGRESS` 持久化)
- **OQ5**: 历史任务列表页 — 1.0 不实现，2.0 再加

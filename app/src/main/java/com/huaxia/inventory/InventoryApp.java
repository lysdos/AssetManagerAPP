package com.huaxia.inventory;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.room.Room;
import com.huaxia.inventory.barcode.BarcodeHardwareController;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.rfid.RfidController;
import com.huaxia.inventory.rfid.SdkRfidAdapter;
import com.huaxia.inventory.session.InventorySession;
import com.huaxia.inventory.util.AppExecutors;

public class InventoryApp extends Application {

    private static final String TAG = "UHFInit";
    private static final String PREFS_NAME = "huaxia_inventory_prefs";
    private static final String KEY_RFID_POWER = "rfid_power";
    private static final int DEFAULT_POWER = 26; // 默认功率26dBm

    /**
     * Application 级核心服务 — 全项目 10+ 个文件直接读这些字段, 切到
     * private + getter 需大改 call site. 这里做折中: 保留 public 但加
     * {@code @VisibleForTesting} 让 lint 知道这是有意暴露的入口, 并配合
     * 顶部 Javadoc 警告 — 跨线程读字段的风险由内部服务本身处理
     * (InventorySession 走 MutableLiveData / AppDatabase 走 Room /
     * RfidController 走 AtomicBoolean + volatile reader).
     *
     * <p><b>不变量</b>: 在 {@link #onCreate()} 一次性赋值后, 不应在后续
     * 重新赋值 — 跨线程读到被替换的实例, 会破坏 RFID/DB 单例假设.
     * 为避免此坑, 把这四条字段都改为 {@code private final} 会大改 call site
     * (10+ caller), 暂时保留 public. 后续若要把这层封装改强, 推荐提供
     * {@link #getExecutors()}/{@link #getSession()}/etc. getter + deprecate
     * public 字段, 一次性让所有 caller 迁移.</p>
     */
    @androidx.annotation.VisibleForTesting
    public AppExecutors executors;
    @androidx.annotation.VisibleForTesting
    public InventorySession session;
    @androidx.annotation.VisibleForTesting
    public RfidController rfid;
    @androidx.annotation.VisibleForTesting
    public AppDatabase db;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "[InventoryApp] onCreate start, BuildConfig.DEBUG=" + BuildConfig.DEBUG);

        // 初始化SharedPreferences
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Android ART 把 java.lang.invoke.LambdaMetafactory 列在 hidden-api blacklist,
        // log4j-api 2.21.1 的 ServiceLoaderUtil 用它生成 Provider 工厂, 触发 NoSuchMethodError,
        // 导致 POIXMLDocumentPart.<clinit> 永久 uninitialized, XSSFWorkbook 无法构造.
        // 官方 property log4j2.loggerContextFactory 让 LogManager.<clinit> 直接 new
        // SimpleLoggerContextFactory, 跳过 ProviderUtil.hasProviders() 整条 ServiceLoader 路径.
        System.setProperty("log4j2.loggerContextFactory",
                "org.apache.logging.log4j.simple.SimpleLoggerContextFactory");
        Log.i(TAG, "[InventoryApp] log4j2.loggerContextFactory set");

        executors = new AppExecutors();
        session = new InventorySession();
        rfid = new RfidController(new SdkRfidAdapter());
        db = Room.databaseBuilder(getApplicationContext(), AppDatabase.class, "huaxia-inventory.db")
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_5_6)
                .fallbackToDestructiveMigration()
                .build();

        // 启动时一次性清理孤儿. 正常删除路径 (TaskListViewModel 三处) 已包 runInTransaction,
        // 不会产生新孤儿; 这里是兜底清历史遗留 — 历史版本 (v4 之前) 串行调 DAO,
        // 中途崩溃 / 进程被杀都可能留孤儿. 顺序: 先 ScanRecord 再 ExtraClaim
        // (ExtraClaim.scanRecordId 是 ScanRecord.id FK, 必须父表先清).
        executors.diskIO.execute(() -> {
            int removedScans = db.scanRecordDao().deleteOrphans();
            int removedClaims = db.extraClaimDao().deleteOrphans();
            if (removedScans > 0 || removedClaims > 0) {
                Log.i(TAG, "[InventoryApp] orphan cleanup removed "
                        + removedScans + " ScanRecord(s), "
                        + removedClaims + " ExtraClaim(s)");
            } else {
                Log.i(TAG, "[InventoryApp] orphan cleanup: no orphans found");
            }
        });

        // 预热硬件扫码 SDK (rscja BarcodeDecoder).
        // 跟 RFID init 同模式: 后台线程 open, 不阻塞 app 启动.
        // 失败不致命, ManualScanFragment 会静默降级到 CameraX + ML Kit.
        executors.diskIO.execute(() -> BarcodeHardwareController.prewarm(this));

        // 诊断: R8 minify 在 release 开启 (debug 已关), com.rscja.team.mtk.* 等
        // 平台实现类会被 R8 误判为"不可达"剥掉 — proguard-rules.pro 已 keep 但偶尔
        // 会因 SDK 升级引入未覆盖的类. 用 Class.forName(false, classLoader) 探一下,
        // 找出哪些类丢了. 仅 release 才有意义, debug 是 no-op 但仍跑消耗 ≈100ms.
        checkRfidClasses();

        Log.i(TAG, "[InventoryApp] calling rfid.init()");
        rfid.init(this, ready -> {
            Log.i(TAG, "[InventoryApp] rfid.init callback ready=" + ready);
            // init 成功后自动应用上次保存的功率. 失败则保持 reader 默认功率不动.
            // 切到 diskIO 后台线程 + try/catch Throwable 兜住: setPower 内部仍走
            // SDK native 调用, HAL miss / SDK 状态机错误时主线程累计阻塞会触发
            // 启动期 ANR. 后台线程 + 错误日志即可, 不影响 init 本身已 READY 的状态.
            if (ready) {
                final int savedPower = getSavedPower();
                executors.diskIO.execute(() -> {
                    try {
                        boolean applied = rfid.setPower(savedPower);
                        Log.i(TAG, "[InventoryApp] auto-applied saved power=" + savedPower
                                + " dBm, success=" + applied);
                    } catch (Throwable t) {
                        Log.w(TAG, "[InventoryApp] setPower failed", t);
                    }
                });
            }
        });
        Log.i(TAG, "[InventoryApp] onCreate end");
    }

    private void checkRfidClasses() {
        // 这些是 rscja SDK 内可能被 R8 误剥的类 — release build R8 minify 后的诊断,
        // debug build 是 no-op (R8 disabled). C72_6765 是 MTK 设备, 重点看 mtk 子包.
        // 任何一项 ClassNotFoundException 都证明 proguard-rules.pro 缺 keep —
        // 补一条 -keep class com.rscja.X { *; } 重打 release.
        String[] classes = {
            "com.rscja.deviceapi.RFIDWithUHFUART",
            "com.rscja.deviceapi.UhfBase",
            "com.rscja.deviceapi.entity.UHFTAGInfo",
            "com.rscja.team.mtk.deviceapi.FingerprintWithMorpho_mtk",
            "com.rscja.team.mtk.custom.UHFTemperatureTagsAPI_mtk",
            "com.rscja.utility.StringUtility",
            "com.rscja.CWDeviceInfo",
            "com.rscja.system.SystemInterfacesFactory",
        };
        ClassLoader cl = getClassLoader();
        for (String name : classes) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                Log.i(TAG, "[ClassCheck] OK    " + name + " -> " + c.getName());
            } catch (Throwable t) {
                Log.e(TAG, "[ClassCheck] MISS  " + name + " : " + t.getClass().getSimpleName()
                        + " " + t.getMessage());
            }
        }
    }

    /**
     * 获取保存的RFID功率设置。
     *
     * @return 保存的功率值(dBm)，未设置返回默认值26
     */
    public int getSavedPower() {
        return prefs.getInt(KEY_RFID_POWER, DEFAULT_POWER);
    }

    /**
     * 保存RFID功率设置。
     *
     * @param power 功率值(dBm)
     */
    public void savePower(int power) {
        prefs.edit().putInt(KEY_RFID_POWER, power).apply();
        Log.i(TAG, "Power saved: " + power + " dBm");
    }
}

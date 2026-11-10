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
     * Application 级核心服务 — 全部 {@code private}, 通过 getter
     * {@link #getExecutors()}/{@link #getSession()}/{@link #getRfid()}/
     * {@link #getDb()} 暴露给外部. 调用方必须经 getter 而非直接读字段,
     * 让内部实例 (尤其是 RFID/DB) 的不变量 (single instance /
     * not reassigned cross-thread) 由 getter API 单点维护, 未来要加 lazy
     * init / 字段替换为代理 (例如 {@code RfidController} 切到 mock 单例)
     * 也只在一个方法里改.
     *
     * <p>迁移路径: {@code ((InventoryApp) getApplication()).rfid.xxx} →
     * {@code ((InventoryApp) getApplication()).getRfid().xxx}, 余类推.</p>
     */
    private AppExecutors executors;
    private InventorySession session;
    private RfidController rfid;
    private AppDatabase db;
    private SharedPreferences prefs;

    /** 后台线程 (diskIO / excelIO / mainThread) 池. */
    public AppExecutors getExecutors() { return executors; }

    /** 当前任务的 LiveData 全局状态. */
    public InventorySession getSession() { return session; }

    /** UHF RFID 读写器 controller (含 power / startInventory / stopInventory). */
    public RfidController getRfid() { return rfid; }

    /** Room 数据库 (Asset / InventoryTask / ScanRecord / ExtraClaim 四表). */
    public AppDatabase getDb() { return db; }

    /**
     * RFID 功率保存 prefs — 主要给 PowerSettingDialog 的功率滑块读写使用,
     * 暴露成 getter 让 UI 不必再绕道 InventoryApp 内部字段.
     */
    public SharedPreferences getPrefs() { return prefs; }

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
        // version = 1 是首次发布基线, 当前没有任何迁移脚本 (详见 AppDatabase javadoc).
        // 用带 OnDowngrade 后缀的 API: 降级安装仍走 destructive 重建 (合理), 但升级
        // 路径缺迁移会抛异常、启动即可发现 —— 不带该后缀的 fallbackToDestructiveMigration
        // 会把"漏写迁移"变成静默清空用户数据, 所以刻意不用它.
        db = Room.databaseBuilder(getApplicationContext(), AppDatabase.class, "huaxia-inventory.db")
                .fallbackToDestructiveMigrationOnDowngrade()
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

        // 预热硬件扫码 SDK (Urovo ScanManager).
        // 跟 RFID init 同模式: 后台线程 open, 不阻塞 app 启动.
        // 失败不致命, ManualScanFragment 会静默降级到 CameraX + ML Kit.
        executors.diskIO.execute(() -> BarcodeHardwareController.prewarm(this));

        // 诊断: R8 minify 只在 release 开启 (debug 已关), com.ubx.usdk.* 等
        // SDK 内部类会被 R8 误判为"不可达"剥掉 — proguard-rules.pro 已 keep 但偶尔
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
        // 这些是 Urovo SDK 内可能被 R8 误剥的类 — release build R8 minify 后的诊断,
        // debug build 是 no-op (R8 disabled).
        //
        // 验证流程 (release APK):
        //   1. ./gradlew assembleRelease 安装到测试机
        //   2. adb logcat -s InventoryApp | grep ClassCheck
        //   3. 期望 7 行 "[ClassCheck] OK    ..." — 任一 MISS 证明
        //      proguard-rules.pro 缺对应 -keep, 补一行重打 release.
        // 完整 keep 列表见 app/proguard-rules.pro 的 "Urovo UHF/Barcode SDK" 块.
        String[] classes = {
            "com.ubx.usdk.RFIDSDKManager",
            "com.ubx.usdk.rfid.RfidManager",
            "com.ubx.usdk.rfid.aidl.IRfidCallback",
            "com.ubx.usdk.bean.Tag6C",
            "android.device.ScanManager",
            "android.device.DeviceManager",
            "android.device.scanner.configuration.PropertyID",
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

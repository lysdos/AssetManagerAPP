package com.huaxia.inventory;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.room.Room;
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

    public AppExecutors executors;
    public InventorySession session;
    public RfidController rfid;
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
                .addMigrations(AppDatabase.MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build();

        // 诊断: R8 minify 在 debug 开启后, com.rscja.team.mtk.* 等平台实现类
        // 会被 R8 误判为"不可达"剥掉. 用 Class.forName 探一下, 找出哪些类丢了.
        checkRfidClasses();

        Log.i(TAG, "[InventoryApp] calling rfid.init()");
        rfid.init(this, ready -> Log.i(TAG, "[InventoryApp] rfid.init callback ready=" + ready));
        Log.i(TAG, "[InventoryApp] onCreate end");
    }

    private void checkRfidClasses() {
        // 这些是 rscja SDK 内可能被 R8 误剥的类. C72_6765 是 MTK 设备, 重点看 mtk 子包.
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

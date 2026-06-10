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

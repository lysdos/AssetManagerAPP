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
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private long lastStopTime = 0L;
    private static final long POWER_OFF_AFTER_MS = 30 * 60 * 1000L;  // 30 min

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_export) {
                Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragmentContainer);
                if (current instanceof ResultFragment) {
                    ((ResultFragment) current).onExportClicked();
                    return true;
                }
            }
            return false;
        });

        BottomNavigationView nav = findViewById(R.id.bottomNav);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_import) return showFragment(new ImportFragment(), "import");
            if (id == R.id.nav_scan)   return showFragment(new ScanFragment(),   "scan");
            if (id == R.id.nav_result) return showFragment(new ResultFragment(), "result");
            return false;
        });

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

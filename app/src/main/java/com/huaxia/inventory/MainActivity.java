package com.huaxia.inventory;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;
import com.huaxia.inventory.ui.import_.ImportViewModel;
import com.huaxia.inventory.ui.manual.ManualScanFragment;
import com.huaxia.inventory.ui.result.ResultFragment;
import com.huaxia.inventory.ui.scan.ScanFragment;
import com.huaxia.inventory.ui.task.TaskListFragment;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.huaxia.inventory.ui.setting.PowerSettingDialog;

public class MainActivity extends AppCompatActivity {

    private long lastStopTime = 0L;
    private static final long POWER_OFF_AFTER_MS = 30 * 60 * 1000L;  // 30 min

    private ImportViewModel importVm;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        BottomNavigationView nav = findViewById(R.id.bottomNav);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_task) return showFragment(new TaskListFragment(), "task");
            if (id == R.id.nav_scan)   return showFragment(new ScanFragment(),   "scan");
            if (id == R.id.nav_result) return showFragment(new ResultFragment(), "result");
            return false;
        });

        if (savedInstanceState == null) {
            nav.setSelectedItemId(R.id.nav_task);
        }

        importVm = new ViewModelProvider(this).get(ImportViewModel.class);
        // 导入文件选择 / 解析 / 进度 / 完成提示全部由 ImportDialogFragment 处理
        // (齿轮 PopupMenu → "导入资产" → 打开 dialog).
        // 库存池模式: 导入完不再自动跳到扫描页 — 用户自行去"任务"tab 创建任务并点"开始盘点"。
        // 错误提示保留 Activity 级 toast (DialogFragment dismiss 后还能看见)。
        importVm.importError().observe(this, err -> {
            if (err == null) return;
            Toast.makeText(this, getString(R.string.err_import_failed_fmt, err),
                    Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        MenuItem settingsItem = menu.findItem(R.id.action_settings);
        if (settingsItem != null) {
            View anchor = settingsItem.getActionView();
            if (anchor != null) {
                anchor.setOnClickListener(this::showSettingsPopup);
            }
        }
        return true;
    }

    private void showSettingsPopup(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.popup_settings, popup.getMenu());
        // PopupMenu 默认不显示 menu item 图标, 反射打开
        forceShowIcon(popup);
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_action_power) {
                showPowerSettingDialog();
                return true;
            }
            if (id == R.id.menu_action_import) {
                openImportDialog();
                return true;
            }
            return false;
        });
        popup.show();
    }

    /**
     * 反射 PopupMenu.mPopup (内部 MenuPopupHelper) 并调 setForceShowIcon(true),
     * 让 popup 的两个 item 在前面显示 icon, 跟 HTML 设计稿对齐.
     * 不直接 import MenuPopupHelper — 它是 androidx RestrictedApi, lint 会报.
     */
    private void forceShowIcon(PopupMenu popup) {
        try {
            java.lang.reflect.Field f = popup.getClass().getDeclaredField("mPopup");
            f.setAccessible(true);
            Object helper = f.get(popup);
            if (helper != null) {
                helper.getClass().getMethod("setForceShowIcon", boolean.class)
                        .invoke(helper, true);
            }
        } catch (Exception e) {
            // 反射失败不致命, 只是不显示图标
        }
    }

    private void openImportDialog() {
        new com.huaxia.inventory.ui.import_.ImportDialogFragment()
                .show(getSupportFragmentManager(), "import");
    }

    private boolean showFragment(Fragment fragment, String tag) {
        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        tx.replace(R.id.fragmentContainer, fragment, tag);
        tx.commit();
        return true;
    }

    private void showPowerSettingDialog() {
        PowerSettingDialog dialog = PowerSettingDialog.newInstance();
        dialog.show(getSupportFragmentManager(), "power_setting");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getKeyCode() == 139 || event.getKeyCode() == 280 || event.getKeyCode() == 293)
                && event.getRepeatCount() == 0) {
            Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragmentContainer);
            if (f instanceof ScanFragment) {
                ((ScanFragment) f).onTriggerPressed();
                return true;
            }
            if (f instanceof ManualScanFragment) {
                ((ManualScanFragment) f).onTriggerPressed();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
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
        // 仅释放 Activity 独占的 RFID 句柄. executors 是 Application 单例 —
        // 跟 JVM 同生死, 千万不能在 Activity onDestroy 里 shutdown.
        // 否则下一个 Activity 实例重建时所有 ViewModel 的 diskIO.execute() 都会崩.
        ((InventoryApp) getApplication()).rfid.release();
    }
}
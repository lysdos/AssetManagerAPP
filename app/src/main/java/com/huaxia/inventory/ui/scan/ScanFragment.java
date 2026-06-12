package com.huaxia.inventory.ui.scan;

import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.R;
import com.huaxia.inventory.rfid.SdkRfidAdapter;
import com.huaxia.inventory.ui.result.ResultFragment;
import com.huaxia.inventory.ui.result.ResultTab;
import com.huaxia.inventory.ui.util.TagPills;
import com.huaxia.inventory.util.DateUtil;
import com.huaxia.inventory.util.EpcNormalizer;

public class ScanFragment extends Fragment {

    private ScanViewModel vm;
    private MaterialButton scanBtn;
    private MaterialButton clearTaskBtn;
    private TextView deviceStatusTv;
    private TextView inStockNumber, scannedNumber, anomalyNumber;
    private TextView inStockSub, scannedSub, anomalySub;
    private TextView checkedCardTitle, checkedCardDesc, checkedCardSub;
    private TextView recentCardTitle, recentCardDesc;
    private LinearLayout recentTags;

    private SoundPool soundPool;
    private int beepSoundId;
    private AudioManager audioManager;

    private final SdkRfidAdapter.TagListener rfidCallback = (epcStr, tid, rssi) -> {
        String normalized = EpcNormalizer.normalize(epcStr);
        if (normalized == null) return;
        vm.onEpcScanned(normalized, tid, rssi);
        playBeep();
    };

    public void onTriggerPressed() {
        toggleScan();
    }

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
        clearTaskBtn = v.findViewById(R.id.clearTaskBtn);
        deviceStatusTv = v.findViewById(R.id.deviceStatusTv);

        // Bind stat card labels + sub texts (default values; will be updated when stats observe)
        bindStatCard(v, R.id.inStockStat, R.string.scan_stat_in_stock,
                R.color.statusFound, getString(R.string.scan_stat_sub_today, 0));
        bindStatCard(v, R.id.scannedStat, R.string.scan_stat_scanned,
                R.color.statScanned, "0%");
        bindStatCard(v, R.id.anomalyStat, R.string.scan_stat_anomaly,
                R.color.statusMissing, "需处理");

        inStockNumber = v.findViewById(R.id.inStockStat).findViewById(R.id.statNumberTv);
        scannedNumber = v.findViewById(R.id.scannedStat).findViewById(R.id.statNumberTv);
        anomalyNumber = v.findViewById(R.id.anomalyStat).findViewById(R.id.statNumberTv);
        inStockSub = v.findViewById(R.id.inStockStat).findViewById(R.id.statSubTv);
        scannedSub = v.findViewById(R.id.scannedStat).findViewById(R.id.statSubTv);
        anomalySub = v.findViewById(R.id.anomalyStat).findViewById(R.id.statSubTv);

        // Bind info cards
        View checkedCard = v.findViewById(R.id.checkedCard);
        checkedCardTitle = checkedCard.findViewById(R.id.infoTitleTv);
        checkedCardDesc = checkedCard.findViewById(R.id.infoDescTv);
        checkedCardSub = checkedCard.findViewById(R.id.infoSubTv);
        checkedCardTitle.setText(R.string.scan_info_checked);
        checkedCardDesc.setText(R.string.scan_info_checked_desc);
        checkedCardSub.setVisibility(View.VISIBLE);

        View recentCard = v.findViewById(R.id.recentResultCard);
        recentCardTitle = recentCard.findViewById(R.id.infoTitleTv);
        recentCardDesc = recentCard.findViewById(R.id.infoDescTv);
        recentTags = recentCard.findViewById(R.id.infoTagsContainer);
        recentCardTitle.setText(R.string.scan_info_recent);

        scanBtn.setOnClickListener(btn -> toggleScan());
        clearTaskBtn.setOnClickListener(btn -> confirmClearTask());
        applyScanBtnGreen();

        // 点击统计卡片跳转结果页并筛选
        v.findViewById(R.id.inStockStat).setOnClickListener(
                card -> navigateToResult(ResultTab.ALL));
        v.findViewById(R.id.scannedStat).setOnClickListener(
                card -> navigateToResult(ResultTab.FOUND));
        v.findViewById(R.id.anomalyStat).setOnClickListener(
                card -> navigateToResult(ResultTab.MISSING));

        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        Long taskId = app.session.getCurrentTaskId();
        if (taskId == null) {
            deviceStatusTv.setText(getString(R.string.scan_device_status,
                    getString(R.string.scan_status_not_ready)));
            scanBtn.setEnabled(false);
            return;
        }
        vm.bindToTask(taskId);

        vm.stats().observe(getViewLifecycleOwner(), stats -> {
            inStockNumber.setText(String.valueOf(stats.inStock));
            inStockSub.setText(getString(R.string.scan_stat_sub_today, stats.todayAdded));
            scannedNumber.setText(String.valueOf(stats.scanned));
            int pct = stats.inStock == 0 ? 0 :
                    (int) Math.round(100.0 * stats.scanned / stats.inStock);
            scannedSub.setText(pct + "%");
            anomalyNumber.setText(String.valueOf(stats.anomaly));
            anomalySub.setText(stats.anomaly > 0 ? "需处理" : "OK");

            checkedCardSub.setText(getString(R.string.scan_info_checked_sub,
                    stats.lastScannedAt == 0 ? "—" : DateUtil.formatTimestamp(stats.lastScannedAt)));
            recentCardDesc.setText(getString(R.string.scan_info_recent_desc,
                    stats.found, stats.missing, stats.extra));
            renderResultTags(recentTags, stats);
        });

        deviceStatusTv.setText(getString(R.string.scan_device_status,
                app.rfid.getState() == com.huaxia.inventory.rfid.RfidController.State.READY
                || app.rfid.getState() == com.huaxia.inventory.rfid.RfidController.State.SCANNING
                ? getString(R.string.scan_status_ready)
                : getString(R.string.scan_status_not_ready)));

        vm.clearResult().observe(getViewLifecycleOwner(), result -> {
            if (result == null) return;
            if (result.isError()) {
                Snackbar.make(requireView(),
                        getString(R.string.scan_clear_task_failed, result.error),
                        Snackbar.LENGTH_LONG).show();
            } else {
                Snackbar.make(requireView(),
                        getString(R.string.scan_clear_task_done, result.assets, result.scans),
                        Snackbar.LENGTH_LONG).show();
            }
        });

        initSound();
    }

    private void confirmClearTask() {
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        Long taskId = app.session.getCurrentTaskId();
        if (taskId == null) {
            Snackbar.make(requireView(), R.string.scan_clear_task_failed,
                    Snackbar.LENGTH_SHORT).show();
            return;
        }
        long tid = taskId;
        app.executors.diskIO.execute(() -> {
            int assets = app.db.assetDao().countByTask(tid);
            int scans = app.db.scanRecordDao().countByTask(tid);
            requireActivity().runOnUiThread(() -> {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.scan_clear_task_confirm_title)
                        .setMessage(getString(R.string.scan_clear_task_confirm_msg, assets, scans))
                        .setPositiveButton(R.string.scan_clear_task_confirm_ok,
                                (d, w) -> vm.clearCurrentTask())
                        .setNegativeButton(R.string.scan_clear_task_confirm_cancel, null)
                        .show();
            });
        });
    }

    private void bindStatCard(View root, int includeId, int labelRes, int colorRes, String sub) {
        View card = root.findViewById(includeId);
        TextView label = card.findViewById(R.id.statLabelTv);
        TextView number = card.findViewById(R.id.statNumberTv);
        TextView subTv = card.findViewById(R.id.statSubTv);
        label.setText(labelRes);
        number.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), colorRes));
        subTv.setVisibility(View.VISIBLE);
        subTv.setText(sub);
    }

    private void renderResultTags(LinearLayout container, ScanViewModel.Stats stats) {
        container.removeAllViews();
        TagPills.bindCount(container, R.string.tag_found, stats.found, R.drawable.bg_tag_green, R.color.tagGreenFg);
        TagPills.bindCount(container, R.string.tag_missing, stats.missing, R.drawable.bg_tag_red, R.color.tagRedFg);
        TagPills.bindCount(container, R.string.tag_extra, stats.extra, R.drawable.bg_tag_orange, R.color.tagOrangeFg);
    }

    private void toggleScan() {
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        var state = app.rfid.getState();
        if (state == com.huaxia.inventory.rfid.RfidController.State.SCANNING) {
            app.rfid.stopInventory();
            scanBtn.setText(R.string.scan_start_inventory);
            applyScanBtnGreen();
        } else {
            app.rfid.startInventory();
            scanBtn.setText(R.string.scan_stop);
            applyScanBtnRed();
        }
    }

    private void navigateToResult(ResultTab tab) {
        ResultFragment fragment = new ResultFragment();
        Bundle args = new Bundle();
        args.putString(ResultFragment.ARG_TAB, tab.name());
        fragment.setArguments(args);
        requireActivity().getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, fragment, "result")
                .addToBackStack(null)
                .commit();
        // 同步底部导航栏视觉选中状态（不触发导航监听器，避免覆盖带参数的 Fragment）
        com.google.android.material.bottomnavigation.BottomNavigationView nav =
                requireActivity().findViewById(R.id.bottomNav);
        nav.getMenu().findItem(R.id.nav_result).setChecked(true);
    }

    private void applyScanBtnGreen() {
        int green = ContextCompat.getColor(requireContext(), R.color.statusFound);
        scanBtn.setTextColor(green);
        scanBtn.setStrokeColorResource(R.color.statusFound);
    }

    private void applyScanBtnRed() {
        int red = ContextCompat.getColor(requireContext(), R.color.colorReset);
        scanBtn.setTextColor(red);
        scanBtn.setStrokeColorResource(R.color.colorReset);
    }

    @SuppressWarnings("deprecation")
    private void initSound() {
        audioManager = (AudioManager) requireContext().getSystemService(android.content.Context.AUDIO_SERVICE);
        soundPool = new SoundPool(10, AudioManager.STREAM_MUSIC, 5);
        beepSoundId = soundPool.load(requireContext(), R.raw.barcodebeep, 1);
    }

    private void playBeep() {
        if (soundPool != null && audioManager != null) {
            float max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            float cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            float ratio = max > 0 ? cur / max : 0.5f;
            soundPool.play(beepSoundId, ratio, ratio, 1, 0, 1);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        app.rfid.registerCallback(rfidCallback);
        app.rfid.powerOn();
        if (app.rfid.isPowered()) {
            scanBtn.setEnabled(true);
        } else {
            scanBtn.setEnabled(false);
            deviceStatusTv.setText(getString(R.string.scan_device_status,
                    getString(R.string.scan_status_not_ready)));
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        app.rfid.stopInventory();
        scanBtn.setText(R.string.scan_start_inventory);
        applyScanBtnGreen();
        app.rfid.unregisterCallback(rfidCallback);
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }
}

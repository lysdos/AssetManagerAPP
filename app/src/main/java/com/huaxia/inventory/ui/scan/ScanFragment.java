package com.huaxia.inventory.ui.scan;

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
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.rfid.SdkRfidAdapter;
import com.huaxia.inventory.ui.result.ResultFragment;
import com.huaxia.inventory.ui.result.ResultTab;
import com.huaxia.inventory.ui.manual.ManualScanFragment;
import com.huaxia.inventory.ui.util.TagPills;
import com.huaxia.inventory.util.BeepPlayer;
import com.huaxia.inventory.util.DateUtil;
import com.huaxia.inventory.util.EpcNormalizer;

public class ScanFragment extends Fragment {

    private ScanViewModel vm;
    private MaterialButton scanBtn;
    private MaterialButton manualScanBtn;
    private MaterialButton clearTaskBtn;
    private TextView deviceStatusTv;
    private TextView inStockNumber, scannedNumber, anomalyNumber, extraNumber;
    private TextView inStockSub, scannedSub, anomalySub, extraSub;
    private TextView checkedCardTitle, checkedCardDesc, checkedCardSub;
    private TextView recentCardTitle, recentCardDesc;
    private LinearLayout recentTags;

    private final BeepPlayer beepPlayer = new BeepPlayer();

    private final SdkRfidAdapter.TagListener rfidCallback = (epcStr, tid, rssi) -> {
        // 卡上存的就是 16/17/24 位十进制固资编号, 直接归一化到 24 位规范
        String normalized = EpcNormalizer.normalize(epcStr);
        if (normalized == null) return;
        vm.onEpcScanned(normalized, tid, rssi);
        beepPlayer.play();
    };

    public void onTriggerPressed() {
        if (scanBtn == null || !scanBtn.isEnabled()) return;
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
        manualScanBtn = v.findViewById(R.id.manualScanBtn);
        clearTaskBtn = v.findViewById(R.id.clearTaskBtn);
        deviceStatusTv = v.findViewById(R.id.deviceStatusTv);

        // Bind stat card labels + sub texts (default values; will be updated when stats observe)
        bindStatCard(v, R.id.inStockStat, R.string.scan_stat_in_stock,
                R.color.statusFound, getString(R.string.scan_stat_sub_today, 0));
        bindStatCard(v, R.id.scannedStat, R.string.scan_stat_scanned,
                R.color.statScanned, "0%");
        bindStatCard(v, R.id.anomalyStat, R.string.scan_stat_anomaly,
                R.color.statusMissing, "需处理");
        bindStatCard(v, R.id.extraStat, R.string.scan_stat_extra,
                R.color.statusExtra, getString(R.string.scan_stat_extra_sub));

        inStockNumber = v.findViewById(R.id.inStockStat).findViewById(R.id.statNumberTv);
        scannedNumber = v.findViewById(R.id.scannedStat).findViewById(R.id.statNumberTv);
        anomalyNumber = v.findViewById(R.id.anomalyStat).findViewById(R.id.statNumberTv);
        extraNumber = v.findViewById(R.id.extraStat).findViewById(R.id.statNumberTv);
        inStockSub = v.findViewById(R.id.inStockStat).findViewById(R.id.statSubTv);
        scannedSub = v.findViewById(R.id.scannedStat).findViewById(R.id.statSubTv);
        anomalySub = v.findViewById(R.id.anomalyStat).findViewById(R.id.statSubTv);
        extraSub = v.findViewById(R.id.extraStat).findViewById(R.id.statSubTv);

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
        manualScanBtn.setOnClickListener(btn -> navigateToManualScan());
        clearTaskBtn.setOnClickListener(btn -> confirmClearTask());
        applyScanBtnGreen();

        // 点击统计卡片跳转结果页并筛选
        v.findViewById(R.id.inStockStat).setOnClickListener(
                card -> navigateToResult(ResultTab.ALL));
        v.findViewById(R.id.scannedStat).setOnClickListener(
                card -> navigateToResult(ResultTab.FOUND));
        v.findViewById(R.id.anomalyStat).setOnClickListener(
                card -> navigateToResult(ResultTab.MISSING));
        v.findViewById(R.id.extraStat).setOnClickListener(
                card -> navigateToResult(ResultTab.EXTRA));

        InventoryApp app = (InventoryApp) requireActivity().getApplication();

        // 扫描页统一订阅 latestScan: 有任务时 onEpcScanned 也会发它, 但 observer
        // 检测到有任务会直接返回(状态行留给设备状态); 无任务时即"读码模式",
        // 把最近读取的编码渲染到状态行.
        vm.latestScan().observe(getViewLifecycleOwner(), this::renderLatestScan);

        Long taskId = app.getSession().getCurrentTaskId();
        if (taskId == null) {
            // 没有当前任务 = 「读码模式」: 状态行显示读码提示, 扫到的编码由
            // latestScan observer 渲染; 统计区归零. 扫描按钮在 onResume 按硬件
            // 就绪态启用(读码同样需要能按).
            renderReaderIdle();
            scanBtn.setEnabled(false);
            renderEmptyStats();
            // 仍要订阅 clearResult, 这样从其他入口触发重置也能收到提示
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
            beepPlayer.init(requireContext(), R.raw.barcodebeep);
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
            extraNumber.setText(String.valueOf(stats.extra));
            extraSub.setText(stats.extra > 0 ? getString(R.string.scan_stat_extra_sub) : "OK");

            checkedCardSub.setText(getString(R.string.scan_info_checked_sub,
                    stats.lastScannedAt == 0 ? "—" : DateUtil.formatTimestamp(stats.lastScannedAt)));
            recentCardDesc.setText(getString(R.string.scan_info_recent_desc,
                    stats.found, stats.missing, stats.extra));
            renderResultTags(recentTags, stats);
        });

        deviceStatusTv.setText(getString(R.string.scan_device_status,
                app.getRfid().getState() == com.huaxia.inventory.rfid.RfidController.State.READY
                || app.getRfid().getState() == com.huaxia.inventory.rfid.RfidController.State.SCANNING
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

        beepPlayer.init(requireContext(), R.raw.barcodebeep);
    }

    private void confirmClearTask() {
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        Long taskId = app.getSession().getCurrentTaskId();
        if (taskId == null) {
            Snackbar.make(requireView(), R.string.scan_clear_task_failed,
                    Snackbar.LENGTH_SHORT).show();
            return;
        }
        long tid = taskId;
        app.getExecutors().diskIO.execute(() -> {
            int assets = app.getDb().assetDao().countByTask(tid);
            int scans = app.getDb().scanRecordDao().countByTask(tid);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
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

    /** 无当前任务时把 stats 区域显式刷成 0/—, 避免上一任务的残留值. */
    private void renderEmptyStats() {
        inStockNumber.setText("0");
        inStockSub.setText(getString(R.string.scan_stat_sub_today, 0));
        scannedNumber.setText("0");
        scannedSub.setText("0%");
        anomalyNumber.setText("0");
        anomalySub.setText("OK");
        extraNumber.setText("0");
        extraSub.setText("OK");
        checkedCardSub.setText(getString(R.string.scan_info_checked_sub, "—"));
        recentCardDesc.setText(getString(R.string.scan_info_recent_desc, 0, 0, 0));
        recentTags.removeAllViews();
    }

    /** 无任务「读码模式」: 硬件就绪时显示空闲提示, 未就绪时显示设备状态. */
    private void renderReaderIdle() {
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        boolean ready = app.getRfid().isPowered();
        deviceStatusTv.setText(ready ? getString(R.string.scan_reader_idle)
                : getString(R.string.scan_device_status,
                        getString(R.string.scan_status_not_ready)));
        deviceStatusTv.setTextColor(ContextCompat.getColor(requireContext(), R.color.textHint));
    }

    /**
     * 订阅 {@code latestScan}: 仅在无任务(读码模式)时把最近读取的编码渲染到状态行;
     * 有任务时状态行保留"设备状态", 不显示编码.
     */
    private void renderLatestScan(ScanRecord rec) {
        if (rec == null || deviceStatusTv == null) return;
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        if (app.getSession().getCurrentTaskId() != null) return;
        deviceStatusTv.setText(getString(R.string.scan_reader_code, rec.epc,
                rec.rssi == null ? "-" : rec.rssi));
        deviceStatusTv.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary));
    }

    private void toggleScan() {
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        boolean ok;
        if (app.getRfid().isActuallyScanning()) {
            ok = app.getRfid().stopInventory();
        } else {
            ok = app.getRfid().startInventory();
        }
        if (!ok) {
            Snackbar.make(requireView(), R.string.scan_start_failed,
                    Snackbar.LENGTH_SHORT).show();
        }
        updateScanButton();
    }

    /** Single source of truth for the scan button's appearance. */
    private void updateScanButton() {
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        if (app.getRfid().isActuallyScanning()) {
            scanBtn.setText(R.string.scan_stop);
            applyScanBtnRed();
        } else {
            scanBtn.setText(R.string.scan_start_inventory);
            applyScanBtnGreen();
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

    private void navigateToManualScan() {
        // 跟 navigateToResult 一样 addToBackStack, 让用户能用返回键回到扫描页;
        // 回到扫描页时 onResume 会调 vm.refreshStats(), 把手动盘点写进去的 ScanRecord 反映到统计卡上.
        requireActivity().getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, new ManualScanFragment(), "manual_scan")
                .addToBackStack(null)
                .commit();
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

    @Override
    public void onResume() {
        super.onResume();
        // 从手动盘点页返回时, 该页面写过 ScanRecord 但 stats observer 不会自动重发,
        // 主动 refresh 一次把已盘点/盘盈数刷成最新. bindToTask 也调 recomputeStats,
        // 但 taskId 不变时不会重跑.
        if (vm != null) vm.refreshStats();
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        app.getRfid().registerCallback(rfidCallback);
        app.getRfid().powerOn();
        if (app.getRfid().isPowered()) {
            scanBtn.setEnabled(true);
            if (app.getSession().getCurrentTaskId() == null) {
                // 读码模式: 复位空闲提示 (会刷掉上一轮读到的编码, 属预期)
                renderReaderIdle();
            }
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
        app.getRfid().stopInventory();
        scanBtn.setText(R.string.scan_start_inventory);
        applyScanBtnGreen();
        app.getRfid().unregisterCallback(rfidCallback);
        // beepPlayer 的 release 调用移到 onDestroyView — 旋转屏幕后回到此 Fragment
        // 会触发 onPause → onDestroyView → 重新 onCreateView. 旧 release 路径下:
        // 第二次进 onViewCreated 时 soundPool != null 走 no-op, 但 SoundPool 已被
        // release — play() 静默. 现在的方案: init 在 onViewCreated, release 在
        // onDestroyView, 让 SoundPool 生命周期跟 View 一致.
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 释放 SoundPool 等与 View 关联的本地资源. 注意: rfid 句柄与 beepPlayer
        // 一致都在 onDestroyView 清理 — rfid 在 Application 级别, release 由
        // ScanViewModel/SdkRfidAdapter 自管, 这里不调.
        beepPlayer.release();
    }
}

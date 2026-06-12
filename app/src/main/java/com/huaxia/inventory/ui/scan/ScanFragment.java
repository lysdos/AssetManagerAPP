package com.huaxia.inventory.ui.scan;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.dao.CategoryCount;
import com.huaxia.inventory.rfid.SdkRfidAdapter;
import com.huaxia.inventory.ui.util.TagPills;
import com.huaxia.inventory.util.DateUtil;
import com.huaxia.inventory.util.EpcNormalizer;

public class ScanFragment extends Fragment {

    private ScanViewModel vm;
    private MaterialButton scanBtn;
    private TextView deviceStatusTv;
    private TextView inStockNumber, scannedNumber, anomalyNumber;
    private TextView inStockSub, scannedSub, anomalySub;
    private TextView inStockCardTitle, inStockCardDesc;
    private TextView checkedCardTitle, checkedCardDesc, checkedCardSub;
    private TextView recentCardTitle, recentCardDesc;
    private LinearLayout inStockTags, recentTags;

    private final SdkRfidAdapter.TagListener rfidCallback = (epcStr, tid, rssi) -> {
        String normalized = EpcNormalizer.normalize(epcStr);
        if (normalized == null) return;
        vm.onEpcScanned(normalized, tid, rssi);
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
        View inStockCard = v.findViewById(R.id.inStockCard);
        inStockCardTitle = inStockCard.findViewById(R.id.infoTitleTv);
        inStockCardDesc = inStockCard.findViewById(R.id.infoDescTv);
        inStockTags = inStockCard.findViewById(R.id.infoTagsContainer);
        inStockCardTitle.setText(R.string.scan_info_in_stock);
        inStockCardDesc.setText(R.string.scan_info_in_stock_desc);

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

            renderCategoryTags(inStockTags, stats.categories);
            checkedCardSub.setText(getString(R.string.scan_info_checked_sub,
                    stats.lastInventoryAt == 0 ? "—" : DateUtil.formatTimestamp(stats.lastInventoryAt)));
            recentCardDesc.setText(getString(R.string.scan_info_recent_desc,
                    stats.found, stats.missing, stats.extra));
            renderResultTags(recentTags, stats);
        });

        deviceStatusTv.setText(getString(R.string.scan_device_status,
                app.rfid.getState() == com.huaxia.inventory.rfid.RfidController.State.READY
                || app.rfid.getState() == com.huaxia.inventory.rfid.RfidController.State.SCANNING
                ? getString(R.string.scan_status_ready)
                : getString(R.string.scan_status_not_ready)));
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

    private void renderCategoryTags(LinearLayout container, java.util.List<CategoryCount> cats) {
        container.removeAllViews();
        if (cats == null || cats.isEmpty()) return;
        for (CategoryCount c : cats) {
            TagPills.bind(container,
                    getString(R.string.tag_format_count, c.category, c.count),
                    R.drawable.bg_tag_blue,
                    R.color.tagBlueFg);
        }
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
        if (app.rfid.isPowered()) {
            if (app.rfid.getState() == com.huaxia.inventory.rfid.RfidController.State.READY) {
                app.rfid.startInventory();
                scanBtn.setText(R.string.scan_stop);
            }
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
        app.rfid.unregisterCallback(rfidCallback);
    }
}

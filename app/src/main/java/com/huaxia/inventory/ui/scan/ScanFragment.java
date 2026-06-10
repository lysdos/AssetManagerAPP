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

public class ScanFragment extends Fragment {

    private ScanViewModel vm;
    private MaterialButton scanBtn;
    private TextView expectedTv, scannedTv, deviceStatusTv;
    private RecyclerView recentRv;
    private FloatingActionButton viewResultFab;
    private RecentScanAdapter adapter;

    private final IRfidCallback rfidCallback = new IRfidCallback() {
        @Override public void onInventoryTag(String epc, String tid, String rssi) {
            vm.onEpcScanned(epc, tid, rssi);
        }
        @Override public void onInventoryTagEnd() {}
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
        if (app.rfid.isPowered()) {
            if (app.rfid.getState() == com.huaxia.inventory.rfid.RfidController.State.READY) {
                app.rfid.startInventory();
                scanBtn.setText(R.string.scan_stop);
            }
            scanBtn.setEnabled(true);
        } else {
            scanBtn.setEnabled(false);
            deviceStatusTv.setText(R.string.scan_status_not_ready);
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

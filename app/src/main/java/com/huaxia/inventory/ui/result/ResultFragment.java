package com.huaxia.inventory.ui.result;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.excel.ExcelExporter;
import com.huaxia.inventory.util.DateUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ResultFragment extends Fragment {

    private ResultViewModel vm;
    private RecyclerView rv;
    private TabLayout tabs;
    private SearchView searchView;
    private MaterialButton exportBtn;
    private ActivityResultLauncher<String> exportLauncher;
    private Set<Long> matchedIdsCache = new HashSet<>();

    @Override
    public void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                uri -> { if (uri != null) doExport(uri); });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_result, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);
        vm = new ViewModelProvider(this).get(ResultViewModel.class);
        rv = v.findViewById(R.id.resultRv);
        tabs = v.findViewById(R.id.tabs);
        searchView = v.findViewById(R.id.searchView);
        exportBtn = v.findViewById(R.id.exportBtn);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));

        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        Long taskId = app.session.getCurrentTaskId();
        if (taskId == null) {
            exportBtn.setEnabled(false);
            return;
        }
        vm.bindToTask(taskId);

        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                ResultTab[] map = {ResultTab.ALL, ResultTab.FOUND, ResultTab.MISSING, ResultTab.EXTRA};
                vm.setTab(map[tab.getPosition()]);
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String q) { vm.setSearchQuery(q); return true; }
            @Override public boolean onQueryTextChange(String newText) { vm.setSearchQuery(newText); return true; }
        });

        exportBtn.setOnClickListener(btn -> {
            String fileName = "盘点结果_" + taskId + "_" +
                    DateUtil.formatTimestamp(System.currentTimeMillis())
                        .replace(" ", "_").replace(":", "")
                    + ".xlsx";
            exportLauncher.launch(fileName);
        });

        AssetResultAdapter initialAdapter =
                new AssetResultAdapter(new ArrayList<>(), matchedIdsCache);
        rv.setAdapter(initialAdapter);

        vm.assets().observe(getViewLifecycleOwner(), assets -> {
            if (assets == null) return;
            // Refresh matched-ids cache for this task
            InventoryApp a = (InventoryApp) requireActivity().getApplication();
            long tid = taskId;
            a.executors.diskIO.execute(() -> {
                Set<Long> matched = new HashSet<>();
                for (Asset as : a.db.assetDao().findFound(tid)) matched.add(as.id);
                matchedIdsCache = matched;
                requireActivity().runOnUiThread(() ->
                    rv.setAdapter(new AssetResultAdapter(assets, matched)));
            });
        });
    }

    private void doExport(Uri uri) {
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        long taskId = app.session.getCurrentTaskId();
        app.executors.excelIO.execute(() -> {
            try {
                InventoryTask task = app.db.inventoryTaskDao().findById(taskId);
                List<Asset> assets = app.db.assetDao().findAllByTask(taskId);
                List<ScanRecord> scans = app.db.scanRecordDao().findAllByTask(taskId);
                Set<Long> matchedIds = new HashSet<>();
                for (Asset a : app.db.assetDao().findFound(taskId)) matchedIds.add(a.id);

                // Reconstruct original 35-column header order from the first asset's rawRowJson
                List<String> originalHeaders = new ArrayList<>();
                if (!assets.isEmpty() && assets.get(0).rawRowJson != null) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper =
                            new com.fasterxml.jackson.databind.ObjectMapper();
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, String> raw = mapper.readValue(
                            assets.get(0).rawRowJson, java.util.Map.class);
                    originalHeaders.addAll(raw.keySet());
                }

                try (java.io.OutputStream out =
                        requireContext().getContentResolver().openOutputStream(uri)) {
                    ExcelExporter.export(out, task.sourceFileName, originalHeaders,
                            assets, scans, matchedIds::contains);
                }
                requireActivity().runOnUiThread(() ->
                    android.widget.Toast.makeText(requireContext(), "导出完成",
                            android.widget.Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                requireActivity().runOnUiThread(() ->
                    android.widget.Toast.makeText(requireContext(), "导出失败: " + e.getMessage(),
                            android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }
}

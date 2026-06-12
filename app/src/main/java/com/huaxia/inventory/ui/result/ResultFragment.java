package com.huaxia.inventory.ui.result;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
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
    private View emptyTv;
    private TextView paginationInfoTv;
    private MaterialButton prevBtn, nextBtn;
    private TextView allNumber, foundNumber, missingNumber, extraNumber;

    private final Set<Long> matchedIdsCache = new HashSet<>();
    private ActivityResultLauncher<String> exportLauncher;
    private long currentTaskId = -1L;

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
        emptyTv = v.findViewById(R.id.emptyTv);
        paginationInfoTv = v.findViewById(R.id.paginationInfoTv);
        prevBtn = v.findViewById(R.id.prevBtn);
        nextBtn = v.findViewById(R.id.nextBtn);

        rv.setLayoutManager(new LinearLayoutManager(requireContext()));

        allNumber = v.findViewById(R.id.allStat).findViewById(R.id.statNumberTv);
        foundNumber = v.findViewById(R.id.foundStat).findViewById(R.id.statNumberTv);
        missingNumber = v.findViewById(R.id.missingStat).findViewById(R.id.statNumberTv);
        extraNumber = v.findViewById(R.id.extraStat).findViewById(R.id.statNumberTv);
        setStatLabel(v.findViewById(R.id.allStat), R.string.result_stat_all);
        setStatLabel(v.findViewById(R.id.foundStat), R.string.result_stat_found);
        setStatLabel(v.findViewById(R.id.missingStat), R.string.result_stat_missing);
        setStatLabel(v.findViewById(R.id.extraStat), R.string.result_stat_extra);
        setStatNumberColor(allNumber, R.color.statTotal);
        setStatNumberColor(foundNumber, R.color.statusFound);
        setStatNumberColor(missingNumber, R.color.statusMissing);
        setStatNumberColor(extraNumber, R.color.statusExtra);

        bindStatClick(v.findViewById(R.id.allStat), ResultTab.ALL);
        bindStatClick(v.findViewById(R.id.foundStat), ResultTab.FOUND);
        bindStatClick(v.findViewById(R.id.missingStat), ResultTab.MISSING);
        bindStatClick(v.findViewById(R.id.extraStat), ResultTab.EXTRA);

        prevBtn.setOnClickListener(btn -> vm.prevPage());
        nextBtn.setOnClickListener(btn -> vm.nextPage());

        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        Long taskId = app.session.getCurrentTaskId();
        if (taskId == null) {
            emptyTv.setVisibility(View.VISIBLE);
            rv.setVisibility(View.GONE);
            paginationInfoTv.setText("");
            return;
        }
        currentTaskId = taskId;
        vm.bindToTask(taskId);

        vm.stats().observe(getViewLifecycleOwner(), stats -> {
            allNumber.setText(String.valueOf(stats.total));
            foundNumber.setText(String.valueOf(stats.found));
            missingNumber.setText(String.valueOf(stats.missing));
            extraNumber.setText(String.valueOf(stats.extra));
        });

        vm.currentPage().observe(getViewLifecycleOwner(), page -> {
            int p = page == null ? 0 : page;
            int total = filterCount();
            int from = total == 0 ? 0 : p * ResultViewModel.PAGE_SIZE + 1;
            int to = Math.min((p + 1) * ResultViewModel.PAGE_SIZE, total);
            paginationInfoTv.setText(getString(R.string.result_pagination, total, from, to));
            prevBtn.setEnabled(p > 0);
            nextBtn.setEnabled((p + 1) * ResultViewModel.PAGE_SIZE < total);
        });

        vm.assets().observe(getViewLifecycleOwner(), assets -> {
            if (assets == null) return;
            ResultTab tab = vm.activeTab().getValue();
            if (tab == ResultTab.EXTRA) {
                // extras observer handles EXTRA mode; skip
                return;
            }
            if (assets.isEmpty()) {
                emptyTv.setVisibility(View.VISIBLE);
                rv.setVisibility(View.GONE);
            } else {
                emptyTv.setVisibility(View.GONE);
                rv.setVisibility(View.VISIBLE);
            }
            InventoryApp a = (InventoryApp) requireActivity().getApplication();
            a.executors.diskIO.execute(() -> {
                Set<Long> matched = new HashSet<>();
                for (Asset as : a.db.assetDao().findFound(currentTaskId)) matched.add(as.id);
                matchedIdsCache.clear();
                matchedIdsCache.addAll(matched);
                requireActivity().runOnUiThread(() ->
                    rv.setAdapter(new AssetResultAdapter(assets, matchedIdsCache)));
            });
        });

        vm.extras().observe(getViewLifecycleOwner(), extras -> {
            if (extras == null) return;
            ResultTab tab = vm.activeTab().getValue();
            if (tab != ResultTab.EXTRA) {
                return; // not in EXTRA mode, ignore
            }
            if (extras.isEmpty()) {
                emptyTv.setVisibility(View.VISIBLE);
                rv.setVisibility(View.GONE);
            } else {
                emptyTv.setVisibility(View.GONE);
                rv.setVisibility(View.VISIBLE);
                rv.setAdapter(new ExtraResultAdapter(extras));
            }
        });
    }

    private int filterCount() {
        ResultViewModel.Stats s = vm.stats().getValue();
        if (s == null) return 0;
        ResultTab tab = vm.activeTab().getValue();
        if (tab == null) return s.total;
        switch (tab) {
            case FOUND:   return s.found;
            case MISSING: return s.missing;
            case EXTRA:   return s.extra;
            default:      return s.total;
        }
    }

    private void setStatLabel(View card, int labelRes) {
        ((TextView) card.findViewById(R.id.statLabelTv)).setText(labelRes);
    }

    private void setStatNumberColor(TextView tv, int colorRes) {
        tv.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), colorRes));
    }

    private void bindStatClick(View card, ResultTab tab) {
        card.setOnClickListener(v -> vm.setTab(tab));
    }

    public void onExportClicked() {
        if (currentTaskId == -1L) return;
        String fileName = "盘点结果_" + currentTaskId + "_" +
                DateUtil.formatTimestamp(System.currentTimeMillis())
                    .replace(" ", "_").replace(":", "")
                + ".xlsx";
        exportLauncher.launch(fileName);
    }

    private void doExport(Uri uri) {
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        long taskId = currentTaskId;
        app.executors.excelIO.execute(() -> {
            try {
                InventoryTask task = app.db.inventoryTaskDao().findById(taskId);
                List<Asset> assets = app.db.assetDao().findAllByTask(taskId);
                List<ScanRecord> scans = app.db.scanRecordDao().findAllByTask(taskId);
                Set<Long> matchedIds = new HashSet<>();
                for (Asset a : app.db.assetDao().findFound(taskId)) matchedIds.add(a.id);

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
                    Snackbar.make(requireView(), R.string.msg_export_done,
                            Snackbar.LENGTH_SHORT).show());
            } catch (Exception e) {
                requireActivity().runOnUiThread(() ->
                    Snackbar.make(requireView(),
                        getString(R.string.err_export_failed, e.getMessage()),
                        Snackbar.LENGTH_LONG).show());
            }
        });
    }
}

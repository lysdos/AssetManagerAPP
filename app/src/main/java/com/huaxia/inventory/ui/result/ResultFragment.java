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
import com.huaxia.inventory.data.entity.ExtraClaim;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.excel.ExcelExporter;
import com.huaxia.inventory.util.DateUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ResultFragment extends Fragment {

    public static final String ARG_TAB = "tab";

    private ResultViewModel vm;
    private RecyclerView rv;
    private View emptyTv;
    private TextView paginationInfoTv;
    private MaterialButton prevBtn, nextBtn;

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

        prevBtn.setOnClickListener(btn -> vm.prevPage());
        nextBtn.setOnClickListener(btn -> vm.nextPage());

        MaterialButton exportBtn = v.findViewById(R.id.exportBtn);
        exportBtn.setOnClickListener(btn -> onExportClicked());

        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        Long taskId = app.session.getCurrentTaskId();
        if (taskId == null) {
            emptyTv.setVisibility(View.VISIBLE);
            rv.setVisibility(View.GONE);
            paginationInfoTv.setText("");
            exportBtn.setEnabled(false);
            return;
        }
        currentTaskId = taskId;

        // 读取导航参数中的 tab，设置初始筛选
        if (getArguments() != null) {
            String tabName = getArguments().getString(ARG_TAB);
            if (tabName != null) {
                try {
                    vm.setTab(ResultTab.valueOf(tabName));
                } catch (IllegalArgumentException ignored) {}
            }
        }

        vm.bindToTask(taskId);

        vm.currentPage().observe(getViewLifecycleOwner(), page -> updatePagination());

        vm.stats().observe(getViewLifecycleOwner(), stats -> updatePagination());

        vm.assets().observe(getViewLifecycleOwner(), assets -> {
            if (assets == null) return;
            ResultTab tab = vm.activeTab().getValue();
            if (tab == ResultTab.EXTRA) {
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
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded() || rv == null) return;
                    rv.setAdapter(new AssetResultAdapter(assets, matchedIdsCache,
                        this::showAssetDetailDialog));
                });
            });
        });

        vm.extras().observe(getViewLifecycleOwner(), extras -> {
            if (extras == null) return;
            ResultTab tab = vm.activeTab().getValue();
            if (tab != ResultTab.EXTRA) {
                return;
            }
            if (extras.isEmpty()) {
                emptyTv.setVisibility(View.VISIBLE);
                rv.setVisibility(View.GONE);
            } else {
                emptyTv.setVisibility(View.GONE);
                rv.setVisibility(View.VISIBLE);
                rv.setAdapter(new ExtraResultAdapter(extras, this::showExtraDetailDialog));
            }
        });
    }

    private void updatePagination() {
        Integer page = vm.currentPage().getValue();
        int p = page == null ? 0 : page;
        int total = filterCount();
        int from = total == 0 ? 0 : p * ResultViewModel.PAGE_SIZE + 1;
        int to = Math.min((p + 1) * ResultViewModel.PAGE_SIZE, total);
        paginationInfoTv.setText(getString(R.string.result_pagination, total, from, to));
        prevBtn.setEnabled(p > 0);
        nextBtn.setEnabled((p + 1) * ResultViewModel.PAGE_SIZE < total);
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

                List<String> originalHeaders = new ArrayList<>();
                List<Asset> firstPage = app.db.assetDao().pageAll(taskId, 1, 0);
                if (!firstPage.isEmpty() && firstPage.get(0).rawRowJson != null) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper =
                            new com.fasterxml.jackson.databind.ObjectMapper();
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> raw = mapper.readValue(
                            firstPage.get(0).rawRowJson, java.util.Map.class);
                    // 源里的"盘点日期"是空占位列, 导出时由"盘点时间"取代, 这里过滤掉避免重复.
                    for (String k : raw.keySet()) {
                        if (!"盘点日期".equals(k)) originalHeaders.add(k);
                    }
                }

                try (java.io.OutputStream out =
                        requireContext().getContentResolver().openOutputStream(uri)) {
                    ExcelExporter.exportStreamed(out, task.sourceFileName, originalHeaders,
                            app.db.assetDao(), app.db.scanRecordDao(),
                            app.db.extraClaimDao(), taskId);
                }
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        View v = getView();
                        if (v != null) {
                            Snackbar.make(v, R.string.msg_export_done,
                                    Snackbar.LENGTH_SHORT).show();
                        }
                    });
                }
            } catch (Exception e) {
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        View v = getView();
                        if (v != null) {
                            Snackbar.make(v,
                                getString(R.string.err_export_failed, e.getMessage()),
                                Snackbar.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private void showAssetDetailDialog(Asset asset) {
        boolean isMatched = matchedIdsCache.contains(asset.id);
        AssetDetailDialog.show(this, asset, isMatched, this::onAssetDetailSaved);
    }

    private void onAssetDetailSaved(Asset updated) {
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        long taskId = currentTaskId;
        long assetId = updated.id;
        app.executors.diskIO.execute(() -> {
            // 盘点时间: 优先用该资产最早一次扫描时间, 否则用当前时间
            Long firstScanAt = app.db.scanRecordDao().findFirstScanTime(taskId, assetId);
            updated.inventoryTime = (firstScanAt != null && firstScanAt > 0)
                    ? firstScanAt
                    : System.currentTimeMillis();
            app.db.assetDao().update(updated);
            if (isAdded()) {
                requireActivity().runOnUiThread(() -> {
                    View v = getView();
                    if (v != null) {
                        Snackbar.make(v, R.string.msg_asset_saved,
                                Snackbar.LENGTH_SHORT).show();
                    }
                    vm.refresh();
                });
            }
        });
    }

    private void showExtraDetailDialog(ExtraWithClaim data) {
        ExtraDetailDialog.show(this, data, claim -> onExtraDetailSaved(data.scan.id, claim));
    }

    /**
     * 保存盘盈补全信息。
     * ScanRecord 行不动(matchedAssetId 保持 NULL, 盘盈状态保留);
     * ExtraClaim 通过 unique(scanRecordId) 实现 UPSERT — 同一行二次编辑会覆盖.
     */
    private void onExtraDetailSaved(long scanRecordId, ExtraClaim claim) {
        vm.saveExtraClaim(scanRecordId, claim, () -> {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                View v = getView();
                if (v != null) {
                    Snackbar.make(v, R.string.msg_extra_saved,
                            Snackbar.LENGTH_SHORT).show();
                }
                vm.refresh();
            });
        });
    }
}

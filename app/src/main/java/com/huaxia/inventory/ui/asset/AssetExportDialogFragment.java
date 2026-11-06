package com.huaxia.inventory.ui.asset;

import android.app.Dialog;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import com.huaxia.inventory.R;
import com.huaxia.inventory.util.DateUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 资产台账导出弹窗：全部资产 / 当前筛选结果 → SAF 另存为 .xlsx.
 *
 * <p>筛选口径来自 Activity 作用域的 {@link AssetOverviewViewModel}，与资产 tab
 * 当前关键词/部门/类型完全一致；没有筛选条件时隐藏「当前筛选结果」。</p>
 *
 * <p>筛选快照会写入 saved state：即使 SAF 选择器打开期间进程被杀，重建后也不会
 * 把“筛选导出”静默退化成全量导出。导出任务本身由 ViewModel 持有，旋转后新弹窗
 * 通过 {@link AssetOverviewViewModel#exportResult()} 继续接收结果。</p>
 */
public class AssetExportDialogFragment extends DialogFragment {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String STATE_PENDING_FILTERED = "pendingFiltered";
    private static final String STATE_PENDING_KEYWORD = "pendingKeyword";
    private static final String STATE_PENDING_DEPT = "pendingDept";
    private static final String STATE_PENDING_TYPE = "pendingType";

    private AssetOverviewViewModel vm;
    private ActivityResultLauncher<String> exportLauncher;
    private View allOption;
    private View filteredOption;
    private TextView allCountTv;
    private TextView filteredCountTv;
    private TextView filterDescTv;
    private TextView statusTv;

    private boolean exporting;
    private boolean pendingFiltered;
    private String pendingKeyword = "";
    @Nullable private String pendingDept;
    @Nullable private String pendingType;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 提前拿 Activity 作用域 VM：SAF 回调可能在 view 重建完成前到达。
        vm = new ViewModelProvider(requireActivity()).get(AssetOverviewViewModel.class);
        if (savedInstanceState != null) {
            pendingFiltered = savedInstanceState.getBoolean(STATE_PENDING_FILTERED, false);
            pendingKeyword = savedInstanceState.getString(STATE_PENDING_KEYWORD, "");
            pendingDept = savedInstanceState.getString(STATE_PENDING_DEPT);
            pendingType = savedInstanceState.getString(STATE_PENDING_TYPE);
        }
        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument(XLSX_MIME),
                uri -> {
                    if (uri == null) {
                        resetExporting();
                        return;
                    }
                    exportTo(uri);
                });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_PENDING_FILTERED, pendingFiltered);
        outState.putString(STATE_PENDING_KEYWORD, pendingKeyword);
        outState.putString(STATE_PENDING_DEPT, pendingDept);
        outState.putString(STATE_PENDING_TYPE, pendingType);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_export_assets, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        allOption = v.findViewById(R.id.exportAllOption);
        filteredOption = v.findViewById(R.id.exportFilteredOption);
        allCountTv = v.findViewById(R.id.exportAllCountTv);
        filteredCountTv = v.findViewById(R.id.exportFilteredCountTv);
        filterDescTv = v.findViewById(R.id.exportFilterDescTv);
        statusTv = v.findViewById(R.id.exportStatusTv);

        allOption.setOnClickListener(btn -> startExport(false));
        filteredOption.setOnClickListener(btn -> startExport(true));
        v.findViewById(R.id.exportCloseBtn).setOnClickListener(btn -> dismiss());
        v.findViewById(R.id.exportCancelBtn).setOnClickListener(btn -> dismiss());

        // ViewModel 可能在上一次弹窗关闭/旋转后仍持有导出任务；先恢复“导出中”UI。
        if (vm.isExportRunning()) {
            exporting = true;
            statusTv.setText(R.string.asset_export_in_progress);
            statusTv.setVisibility(View.VISIBLE);
            setOptionsEnabled(false);
        } else {
            vm.clearExportResult();
        }

        vm.exportResult().observe(getViewLifecycleOwner(), result -> {
            if (result == null) return;
            if (result.token != vm.currentExportToken()) return;
            vm.clearExportResult();
            exporting = false;
            if (!isAdded()) return;
            if (result.success) {
                Toast.makeText(requireContext(),
                        getString(R.string.asset_export_saved_fmt, result.count),
                        Toast.LENGTH_SHORT).show();
                // onSaveInstanceState 之后仍可能收到异步结果，用 state-loss 版本避免崩溃。
                dismissAllowingStateLoss();
            } else {
                resetExporting();
                Toast.makeText(requireContext(), R.string.asset_export_failed,
                        Toast.LENGTH_SHORT).show();
            }
        });

        vm.state().observe(getViewLifecycleOwner(), this::renderState);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 导出任务在 ViewModel 中继续；这里释放旧 View 树，避免回调/长任务持有。
        allOption = null;
        filteredOption = null;
        allCountTv = null;
        filteredCountTv = null;
        filterDescTv = null;
        statusTv = null;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }
    }

    private void renderState(@Nullable AssetOverviewViewModel.OverviewState s) {
        if (s == null) {
            allCountTv.setText(R.string.asset_export_count_loading);
            filteredOption.setVisibility(View.GONE);
            filterDescTv.setText(R.string.asset_export_filter_none);
            return;
        }

        allCountTv.setText(getString(R.string.asset_export_count_fmt, s.totalAll));
        boolean hasFilter = !s.keyword.isEmpty() || s.deptBucket != null || s.typeBucket != null;
        filterDescTv.setText(buildFilterDesc(s));

        // 无筛选，或筛选后条数与全量相同：不展示重复的导出项（与 HTML mockup 一致）
        boolean showFiltered = hasFilter && s.filteredTotal != s.totalAll;
        filteredOption.setVisibility(showFiltered ? View.VISIBLE : View.GONE);
        if (showFiltered) {
            filteredCountTv.setText(getString(R.string.asset_export_count_fmt, s.filteredTotal));
        }
        if (!exporting) {
            setOptionsEnabled(true);
        }
    }

    private String buildFilterDesc(@NonNull AssetOverviewViewModel.OverviewState s) {
        List<String> parts = new ArrayList<>();
        if (!s.keyword.isEmpty()) {
            parts.add(getString(R.string.asset_export_filter_keyword_fmt, s.keyword));
        }
        if (s.deptBucket != null) {
            String dept = s.deptBucket.isEmpty()
                    ? getString(R.string.asset_dept_unassigned) : s.deptBucket;
            parts.add(getString(R.string.asset_export_filter_dept_fmt, dept));
        }
        if (s.typeBucket != null) {
            String type = s.typeBucket.isEmpty()
                    ? getString(R.string.asset_type_unassigned) : s.typeBucket;
            parts.add(getString(R.string.asset_export_filter_type_fmt, type));
        }
        if (parts.isEmpty()) {
            return getString(R.string.asset_export_filter_none);
        }
        String joined = TextUtils.join(getString(R.string.asset_export_filter_join), parts);
        return getString(R.string.asset_export_filter_prefix_fmt, joined);
    }

    private void startExport(boolean filtered) {
        if (exporting || vm.isExportRunning()) return;
        AssetOverviewViewModel.OverviewState s = vm.state().getValue();
        if (s == null) {
            Toast.makeText(requireContext(), R.string.asset_export_count_loading,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        int count = filtered ? s.filteredTotal : s.totalAll;
        if (count <= 0) {
            Toast.makeText(requireContext(), R.string.asset_export_no_assets,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        exporting = true;
        pendingFiltered = filtered;
        pendingKeyword = filtered ? s.keyword : "";
        pendingDept = filtered ? s.deptBucket : null;
        pendingType = filtered ? s.typeBucket : null;
        setOptionsEnabled(false);
        statusTv.setText(R.string.asset_export_in_progress);
        statusTv.setVisibility(View.VISIBLE);

        String date = DateUtil.formatTimestamp(System.currentTimeMillis());
        date = date.substring(0, 10).replace("-", "");
        String fileName = getString(filtered
                ? R.string.asset_export_file_filtered_fmt
                : R.string.asset_export_file_all_fmt, date);
        try {
            exportLauncher.launch(fileName);
        } catch (Exception e) {
            resetExporting();
            Toast.makeText(requireContext(), R.string.asset_export_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void exportTo(@NonNull Uri uri) {
        // 旋转/进程重建后 view 可能还没就绪，但导出本身仍应继续。
        if (vm == null) return;
        if (allOption != null) {
            exporting = true;
            setOptionsEnabled(false);
            if (statusTv != null) {
                statusTv.setText(R.string.asset_export_in_progress);
                statusTv.setVisibility(View.VISIBLE);
            }
        }
        vm.exportLedger(pendingFiltered, pendingKeyword, pendingDept, pendingType, uri);
    }

    private void resetExporting() {
        exporting = false;
        if (statusTv != null) {
            statusTv.setVisibility(View.GONE);
        }
        if (allOption != null) {
            setOptionsEnabled(true);
        }
        if (vm != null && allOption != null) {
            renderState(vm.state().getValue());
        }
    }

    private void setOptionsEnabled(boolean enabled) {
        if (allOption == null) return;
        allOption.setEnabled(enabled);
        allOption.setAlpha(enabled ? 1f : 0.5f);
        if (filteredOption != null && filteredOption.getVisibility() == View.VISIBLE) {
            filteredOption.setEnabled(enabled);
            filteredOption.setAlpha(enabled ? 1f : 0.5f);
        }
    }
}

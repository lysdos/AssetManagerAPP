package com.huaxia.inventory.ui.asset;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.dao.CategoryCount;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.ui.result.AssetDetailDialog;
import com.huaxia.inventory.ui.util.LifecycleDialogs;
import com.huaxia.inventory.util.EpcNormalizer;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 资产 tab: 全量资产台账总览 (库存池 + 所有任务内资产).
 *
 * <p>对齐 资产管理系统HTML界面.html 的资产总览页: hero 总数、部门分布 Top8、
 * 类型 chips Top10、关键词搜索、筛选标签、20 条/页列表、编辑弹窗.</p>
 *
 * <p>ViewModel 是 Activity 作用域的, 切 tab 再回来保留搜索/筛选/页码/类型展开状态.</p>
 */
public class AssetOverviewFragment extends Fragment {

    private AssetOverviewViewModel vm;

    private ScrollView scrollView;
    private TextView heroNumTv, deptHintTv, deptEmptyTv, typeCountTv, typeEmptyTv;
    private TextView listCountTv, emptyTv, pageInfoTv;
    private LinearLayout deptBarsContainer, listContainer, pagerRow;
    private MaterialButton deptMoreBtn, typeToggleBtn, prevBtn, nextBtn;
    private ChipGroup typeChipGroup, filterChipGroup;
    private EditText searchEt;
    private View listCard;

    private boolean updatingSearch;
    private boolean assetLoading;
    private List<CategoryCount> lastDeptBuckets = Collections.emptyList();
    @Nullable private String lastDeptBucket;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_asset_overview, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        vm = new ViewModelProvider(requireActivity()).get(AssetOverviewViewModel.class);

        scrollView = v.findViewById(R.id.assetScrollView);
        heroNumTv = v.findViewById(R.id.assetHeroNumTv);
        deptHintTv = v.findViewById(R.id.assetDeptHintTv);
        deptBarsContainer = v.findViewById(R.id.assetDeptBarsContainer);
        deptEmptyTv = v.findViewById(R.id.assetDeptEmptyTv);
        deptMoreBtn = v.findViewById(R.id.assetDeptMoreBtn);
        typeCountTv = v.findViewById(R.id.assetTypeCountTv);
        typeChipGroup = v.findViewById(R.id.assetTypeChipGroup);
        typeToggleBtn = v.findViewById(R.id.assetTypeToggleBtn);
        typeEmptyTv = v.findViewById(R.id.assetTypeEmptyTv);
        listCountTv = v.findViewById(R.id.assetListCountTv);
        searchEt = v.findViewById(R.id.assetSearchEt);
        filterChipGroup = v.findViewById(R.id.assetFilterChipGroup);
        listContainer = v.findViewById(R.id.assetListContainer);
        emptyTv = v.findViewById(R.id.assetEmptyTv);
        pagerRow = v.findViewById(R.id.assetPagerRow);
        pageInfoTv = v.findViewById(R.id.assetPageInfoTv);
        prevBtn = v.findViewById(R.id.assetPrevBtn);
        nextBtn = v.findViewById(R.id.assetNextBtn);
        listCard = v.findViewById(R.id.assetListCard);
        if (listCard == null) listCard = searchEt;

        prevBtn.setOnClickListener(btn -> {
            vm.prevPage();
            scrollView.fullScroll(View.FOCUS_UP);
        });
        nextBtn.setOnClickListener(btn -> {
            vm.nextPage();
            scrollView.fullScroll(View.FOCUS_UP);
        });
        deptMoreBtn.setOnClickListener(btn -> showDeptDialog());
        typeToggleBtn.setOnClickListener(btn -> vm.toggleTypeExpanded());

        // 恢复旋转/切 tab 后的搜索词 (ViewModel 保留状态; EditText 是 View 状态)
        AssetOverviewViewModel.OverviewState initial = vm.state().getValue();
        if (initial != null && !initial.keyword.isEmpty()) {
            updatingSearch = true;
            searchEt.setText(initial.keyword);
            searchEt.setSelection(initial.keyword.length());
            updatingSearch = false;
        }
        searchEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (updatingSearch) return;
                vm.setKeyword(s == null ? "" : s.toString());
            }
        });

        vm.state().observe(getViewLifecycleOwner(), this::render);
    }

    private void render(@NonNull AssetOverviewViewModel.OverviewState s) {
        heroNumTv.setText(NumberFormat.getIntegerInstance(Locale.CHINA).format(s.totalAll));
        boolean hasFilter = !s.keyword.isEmpty() || s.deptBucket != null || s.typeBucket != null;
        deptHintTv.setText(hasFilter
                ? R.string.asset_dept_hint_filtered : R.string.asset_dept_hint_default);

        renderDeptBars(s);
        renderTypeChips(s);
        renderFilterChips(s);
        renderList(s);
        renderPager(s);
    }

    private void renderDeptBars(@NonNull AssetOverviewViewModel.OverviewState s) {
        deptBarsContainer.removeAllViews();
        lastDeptBuckets = s.deptBuckets;
        lastDeptBucket = s.deptBucket;

        if (s.deptBuckets.isEmpty()) {
            deptEmptyTv.setVisibility(View.VISIBLE);
            deptMoreBtn.setVisibility(View.GONE);
            return;
        }
        deptEmptyTv.setVisibility(View.GONE);

        int total = 0;
        int max = 0;
        for (CategoryCount c : s.deptBuckets) {
            total += c.count;
            if (c.count > max) max = c.count;
        }

        List<CategoryCount> top = new ArrayList<>();
        List<CategoryCount> shown = s.deptBuckets.size() > 8
                ? s.deptBuckets.subList(0, 8) : s.deptBuckets;
        // 当前筛中的部门不在 Top8 时单独补一行, 避免"筛了但看不到"
        if (s.deptBucket != null && !containsBucket(shown, s.deptBucket)) {
            CategoryCount selected = findBucket(s.deptBuckets, s.deptBucket);
            top.add(selected != null ? selected : new CategoryCount(s.deptBucket, 0));
        }
        top.addAll(shown);

        for (CategoryCount c : top) {
            addDeptBar(c, total, max,
                    Objects.equals(s.deptBucket, c.category),
                    s.deptBucket != null && !containsBucket(shown, s.deptBucket)
                            && Objects.equals(s.deptBucket, c.category));
        }

        if (s.deptBuckets.size() > 8) {
            deptMoreBtn.setVisibility(View.VISIBLE);
            deptMoreBtn.setText(getString(R.string.asset_dept_more_fmt, s.deptBuckets.size()));
        } else {
            deptMoreBtn.setVisibility(View.GONE);
        }
    }

    private void addDeptBar(CategoryCount bucket, int total, int max,
                            boolean active, boolean prepended) {
        View row = LayoutInflater.from(requireContext())
                .inflate(R.layout.view_asset_dept_bar, deptBarsContainer, false);
        TextView nameTv = row.findViewById(R.id.barDeptNameTv);
        TextView valueTv = row.findViewById(R.id.barDeptValueTv);
        View fill = row.findViewById(R.id.barDeptFill);
        View remainder = row.findViewById(R.id.barDeptRemainder);

        String label = bucket.category.isEmpty()
                ? getString(R.string.asset_dept_unassigned) : bucket.category;
        if (prepended && active) {
            label = label + getString(R.string.asset_current_filter_marker);
        }
        nameTv.setText(label);
        int pct = total == 0 ? 0 : Math.round(100f * bucket.count / total);
        valueTv.setText(getString(R.string.asset_dept_row_count_fmt, bucket.count, pct));

        if (active) {
            int primary = ContextCompat.getColor(requireContext(), R.color.colorPrimary);
            nameTv.setTextColor(primary);
            valueTv.setTextColor(primary);
            nameTv.setTypeface(null, Typeface.BOLD);
            valueTv.setTypeface(null, Typeface.BOLD);
            fill.setBackgroundResource(R.drawable.bg_bar_fill_active);
        } else {
            nameTv.setTextColor(ContextCompat.getColor(requireContext(), R.color.assetBarLabel));
            valueTv.setTextColor(ContextCompat.getColor(requireContext(), R.color.textHint));
            nameTv.setTypeface(null, Typeface.NORMAL);
            valueTv.setTypeface(null, Typeface.NORMAL);
            fill.setBackgroundResource(R.drawable.bg_bar_fill);
        }

        LinearLayout.LayoutParams fillLp = (LinearLayout.LayoutParams) fill.getLayoutParams();
        fillLp.weight = bucket.count;
        fill.setLayoutParams(fillLp);
        LinearLayout.LayoutParams remLp = (LinearLayout.LayoutParams) remainder.getLayoutParams();
        remLp.weight = Math.max(0, max - bucket.count);
        remainder.setLayoutParams(remLp);

        row.setOnClickListener(v -> vm.toggleDeptBucket(bucket.category));
        deptBarsContainer.addView(row);
    }

    private void renderTypeChips(@NonNull AssetOverviewViewModel.OverviewState s) {
        typeChipGroup.removeAllViews();

        List<CategoryCount> all = new ArrayList<>(s.typeBuckets);
        int typeTotal = all.size();
        if (s.typeBucket != null && !containsBucket(all, s.typeBucket)) {
            all.add(0, new CategoryCount(s.typeBucket, 0));
        }
        // 表头统计真实类型数, 不计入为"可取消选中"补的 0 计数占位 chip
        typeCountTv.setText(getString(R.string.asset_type_count_fmt, typeTotal));
        if (all.isEmpty()) {
            typeEmptyTv.setVisibility(View.VISIBLE);
            typeToggleBtn.setVisibility(View.GONE);
            return;
        }
        typeEmptyTv.setVisibility(View.GONE);

        int limit = s.typeExpanded ? all.size() : Math.min(10, all.size());
        List<CategoryCount> shown = new ArrayList<>(all.subList(0, limit));
        // 已选类型在 Top10 之外时也保留, 否则用户只能靠筛选标签取消
        if (!s.typeExpanded && s.typeBucket != null && !containsBucket(shown, s.typeBucket)) {
            CategoryCount selected = findBucket(all, s.typeBucket);
            if (selected != null) shown.add(selected);
        }

        for (CategoryCount c : shown) {
            Chip chip = (Chip) LayoutInflater.from(requireContext())
                    .inflate(R.layout.view_asset_type_chip, typeChipGroup, false);
            chip.setId(View.generateViewId());
            String label = c.category.isEmpty()
                    ? getString(R.string.asset_type_unassigned) : c.category;
            chip.setText(getString(R.string.asset_type_chip_fmt,
                    AssetIcons.forType(c.category), label, c.count));
            chip.setChecked(Objects.equals(s.typeBucket, c.category));
            chip.setOnClickListener(v -> vm.toggleTypeBucket(c.category));
            typeChipGroup.addView(chip);
        }

        if (all.size() > 10) {
            typeToggleBtn.setVisibility(View.VISIBLE);
            typeToggleBtn.setText(s.typeExpanded
                    ? getString(R.string.asset_type_collapse)
                    : getString(R.string.asset_type_expand_fmt, all.size()));
        } else {
            typeToggleBtn.setVisibility(View.GONE);
        }
    }

    private void renderFilterChips(@NonNull AssetOverviewViewModel.OverviewState s) {
        filterChipGroup.removeAllViews();
        boolean hasAny = !s.keyword.isEmpty() || s.deptBucket != null || s.typeBucket != null;
        filterChipGroup.setVisibility(hasAny ? View.VISIBLE : View.GONE);
        if (!hasAny) return;

        int count = 0;
        if (!s.keyword.isEmpty()) {
            addFilterChip(getString(R.string.asset_filter_keyword_fmt, s.keyword),
                    () -> clearKeyword());
            count++;
        }
        if (s.deptBucket != null) {
            addFilterChip(getString(R.string.asset_filter_dept_fmt,
                            s.deptBucket.isEmpty()
                                    ? getString(R.string.asset_dept_unassigned) : s.deptBucket),
                    () -> vm.toggleDeptBucket(s.deptBucket));
            count++;
        }
        if (s.typeBucket != null) {
            addFilterChip(getString(R.string.asset_filter_type_fmt,
                            s.typeBucket.isEmpty()
                                    ? getString(R.string.asset_type_unassigned) : s.typeBucket),
                    () -> vm.toggleTypeBucket(s.typeBucket));
            count++;
        }
        if (count > 1) {
            Chip clear = (Chip) LayoutInflater.from(requireContext())
                    .inflate(R.layout.view_asset_filter_chip, filterChipGroup, false);
            clear.setText(R.string.asset_filter_clear_all);
            clear.setCloseIconVisible(false);
            clear.setCheckable(false);
            clear.setOnClickListener(v -> clearAllFilters());
            filterChipGroup.addView(clear);
        }
    }

    private void addFilterChip(CharSequence text, Runnable onClose) {
        Chip chip = (Chip) LayoutInflater.from(requireContext())
                .inflate(R.layout.view_asset_filter_chip, filterChipGroup, false);
        chip.setText(text);
        chip.setCheckable(false);
        chip.setCloseIconVisible(true);
        chip.setOnCloseIconClickListener(v -> onClose.run());
        filterChipGroup.addView(chip);
    }

    private void renderList(@NonNull AssetOverviewViewModel.OverviewState s) {
        listContainer.removeAllViews();
        listCountTv.setText(getString(R.string.asset_list_count_fmt, s.filteredTotal));

        if (s.filteredTotal == 0) {
            emptyTv.setVisibility(View.VISIBLE);
            emptyTv.setText(s.totalAll == 0
                    ? R.string.asset_empty_none : R.string.asset_empty_filtered);
            return;
        }
        emptyTv.setVisibility(View.GONE);

        for (Asset a : s.assets) {
            View row = LayoutInflater.from(requireContext())
                    .inflate(R.layout.view_asset_overview_row, listContainer, false);
            TextView iconTv = row.findViewById(R.id.assetRowIconTv);
            TextView titleTv = row.findViewById(R.id.assetRowTitleTv);
            TextView metaTv = row.findViewById(R.id.assetRowMetaTv);

            iconTv.setText(AssetIcons.forType(a.assetType));
            titleTv.setText(isBlank(a.assetModel) ? "—" : a.assetModel);
            String type = isBlank(a.assetType)
                    ? getString(R.string.asset_type_unassigned) : a.assetType;
            String epc = a.epc == null ? "" : EpcNormalizer.toDisplayForm(a.epc);
            StringBuilder meta = new StringBuilder(epc).append(" · ").append(type);
            if (!isBlank(a.dept)) meta.append(" · ").append(a.dept);
            metaTv.setText(meta);

            row.setOnClickListener(v -> openAsset(a.id));
            listContainer.addView(row);
        }
    }

    private void renderPager(@NonNull AssetOverviewViewModel.OverviewState s) {
        if (s.filteredTotal == 0) {
            pagerRow.setVisibility(View.GONE);
            return;
        }
        pagerRow.setVisibility(View.VISIBLE);
        pageInfoTv.setText(getString(R.string.asset_pager_info,
                s.page + 1, s.pageCount, AssetOverviewViewModel.PAGE_SIZE));
        prevBtn.setEnabled(s.page > 0);
        nextBtn.setEnabled(s.page + 1 < s.pageCount);
    }

    private void clearKeyword() {
        updatingSearch = true;
        searchEt.setText("");
        updatingSearch = false;
        vm.setKeyword("");
    }

    private void clearAllFilters() {
        updatingSearch = true;
        searchEt.setText("");
        updatingSearch = false;
        vm.clearFilters();
    }

    private void showDeptDialog() {
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_asset_depts, null, false);
        EditText search = view.findViewById(R.id.deptDialogSearchEt);
        LinearLayout list = view.findViewById(R.id.deptDialogListContainer);
        TextView empty = view.findViewById(R.id.deptDialogEmptyTv);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.asset_dept_dialog_title)
                .setView(view)
                .setNegativeButton(R.string.action_close, null)
                .create();

        Runnable render = () -> renderDeptDialogRows(list, empty, search.getText().toString(), dialog);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { render.run(); }
        });
        render.run();

        LifecycleDialogs.showDismissOnDestroy(this, dialog);
    }

    private void renderDeptDialogRows(@NonNull LinearLayout container, @NonNull TextView empty,
                                      @Nullable String query, @NonNull AlertDialog dialog) {
        container.removeAllViews();
        List<CategoryCount> buckets = new ArrayList<>(lastDeptBuckets);
        if (lastDeptBucket != null && !containsBucket(buckets, lastDeptBucket)) {
            buckets.add(new CategoryCount(lastDeptBucket, 0));
        }

        int total = 0;
        for (CategoryCount c : buckets) total += c.count;

        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (CategoryCount bucket : buckets) {
            String label = bucket.category.isEmpty()
                    ? getString(R.string.asset_dept_unassigned) : bucket.category;
            if (!q.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(q)) continue;

            View row = LayoutInflater.from(requireContext())
                    .inflate(R.layout.view_asset_dept_dialog_row, container, false);
            TextView nameTv = row.findViewById(R.id.deptDialogNameTv);
            TextView valueTv = row.findViewById(R.id.deptDialogValueTv);
            nameTv.setText(label);
            int pct = total == 0 ? 0 : Math.round(100f * bucket.count / total);
            valueTv.setText(getString(R.string.asset_dept_row_count_fmt, bucket.count, pct));
            if (Objects.equals(lastDeptBucket, bucket.category)) {
                row.setBackgroundColor(0x1A1976D2);
            }
            row.setOnClickListener(v -> {
                vm.toggleDeptBucket(bucket.category);
                dialog.dismiss();
                if (listCard != null) {
                    scrollView.post(() -> scrollView.smoothScrollTo(0, listCard.getTop()));
                }
            });
            container.addView(row);
            shown++;
        }
        empty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    private void openAsset(long assetId) {
        if (assetLoading) return; // 连点同一行时避免重复查询/叠两个弹窗
        assetLoading = true;
        vm.loadLedgerAsset(assetId, new AssetOverviewViewModel.LedgerLoadCallback() {
            @Override
            public void onLoaded(@NonNull Asset asset, @Nullable InventoryTask task) {
                assetLoading = false;
                if (!isAdded()) return;
                AssetDetailDialog.showLedger(AssetOverviewFragment.this, asset,
                        ledgerOptions(asset, task), AssetOverviewFragment.this::onLedgerSave);
            }

            @Override
            public void onMissing() {
                assetLoading = false;
                if (!isAdded()) return;
                Toast.makeText(requireContext(), R.string.asset_asset_missing,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static AssetDetailDialog.LedgerOptions ledgerOptions(
            @NonNull Asset asset, @Nullable InventoryTask task) {
        if (task == null) {
            return asset.taskId == 0L
                    ? AssetDetailDialog.LedgerOptions.pool()
                    : AssetDetailDialog.LedgerOptions.orphan();
        }
        String name = task.sourceFileName;
        if ("COMPLETED".equals(task.status)) {
            return AssetDetailDialog.LedgerOptions.completed(name);
        }
        if ("PAUSED".equals(task.status)) {
            return AssetDetailDialog.LedgerOptions.paused(name);
        }
        if ("IN_PROGRESS".equals(task.status)) {
            return AssetDetailDialog.LedgerOptions.inProgress(name);
        }
        return AssetDetailDialog.LedgerOptions.orphan();
    }

    private void onLedgerSave(Asset pending, AssetDetailDialog.SaveCompletion completion) {
        vm.saveLedger(pending, result -> {
            if (!isAdded()) return;
            switch (result) {
                case SUCCESS:
                    completion.onSuccess();
                    Toast.makeText(requireContext(), R.string.msg_asset_saved,
                            Toast.LENGTH_SHORT).show();
                    break;
                case FROZEN:
                    completion.onFrozen();
                    break;
                case MISSING:
                    completion.onMissing();
                    break;
                case ERROR:
                default:
                    completion.onError();
                    break;
            }
        });
    }

    private static boolean containsBucket(List<CategoryCount> buckets, String category) {
        return findBucket(buckets, category) != null;
    }

    @Nullable
    private static CategoryCount findBucket(List<CategoryCount> buckets, String category) {
        for (CategoryCount c : buckets) {
            if (Objects.equals(c.category, category)) return c;
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}

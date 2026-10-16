package com.huaxia.inventory.ui.result;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
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
import com.huaxia.inventory.ui.util.LifecycleDialogs;
import com.huaxia.inventory.util.DateUtil;
import com.huaxia.inventory.util.EpcNormalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ResultFragment extends Fragment {

    private static final String TAG = "ResultFragment";

    public static final String ARG_TAB = "tab";
    /**
     * 可选: 显式指定要查看的 taskId (任务卡"查看详情" → 查看某已完成任务)。
     * 传了就用它 (仅展示, 不改 session / 不给它写权限); 不传回落到 session 的当前任务。
     */
    public static final String ARG_VIEW_TASK_ID = "viewTaskId";

    private ResultViewModel vm;
    private RecyclerView rv;
    private View emptyTv;
    private TextView paginationInfoTv;
    private MaterialButton prevBtn, nextBtn;
    private TextView resultTitleTv;

    /**
     * 当前任务 found 资产 id 集合 — 来自 {@link ResultViewModel#matchedIds()}.
     * 仅缓存一份, 由 observer 回调更新到 {@link AssetResultAdapter}.
     * 不再由 ResultFragment 自管 (旧实现: assets observer 内 dispatch findFound
     * 全表加载 + UI 线程读写 race).
     */
    private Set<Long> matchedIdsCache = new HashSet<>();
    /**
     * 当前 RecyclerView 上的 AssetResultAdapter 实例 — 两个 observer (assets +
     * matchedIds) 复用同一个 adapter, 只调 {@link AssetResultAdapter#setItems}
     * / {@link AssetResultAdapter#setMatchedIds} + notifyDataSetChanged,
     * 不再多次 {@code new AssetResultAdapter + setAdapter}.
     */
    private AssetResultAdapter assetAdapter;
    private ActivityResultLauncher<String> exportLauncher;
    private long currentTaskId = -1L;
    /** 只读模式 (已完成任务"查看详情"): 弹窗只读, 不能编辑/保存/删除盘盈。 */
    private boolean readOnly;

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
        resultTitleTv = v.findViewById(R.id.resultTitleTv);
        paginationInfoTv = v.findViewById(R.id.paginationInfoTv);
        prevBtn = v.findViewById(R.id.prevBtn);
        nextBtn = v.findViewById(R.id.nextBtn);

        rv.setLayoutManager(new LinearLayoutManager(requireContext()));

        prevBtn.setOnClickListener(btn -> vm.prevPage());
        nextBtn.setOnClickListener(btn -> vm.nextPage());

        MaterialButton exportBtn = v.findViewById(R.id.exportBtn);
        exportBtn.setOnClickListener(btn -> onExportClicked());

        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        // 显式传入 viewTaskId 时用它 (任务卡"查看详情"查看已完成任务); 否则用当前任务。
        long viewTaskId = getArguments() != null
                ? getArguments().getLong(ARG_VIEW_TASK_ID, -1L) : -1L;
        // 参数缺省时回落到 session 的"查看中任务" —— 底部 tab 切换也保持查看上下文。
        if (viewTaskId < 0L) {
            Long viewing = app.getSession().getViewingTaskId();
            if (viewing != null) viewTaskId = viewing;
        }
        Long taskId = viewTaskId >= 0L
                ? Long.valueOf(viewTaskId) : app.getSession().getCurrentTaskId();
        if (taskId == null) {
            emptyTv.setVisibility(View.VISIBLE);
            rv.setVisibility(View.GONE);
            paginationInfoTv.setText("");
            exportBtn.setEnabled(false);
            return;
        }
        currentTaskId = taskId;
        // 只读: 由"任务卡查看详情"传入 ARG_VIEW_TASK_ID (该入口只对已完成任务暴露) →
        // 已完成任务结果页只读 (可看/导出, 不可编辑)。当前任务 (session 路径) 永远可编辑。
        readOnly = viewTaskId >= 0L;

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

        // 标题后缀标出当前 tab (全部设备/已盘/盘亏/盘盈) + 该 tab 数量.
        // 数量来自 stats(), 故 stats observer 里一并刷新 (删盘盈后 count 自动更新).
        vm.activeTab().observe(getViewLifecycleOwner(), t -> updateResultTitle());

        vm.currentPage().observe(getViewLifecycleOwner(), page -> updatePagination());

        vm.stats().observe(getViewLifecycleOwner(), stats -> {
            updatePagination();
            updateResultTitle();
        });

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
            // matchedIds 由 vm.matchedIds() 一次性拉取, UI 只读 — 不再在 observer
            // 内 dispatch findFound 全表加载. 复用同一个 assetAdapter 实例, 只调
            // setItems + setMatchedIds + notifyDataSetChanged — 避免多次
            // new AssetResultAdapter + setAdapter 让 RecyclerView 内部短链残留.
            if (assetAdapter == null) {
                assetAdapter = new AssetResultAdapter(
                        assets, matchedIdsCache, this::showAssetDetailDialog);
                rv.setAdapter(assetAdapter);
            } else {
                assetAdapter.setItems(assets);
                assetAdapter.setMatchedIds(matchedIdsCache);
                assetAdapter.notifyDataSetChanged();
            }
        });

        vm.matchedIds().observe(getViewLifecycleOwner(), matched -> {
            if (matched == null) return;
            // 每次进结果页或 refresh 时, matchedIds 会更新 (新盘到 / 取消关联都触发).
            // 更新 matchedIdsCache 即可 — assets observer 会在下一次 setItems 时
            // 一并应用, 不必此处再 setAdapter. 如果 cache 已更新但 list 没动,
            // 当前 list 的 render 仍会读取最新 cache (assetAdapter.matchedIds
            // 字段已变), 下一轮 onBindViewHolder 自然反映.
            Set<Long> newCache = new HashSet<>(matched);
            if (newCache.equals(matchedIdsCache)) {
                return;  // 内容相同, 不更新
            }
            matchedIdsCache.clear();
            matchedIdsCache.addAll(newCache);
            if (assetAdapter != null) {
                assetAdapter.setMatchedIds(matchedIdsCache);
                assetAdapter.notifyDataSetChanged();
            }
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

    /**
     * 结果页标题 = 基础标题 + 当前 tab 名 + 该 tab 数量 (如「📊 盘点结果 · 盘盈（3）」).
     * 由 activeTab + stats 两个 observer 驱动: 数量随删除/刷新自动更新.
     */
    private void updateResultTitle() {
        if (resultTitleTv == null) return;
        ResultTab t = vm.activeTab().getValue();
        if (t == null) t = ResultTab.FOUND;
        ResultViewModel.Stats s = vm.stats().getValue();
        int labelRes;
        int count;
        switch (t) {
            case ALL:
                labelRes = R.string.result_stat_all;
                count = s == null ? 0 : s.total;
                break;
            case MISSING:
                labelRes = R.string.result_stat_missing;
                count = s == null ? 0 : s.missing;
                break;
            case EXTRA:
                labelRes = R.string.result_stat_extra;
                count = s == null ? 0 : s.extra;
                break;
            case FOUND:
            default:
                labelRes = R.string.result_stat_found;
                count = s == null ? 0 : s.found;
                break;
        }
        resultTitleTv.setText(getString(R.string.result_title_tab_fmt,
                getString(R.string.result_title), getString(labelRes), count));
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
        // 在切到 excelIO 之前先 cache Context + ContentResolver + CacheDir —
        // SAF 文件写盘可能耗时 5s+, 用户在这期间可能通过底部 nav 切走,
        // 导致 fragment detach; lambda 派发到 detach 的 fragment 后再调
        // requireContext() 会抛 IllegalStateException, 同时 xlsx 写到一半的
        // OutputStream 因为 fragment 销毁被 GC 截断损坏. 缓存强引用 + 双层
        // isAdded 守卫 + 主线程再判 getView() 路径在两个 branch 都保留.
        final android.content.Context ctx = requireContext();
        final android.content.ContentResolver resolver = ctx.getContentResolver();
        final java.io.File cacheDir = ctx.getCacheDir();
        app.getExecutors().excelIO.execute(() -> {
            try {
                InventoryTask task = app.getDb().inventoryTaskDao().findById(taskId);

                List<String> originalHeaders = new ArrayList<>();
                List<Asset> firstPage = app.getDb().assetDao().pageAll(taskId, 1, 0);
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

                try (java.io.OutputStream out = resolver.openOutputStream(uri)) {
                    ExcelExporter.exportStreamed(out, task.sourceFileName, originalHeaders,
                            app.getDb().assetDao(), app.getDb().scanRecordDao(),
                            app.getDb().extraClaimDao(), taskId, cacheDir);
                }
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        View v = getView();
                        if (v != null) {
                            Snackbar.make(v, R.string.msg_export_done,
                                    Snackbar.LENGTH_SHORT).show();
                        }
                    });
                }
            } catch (Exception e) {
                // 异常详情写入受控 Log 通道 (offline + diag), 给用户只看固定文案
                // (R.string.err_export_failed), 不暴露 SAF URI / 本地路径 / SQL 异常.
                Log.e(TAG, "doExport failed", e);
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        View v = getView();
                        if (v != null) {
                            Snackbar.make(v,
                                getString(R.string.err_export_failed),
                                Snackbar.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private void showAssetDetailDialog(Asset asset) {
        boolean isMatched = matchedIdsCache.contains(asset.id);
        AssetDetailDialog.show(this, asset, isMatched, readOnly, this::onAssetDetailSaved);
    }

    private void onAssetDetailSaved(Asset pendingSave,
                                    AssetDetailDialog.SaveCompletion completion) {
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        long taskId = pendingSave.taskId;
        app.getExecutors().diskIO.execute(() -> {
            // 单事务: precheck findByEpcGlobal + inventoryTime 计算 + update — 防并发
            // (a) 其它路径 (导入 / 任务切换) 在 precheck 与 update 之间改写其它字段;
            // (b) backfillFoundInventoryTime 与 pendingSave.inventoryTime 并发写入
            // 同一行, 两条路径写入值不一致. 事务内 SQLite 行级锁串行化两条 update,
            // 先到先写, 后到看到的 inventory_time 与本批最终值一.
            final boolean[] isDuplicateHolder = {false};
            final boolean[] successHolder = {false};
            try {
                app.getDb().runInTransaction(() -> {
                    Asset existing = app.getDb().assetDao().findByEpcGlobal(pendingSave.epc);
                    if (existing != null && existing.id != pendingSave.id) {
                        isDuplicateHolder[0] = true;
                        return;
                    }
                    Long firstScanAt = app.getDb().scanRecordDao().findFirstScanTime(
                            taskId, pendingSave.id);
                    pendingSave.inventoryTime = (firstScanAt != null && firstScanAt > 0)
                            ? firstScanAt
                            : System.currentTimeMillis();
                    app.getDb().assetDao().update(pendingSave);
                    successHolder[0] = true;
                });
            } catch (android.database.sqlite.SQLiteConstraintException e) {
                // 兜底: 跨事务并发窗口下 epc 唯一索引触发 (TOCTOU).
                // 日志不打印明文 epc — EPC 是固资编号, 业务敏感, logcat 暴露给运维 / 厂商
                // 诊断系统采集就有泄露面. 只打印长度 + hash 末 4 位用于排障.
                Log.w(TAG, "asset save caught SQLiteConstraintException for epc(len="
                        + (pendingSave.epc == null ? 0 : pendingSave.epc.length()) + ")", e);
                isDuplicateHolder[0] = true;
            } catch (RuntimeException e) {
                Log.e(TAG, "asset save failed", e);
            }
            if (isDuplicateHolder[0]) {
                postAssetSaveResult(completion::onDuplicateEpc);
            } else if (successHolder[0]) {
                postAssetSaveResult(() -> {
                    completion.onSuccess();
                    View v = getView();
                    if (v != null) {
                        Snackbar.make(v, R.string.msg_asset_saved,
                                Snackbar.LENGTH_SHORT).show();
                    }
                    vm.refresh();
                });
            } else {
                postAssetSaveResult(completion::onError);
            }
        });
    }

    private void postAssetSaveResult(Runnable action) {
        if (!isAdded()) return;
        FragmentActivity activity = getActivity();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            if (!isAdded()) return;
            action.run();
        });
    }

    private void showExtraDetailDialog(ExtraWithClaim data) {
        ExtraDetailDialog.show(this, data, readOnly,
                claim -> onExtraDetailSaved(data.scan.id, claim),
                () -> confirmDeleteExtra(data));
    }

    /** 二次确认后单条删除盘盈记录 (ScanRecord + ExtraClaim)。 */
    private void confirmDeleteExtra(ExtraWithClaim data) {
        String displayEpc = data.scan.epc == null ? "" : EpcNormalizer.toDisplayForm(data.scan.epc);
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.extra_delete_confirm_title)
                .setMessage(getString(R.string.extra_delete_confirm_msg, displayEpc))
                .setPositiveButton(R.string.extra_delete_confirm_ok, (d, w) ->
                        vm.deleteExtra(data.scan.id, data.scan.epc, () -> {
                            if (!isAdded()) return;
                            requireActivity().runOnUiThread(() -> {
                                if (!isAdded()) return;
                                View v = getView();
                                if (v != null) {
                                    Snackbar.make(v, R.string.extra_delete_done,
                                            Snackbar.LENGTH_SHORT).show();
                                }
                                vm.refresh();
                            });
                        }))
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        LifecycleDialogs.showDismissOnDestroy(this, dialog);
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

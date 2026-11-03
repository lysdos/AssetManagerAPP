package com.huaxia.inventory.ui.result;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.android.material.button.MaterialButton;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.util.DateUtil;
import com.huaxia.inventory.util.EpcNormalizer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 资产详情弹窗：业务字段全部可编辑（除盘点状态/盘点时间只读），回调 onSave 由调用方入库。
 *
 * <p>业务字段：固资编号(epc) / 设备类型 / 设备型号 / 序列号 / 领用部门 / 领用人。<br>
 * 固资编号保存时走 {@link EpcNormalizer#normalize(String)} 校验（16/17/24 位十进制）。</p>
 *
 * <p>盘点时间 (inventoryTime) 的计算与 DB 写入由 ResultFragment 负责，
 * 因为需要访问 ScanRecordDao / AssetDao。</p>
 */
public final class AssetDetailDialog {

    public interface SaveCallback {
        void onSave(Asset pendingSave, SaveCompletion completion);
    }

    public interface SaveCompletion {
        void onSuccess();
        void onDuplicateEpc();
        void onError();

        /** 台账编辑: 任务在保存前变成了已完成/被删除. 默认退化为通用错误. */
        default void onFrozen() { onError(); }

        /** 台账编辑: 资产行已不存在. 默认退化为通用错误. */
        default void onMissing() { onError(); }
    }

    /** 资产 tab 编辑弹窗的任务上下文. */
    public enum LedgerMode { POOL, IN_PROGRESS, PAUSED, COMPLETED, ORPHAN }

    public static final class LedgerOptions {
        public final LedgerMode mode;
        @Nullable public final String taskName;

        public LedgerOptions(LedgerMode mode, @Nullable String taskName) {
            this.mode = mode;
            this.taskName = taskName;
        }

        public boolean isReadOnly() {
            return mode == LedgerMode.COMPLETED || mode == LedgerMode.ORPHAN;
        }

        public static LedgerOptions pool() {
            return new LedgerOptions(LedgerMode.POOL, null);
        }

        public static LedgerOptions inProgress(@Nullable String taskName) {
            return new LedgerOptions(LedgerMode.IN_PROGRESS, taskName);
        }

        public static LedgerOptions paused(@Nullable String taskName) {
            return new LedgerOptions(LedgerMode.PAUSED, taskName);
        }

        public static LedgerOptions completed(@Nullable String taskName) {
            return new LedgerOptions(LedgerMode.COMPLETED, taskName);
        }

        public static LedgerOptions orphan() {
            return new LedgerOptions(LedgerMode.ORPHAN, null);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AssetDetailDialog() {}

    public static void show(@NonNull Fragment fragment,
                            @NonNull Asset asset,
                            boolean isMatched,
                            @NonNull SaveCallback onSave) {
        showInternal(fragment, asset, isMatched, false, null, onSave);
    }

    /**
     * @param readOnly 只读模式 (已完成任务"查看详情"): 字段不可编辑、无保存按钮, 只看 + 关闭。
     */
    public static void show(@NonNull Fragment fragment,
                            @NonNull Asset asset,
                            boolean isMatched,
                            boolean readOnly,
                            @NonNull SaveCallback onSave) {
        showInternal(fragment, asset, isMatched, readOnly, null, onSave);
    }

    /**
     * 资产 tab (全局台账) 编辑入口. 与结果页共用字段/校验/rawRowJson 重写,
     * 区别: 隐藏盘点状态、EPC 只读、任务上下文横幅、保存不计算 inventoryTime.
     */
    public static void showLedger(@NonNull Fragment fragment,
                                  @NonNull Asset asset,
                                  @NonNull LedgerOptions options,
                                  @NonNull SaveCallback onSave) {
        showInternal(fragment, asset, false, options.isReadOnly(), options, onSave);
    }

    private static void showInternal(@NonNull Fragment fragment,
                                     @NonNull Asset asset,
                                     boolean isMatched,
                                     boolean readOnly,
                                     @Nullable LedgerOptions ledger,
                                     @NonNull SaveCallback onSave) {
        boolean ledgerMode = ledger != null;
        View view = LayoutInflater.from(fragment.requireContext())
                .inflate(R.layout.dialog_asset_detail, null, false);

        EditText epcEt = view.findViewById(R.id.dlgEpcEt);
        EditText typeEt = view.findViewById(R.id.dlgTypeEt);
        EditText modelEt = view.findViewById(R.id.dlgModelEt);
        EditText serialEt = view.findViewById(R.id.dlgSerialEt);
        EditText deptEt = view.findViewById(R.id.dlgBorrowDeptEt);
        EditText borrowerEt = view.findViewById(R.id.dlgBorrowerEt);
        View statusRow = view.findViewById(R.id.dlgInventoryStatusRow);
        TextView taskHintTv = view.findViewById(R.id.dlgTaskHintTv);
        TextView epcHintTv = view.findViewById(R.id.dlgEpcReadonlyHintTv);

        epcEt.setText(asset.epc == null ? "" : EpcNormalizer.toDisplayForm(asset.epc));
        typeEt.setText(safe(asset.assetType));
        modelEt.setText(safe(asset.assetModel));
        serialEt.setText(safe(asset.serialNumber));
        deptEt.setText(safe(asset.dept));
        borrowerEt.setText(safe(asset.custodian));

        TextView inventoryStatusTv = view.findViewById(R.id.dlgInventoryStatusTv);
        if (!ledgerMode) {
            inventoryStatusTv.setText(isMatched
                    ? fragment.getString(R.string.tag_found)
                    : fragment.getString(R.string.tag_missing));
        }

        TextView inventoryTimeTv = view.findViewById(R.id.dlgInventoryTimeTv);
        inventoryTimeTv.setText(asset.inventoryTime == null
                ? "—"
                : DateUtil.formatTimestamp(asset.inventoryTime));

        int titleRes = ledgerMode && !readOnly
                ? R.string.dialog_asset_edit_title : R.string.dialog_asset_title;
        AlertDialog dialog = new AlertDialog.Builder(fragment.requireContext())
                .setTitle(titleRes)
                .setView(view)
                .create();

        MaterialButton cancelBtn = view.findViewById(R.id.dlgCancelBtn);
        MaterialButton saveBtn = view.findViewById(R.id.dlgSaveBtn);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        saveBtn.setOnClickListener(v -> {
            if (ledgerMode) {
                // 台账编辑: EPC 只读, 沿用原值; inventoryTime/taskId/快照由调用方保持不变.
                Asset pending = copyAsset(asset);
                pending.assetType = typeEt.getText().toString().trim();
                pending.assetModel = modelEt.getText().toString().trim();
                pending.serialNumber = serialEt.getText().toString().trim();
                pending.dept = deptEt.getText().toString().trim();
                pending.custodian = borrowerEt.getText().toString().trim();

                String epc = asset.epc == null ? "" : asset.epc;
                Map<String, Object> headers = parseHeaders(asset.rawRowJson);
                headers.put("固资编号", EpcNormalizer.toDisplayForm(epc));
                headers.put("设备类型", pending.assetType);
                headers.put("设备型号", pending.assetModel);
                headers.put("序列号", pending.serialNumber);
                headers.put("领用部门", pending.dept);
                headers.put("领用人", pending.custodian);
                try {
                    pending.rawRowJson = MAPPER.writeValueAsString(headers);
                } catch (Exception e) {
                    pending.rawRowJson = "{}";
                }

                saveBtn.setEnabled(false);
                cancelBtn.setEnabled(false);
                dialog.setCancelable(false);
                dialog.setCanceledOnTouchOutside(false);
                onSave.onSave(pending, new SaveCompletion() {
                    @Override
                    public void onSuccess() {
                        if (dialog.isShowing()) dialog.dismiss();
                    }

                    @Override
                    public void onDuplicateEpc() {
                        onError();
                    }

                    @Override
                    public void onFrozen() {
                        if (!dialog.isShowing()) return;
                        restoreEditing(dialog, saveBtn, cancelBtn);
                        Toast.makeText(view.getContext(), R.string.asset_task_frozen_error,
                                Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onMissing() {
                        if (!dialog.isShowing()) return;
                        restoreEditing(dialog, saveBtn, cancelBtn);
                        Toast.makeText(view.getContext(), R.string.asset_asset_missing,
                                Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onError() {
                        if (!dialog.isShowing()) return;
                        restoreEditing(dialog, saveBtn, cancelBtn);
                        Toast.makeText(view.getContext(), R.string.err_asset_save_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }

            // ---- 结果页原有路径 ----
            String newEpcDisplay = epcEt.getText().toString().trim();
            String normalized = EpcNormalizer.normalize(newEpcDisplay);
            if (normalized == null) {
                Toast.makeText(fragment.requireContext(),
                        R.string.manual_err_invalid, Toast.LENGTH_SHORT).show();
                return;
            }

            epcEt.setError(null);
            Asset pending = copyAsset(asset);
            pending.epc = normalized;
            pending.assetType = typeEt.getText().toString().trim();
            pending.assetModel = modelEt.getText().toString().trim();
            pending.serialNumber = serialEt.getText().toString().trim();
            pending.dept = deptEt.getText().toString().trim();
            pending.custodian = borrowerEt.getText().toString().trim();

            Map<String, Object> headers = parseHeaders(asset.rawRowJson);
            headers.put("固资编号", EpcNormalizer.toDisplayForm(normalized));
            headers.put("设备类型", pending.assetType);
            headers.put("设备型号", pending.assetModel);
            headers.put("序列号", pending.serialNumber);
            headers.put("领用部门", pending.dept);
            headers.put("领用人", pending.custodian);
            try {
                pending.rawRowJson = MAPPER.writeValueAsString(headers);
            } catch (Exception e) {
                pending.rawRowJson = "{}";
            }

            saveBtn.setEnabled(false);
            cancelBtn.setEnabled(false);
            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);
            onSave.onSave(pending, new SaveCompletion() {
                @Override
                public void onSuccess() {
                    if (dialog.isShowing()) dialog.dismiss();
                }

                @Override
                public void onDuplicateEpc() {
                    if (!dialog.isShowing()) return;
                    restoreEditing(dialog, saveBtn, cancelBtn);
                    epcEt.setError(fragment.getString(R.string.err_epc_duplicate));
                    epcEt.requestFocus();
                }

                @Override
                public void onError() {
                    if (!dialog.isShowing()) return;
                    restoreEditing(dialog, saveBtn, cancelBtn);
                    Toast.makeText(view.getContext(), R.string.err_asset_save_failed,
                            Toast.LENGTH_SHORT).show();
                }
            });
        });

        if (ledgerMode) {
            statusRow.setVisibility(View.GONE);
            epcEt.setEnabled(false);
            epcHintTv.setVisibility(View.VISIBLE);
            taskHintTv.setVisibility(View.VISIBLE);
            applyLedgerHint(fragment, taskHintTv, ledger);
            if (!readOnly) {
                saveBtn.setText(R.string.action_save_edit);
                cancelBtn.setText(R.string.action_cancel);
            } else {
                cancelBtn.setText(R.string.action_close);
            }
        }

        if (readOnly) {
            // 只读 (查看已完成任务): 字段不可编辑、隐藏保存按钮 —— 无写权限。
            epcEt.setEnabled(false);
            typeEt.setEnabled(false);
            modelEt.setEnabled(false);
            serialEt.setEnabled(false);
            deptEt.setEnabled(false);
            borrowerEt.setEnabled(false);
            saveBtn.setVisibility(View.GONE);
        }

        dialog.show();

        // AlertDialog 不归 FragmentManager 管: 宿主销毁(含旋转)时必须主动关闭, 否则旋转
        // 会泄漏 Window(WindowLeaked), 且异步保存完成的回调可能在 detach 后调
        // requireContext/getString 崩溃。关闭后回调里的 dialog.isShowing() 守卫自然变 no-op。
        // 用 fragment.getLifecycle() 而非 getViewLifecycleOwner() — 后者在无 View 的宿主
        // (如单元测试的裸 Fragment) 会抛 IllegalStateException。
        androidx.lifecycle.DefaultLifecycleObserver destroyObserver =
                new androidx.lifecycle.DefaultLifecycleObserver() {
                    @Override
                    public void onDestroy(@NonNull androidx.lifecycle.LifecycleOwner owner) {
                        fragment.getLifecycle().removeObserver(this);
                        if (dialog.isShowing()) dialog.dismiss();
                    }
                };
        fragment.getLifecycle().addObserver(destroyObserver);
        // 弹窗手动关闭后立即移除 observer, 否则每打开一次台账编辑都会在宿主
        // Lifecycle 上累积一个持有 dialog/view 的 observer, 浏览多行后持续泄漏.
        dialog.setOnDismissListener(d ->
                fragment.getLifecycle().removeObserver(destroyObserver));
    }

    private static void applyLedgerHint(@NonNull Fragment fragment,
                                        @NonNull TextView tv,
                                        @NonNull LedgerOptions options) {
        String name = (options.taskName == null || options.taskName.isEmpty())
                ? fragment.getString(R.string.asset_task_name_unknown)
                : options.taskName;
        switch (options.mode) {
            case POOL:
                tv.setText(R.string.asset_task_hint_pool);
                tv.setBackgroundResource(R.drawable.bg_tip_info);
                tv.setTextColor(ContextCompat.getColor(fragment.requireContext(),
                        R.color.tipInfoFg));
                return;
            case IN_PROGRESS:
                tv.setText(fragment.getString(R.string.asset_task_hint_in_progress_fmt, name));
                break;
            case PAUSED:
                tv.setText(fragment.getString(R.string.asset_task_hint_paused_fmt, name));
                break;
            case COMPLETED:
                tv.setText(fragment.getString(R.string.asset_task_hint_completed_fmt, name));
                break;
            case ORPHAN:
            default:
                tv.setText(R.string.asset_task_hint_orphan);
                break;
        }
        tv.setBackgroundResource(R.drawable.bg_tip_warning);
        tv.setTextColor(ContextCompat.getColor(fragment.requireContext(),
                R.color.tipWarningFg));
    }

    private static void restoreEditing(AlertDialog dialog, View saveBtn, View cancelBtn) {
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        saveBtn.setEnabled(true);
        cancelBtn.setEnabled(true);
    }

    /**
     * 复制 Asset 用于 ResultFragment 的异步保存流程。
     *
     * <p>必须复制全部持久化字段, 缺一即破坏后续语义:
     * <ul>
     *   <li>{@code id / taskId / excelRowIndex} — Room @Update 按 id 匹配主键,
     *       ResultFragment 用 taskId 写入 ScanRecord.</li>
     *   <li>{@code rawRowJson} — 导出路径会读这份 JSON 重建 Excel 列,
     *       不复制会导致导出丢字段.</li>
     *   <li>{@code inventoryTime} — ResultFragment 会先覆盖它(用最早扫描时间),
     *       但列表显示依赖该字段; 不复制会让用户看到的盘点时间被改写.</li>
     *   <li>{@code createdAt} — @Update 按 id 覆盖整行, 不复制会把"首次导入时间"
     *       清成 NULL, 「+N 今日」统计随之丢失.</li>
     *   <li>其余业务字段 ({@code assetType / assetModel / serialNumber / custodian / dept})
     *       — dialog 立刻改写, 但保留旧值作为回退.</li>
     * </ul>
     *
     * <p>新增 Asset 字段时必须同步更新本方法, 否则复制会静默丢字段.</p>
     */
    private static Asset copyAsset(Asset source) {
        Asset copy = new Asset();
        copy.id = source.id;
        copy.taskId = source.taskId;
        copy.excelRowIndex = source.excelRowIndex;
        copy.epc = source.epc;
        copy.assetType = source.assetType;
        copy.assetModel = source.assetModel;
        copy.serialNumber = source.serialNumber;
        copy.custodian = source.custodian;
        copy.dept = source.dept;
        copy.rawRowJson = source.rawRowJson;
        copy.inventoryTime = source.inventoryTime;
        copy.createdAt = source.createdAt;
        // 快照列 (v10): 必须一并复制 —— ResultFragment 用 update(pending) 整行 REPLACE,
        // 漏复制会把快照清空、破坏"删除/重置回滚"。
        copy.origEpc = source.origEpc;
        copy.origAssetType = source.origAssetType;
        copy.origAssetModel = source.origAssetModel;
        copy.origSerialNumber = source.origSerialNumber;
        copy.origCustodian = source.origCustodian;
        copy.origDept = source.origDept;
        copy.origRawRowJson = source.origRawRowJson;
        return copy;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static Map<String, Object> parseHeaders(String rawRowJson) {
        if (rawRowJson == null || rawRowJson.isEmpty()) return new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = MAPPER.readValue(rawRowJson, Map.class);
            return result;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
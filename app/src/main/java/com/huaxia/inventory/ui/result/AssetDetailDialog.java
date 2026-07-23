package com.huaxia.inventory.ui.result;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AssetDetailDialog() {}

    public static void show(@NonNull Fragment fragment,
                            @NonNull Asset asset,
                            boolean isMatched,
                            @NonNull SaveCallback onSave) {
        View view = LayoutInflater.from(fragment.requireContext())
                .inflate(R.layout.dialog_asset_detail, null, false);

        EditText epcEt = view.findViewById(R.id.dlgEpcEt);
        EditText typeEt = view.findViewById(R.id.dlgTypeEt);
        EditText modelEt = view.findViewById(R.id.dlgModelEt);
        EditText serialEt = view.findViewById(R.id.dlgSerialEt);
        EditText deptEt = view.findViewById(R.id.dlgBorrowDeptEt);
        EditText borrowerEt = view.findViewById(R.id.dlgBorrowerEt);

        epcEt.setText(asset.epc == null ? "" : EpcNormalizer.toDisplayForm(asset.epc));
        typeEt.setText(safe(asset.assetType));
        modelEt.setText(safe(asset.assetModel));
        serialEt.setText(safe(asset.serialNumber));
        deptEt.setText(safe(asset.dept));
        borrowerEt.setText(safe(asset.custodian));

        TextView inventoryStatusTv = view.findViewById(R.id.dlgInventoryStatusTv);
        inventoryStatusTv.setText(isMatched
                ? fragment.getString(R.string.tag_found)
                : fragment.getString(R.string.tag_missing));

        TextView inventoryTimeTv = view.findViewById(R.id.dlgInventoryTimeTv);
        inventoryTimeTv.setText(asset.inventoryTime == null
                ? "—"
                : DateUtil.formatTimestamp(asset.inventoryTime));

        AlertDialog dialog = new AlertDialog.Builder(fragment.requireContext())
                .setTitle(R.string.dialog_asset_title)
                .setView(view)
                .create();

        View cancelBtn = view.findViewById(R.id.dlgCancelBtn);
        View saveBtn = view.findViewById(R.id.dlgSaveBtn);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        saveBtn.setOnClickListener(v -> {
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

        dialog.show();
    }

    private static void restoreEditing(AlertDialog dialog, View saveBtn, View cancelBtn) {
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        saveBtn.setEnabled(true);
        cancelBtn.setEnabled(true);
    }

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
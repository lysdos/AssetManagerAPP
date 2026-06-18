package com.huaxia.inventory.ui.result;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.util.EpcNormalizer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 资产详情弹窗：展示只读字段，编辑 领用部门 / 领用人，回调 onSave 由调用方入库。
 *
 * <p>职责：UI 呈现 + rawRowJson 的写回。盘点时间 (inventoryTime) 的计算与 DB 写入
 * 由 ResultFragment 负责，因为需要访问 ScanRecordDao / AssetDao。</p>
 */
public final class AssetDetailDialog {

    public interface SaveCallback {
        void onSave(Asset updatedAsset);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AssetDetailDialog() {}

    public static void show(@NonNull Fragment fragment,
                            @NonNull Asset asset,
                            boolean isMatched,
                            @NonNull SaveCallback onSave) {
        View view = LayoutInflater.from(fragment.requireContext())
                .inflate(R.layout.dialog_asset_detail, null, false);

        // ---- 只读字段 ----
        ((TextView) view.findViewById(R.id.dlgEpcTv))
                .setText(asset.epc == null ? "" : EpcNormalizer.toDisplayForm(asset.epc));
        ((TextView) view.findViewById(R.id.dlgNameTv))
                .setText(safe(asset.assetName));
        ((TextView) view.findViewById(R.id.dlgTypeTv))
                .setText(safe(asset.assetType));
        ((TextView) view.findViewById(R.id.dlgModelTv))
                .setText(safe(asset.assetModel));
        ((TextView) view.findViewById(R.id.dlgOrgTv))
                .setText(safe(asset.org));
        ((TextView) view.findViewById(R.id.dlgDetailedLocationTv))
                .setText(safe(asset.detailedLocation));
        ((TextView) view.findViewById(R.id.dlgPlacementLocationTv))
                .setText(safe(asset.placementLocation));
        ((TextView) view.findViewById(R.id.dlgAssetStatusTv))
                .setText(safe(asset.assetStatus));

        TextView inventoryStatusTv = view.findViewById(R.id.dlgInventoryStatusTv);
        inventoryStatusTv.setText(isMatched
                ? fragment.getString(R.string.tag_found)
                : fragment.getString(R.string.tag_missing));

        TextView inventoryTimeTv = view.findViewById(R.id.dlgInventoryTimeTv);
        inventoryTimeTv.setText(asset.inventoryTime == null
                ? "—"
                : com.huaxia.inventory.util.DateUtil.formatTimestamp(asset.inventoryTime));

        // ---- 可编辑字段（从 rawRowJson 读初值） ----
        Map<String, Object> headers = parseHeaders(asset.rawRowJson);
        EditText borrowDeptEt = view.findViewById(R.id.dlgBorrowDeptEt);
        EditText borrowerEt = view.findViewById(R.id.dlgBorrowerEt);
        Object deptVal = headers.get("领用部门");
        Object borrowerVal = headers.get("领用人");
        borrowDeptEt.setText(deptVal == null ? "" : deptVal.toString());
        borrowerEt.setText(borrowerVal == null ? "" : borrowerVal.toString());

        // ---- 弹窗 ----
        AlertDialog dialog = new AlertDialog.Builder(fragment.requireContext())
                .setTitle(R.string.dialog_asset_title)
                .setView(view)
                .create();

        view.findViewById(R.id.dlgCancelBtn).setOnClickListener(v -> dialog.dismiss());

        view.findViewById(R.id.dlgSaveBtn).setOnClickListener(v -> {
            // 把 2 个新值写回 headers（保留其他列）
            String newDept = borrowDeptEt.getText().toString().trim();
            String newBorrower = borrowerEt.getText().toString().trim();
            headers.put("领用部门", newDept);
            headers.put("领用人", newBorrower);
            try {
                asset.rawRowJson = MAPPER.writeValueAsString(headers);
            } catch (Exception e) {
                asset.rawRowJson = "{}";
            }
            dialog.dismiss();
            onSave.onSave(asset);
        });

        dialog.show();
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static Map<String, Object> parseHeaders(String rawRowJson) {
        if (rawRowJson == null || rawRowJson.isEmpty()) return new LinkedHashMap<>();
        try {
            // 用 raw Map.class 读, 避免 TypeReference<Map<String,String>> 在遇到 null 值时
            // 丢 key (实测 30-key JSON 会被砍到 26-key, 导致写回后导出只剩 4 列).
            @SuppressWarnings("unchecked")
            Map<String, Object> result = MAPPER.readValue(rawRowJson, Map.class);
            return result;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}

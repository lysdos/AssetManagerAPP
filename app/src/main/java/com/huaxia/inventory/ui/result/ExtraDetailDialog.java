package com.huaxia.inventory.ui.result;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.entity.ExtraClaim;
import com.huaxia.inventory.util.DateUtil;
import com.huaxia.inventory.util.EpcNormalizer;

/**
 * 盘盈补全弹窗（结果页 EXTRA tab → 点击行 → 弹此 dialog）。
 *
 * <p>ScanRecord 行不会被修改（matchedAssetId 保持 NULL，仍显示在盘盈 tab）；
 * 补全信息写入 ExtraClaim 表，与 ScanRecord 1:1。
 *
 * <p>6 个业务字段全部可编辑，与 AssetDetailDialog 字段一致：
 * 固资编号 / 设备类型 / 设备型号 / 序列号 / 领用部门 / 领用人。
 * 固资编号保存时走 EpcNormalizer.normalize() 校验。
 */
public final class ExtraDetailDialog {

    public interface SaveCallback {
        void onSave(ExtraClaim claim);
    }

    private ExtraDetailDialog() {}

    public static void show(@NonNull Fragment fragment,
                            @NonNull ExtraWithClaim data,
                            @NonNull SaveCallback onSave) {
        View view = LayoutInflater.from(fragment.requireContext())
                .inflate(R.layout.dialog_extra_detail, null, false);

        TextView epcTv = view.findViewById(R.id.extraDlgEpcTv);
        TextView scannedAtTv = view.findViewById(R.id.extraDlgScannedAtTv);

        EditText epcEt = view.findViewById(R.id.extraDlgEpcEt);
        EditText typeEt = view.findViewById(R.id.extraDlgTypeEt);
        EditText modelEt = view.findViewById(R.id.extraDlgModelEt);
        EditText serialEt = view.findViewById(R.id.extraDlgSerialEt);
        EditText deptEt = view.findViewById(R.id.extraDlgDeptEt);
        EditText borrowerEt = view.findViewById(R.id.extraDlgBorrowerEt);

        String originalEpc = data.scan.epc == null ? "" : data.scan.epc;
        // 头部只读: 显示原始扫描 EPC (来自 ScanRecord, hex 形式 — 跟扫描页一致)
        epcTv.setText(originalEpc);
        scannedAtTv.setText(DateUtil.formatTimestamp(data.scan.scannedAt));

        // 编辑区: 若已补全, 用 ExtraClaim 初值; 否则用空字符串
        ExtraClaim c = data.claim;
        epcEt.setText(c != null && c.epc != null ? EpcNormalizer.toDisplayForm(c.epc) : originalEpc);
        typeEt.setText(c == null || c.assetType == null ? "" : c.assetType);
        modelEt.setText(c == null || c.assetModel == null ? "" : c.assetModel);
        serialEt.setText(c == null || c.serialNumber == null ? "" : c.serialNumber);
        deptEt.setText(c == null || c.dept == null ? "" : c.dept);
        borrowerEt.setText(c == null || c.custodian == null ? "" : c.custodian);

        AlertDialog dialog = new AlertDialog.Builder(fragment.requireContext())
                .setTitle(R.string.dialog_extra_title)
                .setView(view)
                .create();

        view.findViewById(R.id.extraDlgCancelBtn).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.extraDlgSaveBtn).setOnClickListener(v -> {
            String newEpcDisplay = epcEt.getText().toString().trim();
            String normalized = EpcNormalizer.normalize(newEpcDisplay);
            if (normalized == null) {
                Toast.makeText(fragment.requireContext(),
                        R.string.manual_err_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            ExtraClaim claim = c == null ? new ExtraClaim() : c;
            claim.epc = normalized;
            claim.assetType = typeEt.getText().toString().trim();
            claim.assetModel = modelEt.getText().toString().trim();
            claim.serialNumber = serialEt.getText().toString().trim();
            claim.dept = deptEt.getText().toString().trim();
            claim.custodian = borrowerEt.getText().toString().trim();
            dialog.dismiss();
            onSave.onSave(claim);
        });

        dialog.show();
    }
}
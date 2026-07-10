package com.huaxia.inventory.ui.import_;

import android.app.Dialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.huaxia.inventory.R;

/**
 * 导入资产弹窗（齿轮 PopupMenu → "导入资产" 触发）。
 * 对齐 HTML 设计稿 (资产管理系统HTML界面.html lines 1204-1233)：
 *   - 上传区（点击触发 file picker）
 *   - 已选文件行 + 清除
 *   - 进度提示（解析中 / 正在导入 N / M）
 *   - 完成提示（✅ 已导入 N 条）
 *   - 自动关闭
 *
 * 与 ImportViewModel 协作：dialog 调用 parseFile → parsedFile 触发 → 启用"开始导入"按钮 →
 * 用户点开始 → commitToTask → 实时 importProgress → importDone → 1.2s 后自动 dismiss。
 */
public class ImportDialogFragment extends DialogFragment {

    private ImportViewModel importVm;

    private LinearLayout uploadArea, fileInfoRow;
    private TextView fileInfoNameTv, progressTv, errorTv;
    private MaterialButton startImportBtn;

    private Uri pickedUri;
    private String pickedName;
    private int pickedSuccessCount;
    private int pickedErrorCount;
    private boolean commited;  // 标记是否已提交, 避免 importDone 误关弹窗

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    requireContext().getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                }
                pickedUri = uri;
                pickedName = queryFileName(uri);
                showFileInfo();
                errorTv.setVisibility(View.GONE);
                progressTv.setVisibility(View.VISIBLE);
                progressTv.setText(R.string.import_progress_pre);
                startImportBtn.setEnabled(false);
                uploadArea.setEnabled(false);
                // 触发解析 (仅解析不入库), 等 parsedFile 回调再启用"开始导入"
                importVm.parseFile(uri, pickedName);
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_import, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);
        // activity-scoped, 与 MainActivity 共享同一 ViewModel 实例
        importVm = new ViewModelProvider(requireActivity()).get(ImportViewModel.class);

        uploadArea = v.findViewById(R.id.uploadArea);
        fileInfoRow = v.findViewById(R.id.fileInfoRow);
        fileInfoNameTv = v.findViewById(R.id.fileInfoNameTv);
        progressTv = v.findViewById(R.id.progressTv);
        errorTv = v.findViewById(R.id.errorTv);
        startImportBtn = v.findViewById(R.id.startImportBtn);
        ImageButton closeBtn = v.findViewById(R.id.importCloseBtn);
        Button clearFileBtn = v.findViewById(R.id.clearFileBtn);

        uploadArea.setOnClickListener(btn -> filePicker.launch(new String[]{
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"}));
        clearFileBtn.setOnClickListener(btn -> clearPicked());
        startImportBtn.setOnClickListener(btn -> startCommit());
        closeBtn.setOnClickListener(btn -> dismiss());

        importVm.parsedFile().observe(getViewLifecycleOwner(), pf -> {
            if (pf == null) return;
            // 解析完成
            pickedSuccessCount = pf.successCount;
            pickedErrorCount = pf.errorCount;
            if (pf.successCount > 0) {
                startImportBtn.setEnabled(true);
                uploadArea.setEnabled(true);
                progressTv.setText(getString(R.string.import_progress_parsed,
                        pf.successCount, pf.errorCount));
            } else {
                showError(getString(R.string.import_failed, pf.fileName));
                uploadArea.setEnabled(true);
                startImportBtn.setEnabled(false);
            }
        });
        importVm.parseError().observe(getViewLifecycleOwner(), err -> {
            if (err == null) return;
            showError(getString(R.string.err_parse_failed, err));
            uploadArea.setEnabled(true);
            startImportBtn.setEnabled(false);
        });
        importVm.importProgress().observe(getViewLifecycleOwner(), prog -> {
            if (prog == null || !commited) return;
            progressTv.setText(getString(R.string.import_importing_fmt,
                    prog.imported, prog.total));
        });
        importVm.importDone().observe(getViewLifecycleOwner(), taskId -> {
            if (taskId == null || !commited) return;
            progressTv.setText(getString(R.string.import_done_fmt, pickedSuccessCount));
            startImportBtn.setEnabled(false);
            uploadArea.setEnabled(false);
            // 1.2s 后自动关闭, 让用户看清完成提示
            mainHandler.postDelayed(() -> {
                if (isAdded()) dismiss();
            }, 1200);
        });
        importVm.importError().observe(getViewLifecycleOwner(), err -> {
            if (err == null) return;
            showError(getString(R.string.err_import_failed_fmt, err));
            commited = false;
            uploadArea.setEnabled(true);
            startImportBtn.setEnabled(true);
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog d = getDialog();
        if (d != null) {
            Window w = d.getWindow();
            if (w != null) {
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacksAndMessages(null);
    }

    private void showFileInfo() {
        fileInfoRow.setVisibility(View.VISIBLE);
        fileInfoNameTv.setText(getString(R.string.import_file_selected, pickedName));
    }

    private void startCommit() {
        if (pickedUri == null) return;
        commited = true;
        startImportBtn.setEnabled(false);
        uploadArea.setEnabled(false);
        progressTv.setText(getString(R.string.import_importing_fmt, 0, pickedSuccessCount));
        importVm.commitToTask(pickedName, pickedUri, pickedSuccessCount, taskId -> {
            // onCommitted 已被 post 到 UI thread 修过, 不再这里动 UI
        });
    }

    private void clearPicked() {
        pickedUri = null;
        pickedName = null;
        pickedSuccessCount = 0;
        pickedErrorCount = 0;
        commited = false;
        fileInfoRow.setVisibility(View.GONE);
        progressTv.setVisibility(View.GONE);
        errorTv.setVisibility(View.GONE);
        startImportBtn.setEnabled(false);
        uploadArea.setEnabled(true);
    }

    private void showError(String msg) {
        errorTv.setVisibility(View.VISIBLE);
        errorTv.setText(msg);
    }

    private String queryFileName(Uri uri) {
        try (Cursor c = requireContext().getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        }
        return uri.getLastPathSegment();
    }
}
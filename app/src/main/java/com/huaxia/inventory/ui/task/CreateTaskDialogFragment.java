package com.huaxia.inventory.ui.task;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.huaxia.inventory.R;
import java.util.ArrayList;
import java.util.List;

public class CreateTaskDialogFragment extends DialogFragment {

    private TaskListViewModel vm;
    private EditText nameEt, remarkEt;
    private Spinner deptSp;
    private TextView previewTv;
    /**
     * Spinner 选项列表. 使用 {@link java.util.concurrent.CopyOnWriteArrayList},
     * 不是因为 deptOptions 真的有跨线程并发访问 (callbacks 已 runOnUiThread + isAdded
     * 守卫), 而是为了给未来可能的 dialog 多观察者 / 转屏重建 race 兜底 — 同时 copy-on-write
     * 读路径无锁, 比强行同步的 {@code synchronized (deptOptions)} 写入更友好.
     */
    private final List<String> deptOptions = new java.util.concurrent.CopyOnWriteArrayList<>();
    private int unlimitedPos = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_create_task, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);
        vm = new ViewModelProvider(requireActivity()).get(TaskListViewModel.class);

        nameEt = v.findViewById(R.id.taskNameEt);
        remarkEt = v.findViewById(R.id.taskRemarkEt);
        deptSp = v.findViewById(R.id.taskDeptSp);
        previewTv = v.findViewById(R.id.taskPreviewTv);

        deptOptions.add(getString(R.string.task_dept_all));
        unlimitedPos = 0;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, deptOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deptSp.setAdapter(adapter);
        deptSp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                                  int position, long id) {
                refreshPreview(position);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        vm.loadDistinctDeptAll(depts -> {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                deptOptions.clear();
                deptOptions.add(getString(R.string.task_dept_all));
                deptOptions.addAll(depts);
                adapter.notifyDataSetChanged();
                refreshPreview(unlimitedPos);
            });
        });

        ImageButton closeBtn = v.findViewById(R.id.taskCloseBtn);
        closeBtn.setOnClickListener(btn -> dismiss());

        MaterialButton cancelBtn = v.findViewById(R.id.taskCancelBtn);
        MaterialButton saveBtn = v.findViewById(R.id.taskSaveBtn);
        cancelBtn.setOnClickListener(btn -> dismiss());
        saveBtn.setOnClickListener(btn -> doSave());
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

    private void refreshPreview(int position) {
        String dept = position == unlimitedPos ? null : deptOptions.get(position);
        vm.previewAssetCount(dept, count -> {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> renderPreview(dept, count));
        });
    }

    private void renderPreview(@Nullable String dept, int count) {
        if (dept == null) {
            // 不限制 → 统计全部
            if (count == 0) {
                previewTv.setText(R.string.task_preview_empty);
                previewTv.setTextColor(0xFFC62828);
            } else {
                previewTv.setText(getString(R.string.task_preview_fmt, count));
                previewTv.setTextColor(0xFF2E7D32);
            }
        } else {
            if (count == 0) {
                previewTv.setText(R.string.task_preview_dept_empty);
                previewTv.setTextColor(0xFFC62828);
            } else {
                previewTv.setText(getString(R.string.task_preview_fmt, count));
                previewTv.setTextColor(0xFF2E7D32);
            }
        }
    }

    private void doSave() {
        String name = nameEt.getText() == null ? "" : nameEt.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), R.string.task_msg_name_required, Toast.LENGTH_SHORT).show();
            return;
        }
        int pos = deptSp.getSelectedItemPosition();
        String dept = pos == unlimitedPos ? null : deptOptions.get(pos);
        String remark = remarkEt.getText() == null ? "" : remarkEt.getText().toString().trim();

        vm.previewAssetCount(dept, totalCount -> {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (totalCount == 0) {
                    Toast.makeText(requireContext(), R.string.task_msg_no_data, Toast.LENGTH_LONG).show();
                    return;
                }
                final int finalCount = totalCount;
                vm.createTask(name, dept, remark, taskId -> {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        Toast.makeText(requireContext(),
                                getString(R.string.task_msg_created, finalCount),
                                Toast.LENGTH_SHORT).show();
                        // 库存池模式: 创建任务后只关闭 dialog, 不自动跳扫描页.
                        // 用户自行在任务卡片上点"开始盘点"按钮进入扫描.
                        // 同时也不再预设 currentTaskId — 用户点"开始盘点"时再切.
                        dismiss();
                    });
                });
            });
        });
    }
}
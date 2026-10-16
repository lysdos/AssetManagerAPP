package com.huaxia.inventory.ui.task;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.PopupMenu;
import com.google.android.material.button.MaterialButton;
import android.widget.Toast;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.ui.scan.ScanFragment;
import com.huaxia.inventory.ui.util.LifecycleDialogs;
import java.util.List;

public class TaskListFragment extends Fragment {

    private static final String TAG = "TaskList";

    private TaskListViewModel vm;
    private TaskAdapter adapter;
    private TextView totalNumber, progressNumber, doneNumber;
    private TextView taskEmptyTv;
    private RecyclerView rv;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_task_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);
        vm = new ViewModelProvider(this).get(TaskListViewModel.class);

        totalNumber = v.findViewById(R.id.summaryTotal).findViewById(R.id.statNumberTv);
        progressNumber = v.findViewById(R.id.summaryProgress).findViewById(R.id.statNumberTv);
        doneNumber = v.findViewById(R.id.summaryDone).findViewById(R.id.statNumberTv);
        TextView totalLabel = v.findViewById(R.id.summaryTotal).findViewById(R.id.statLabelTv);
        TextView progressLabel = v.findViewById(R.id.summaryProgress).findViewById(R.id.statLabelTv);
        TextView doneLabel = v.findViewById(R.id.summaryDone).findViewById(R.id.statLabelTv);
        totalLabel.setText(R.string.task_summary_total);
        progressLabel.setText(R.string.task_summary_progress);
        doneLabel.setText(R.string.task_summary_done);

        taskEmptyTv = v.findViewById(R.id.taskEmptyTv);
        rv = v.findViewById(R.id.taskListRv);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));

        adapter = new TaskAdapter(new TaskAdapter.Listener() {
            @Override public void onStartTask(InventoryTask task) {
                // switchToTask 回调在 diskIO 线程触发; session 用 postValue,
                // 立刻 getValue() 读到的是旧值, cur != taskId 会恒真.
                // 参数 taskId 已经是校验过的, 直接用.
                vm.switchToTask(task.id, (taskId, ok) -> {
                    if (!isAdded()) return;   // detach 后 requireActivity() 会在后台线程抛 ISE
                    if (!ok) return;
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        navigateToScan();
                    });
                });
            }
            @Override public void onPauseTask(InventoryTask task) {
                vm.pauseTask(task.id, () -> {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        Toast.makeText(requireContext(),
                                getString(R.string.task_msg_paused, task.sourceFileName),
                                Toast.LENGTH_SHORT).show();
                    });
                });
            }
            @Override public void onResumeTask(InventoryTask task) {
                vm.resumeTask(task.id, () -> {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        Toast.makeText(requireContext(),
                                getString(R.string.task_msg_resumed, task.sourceFileName),
                                Toast.LENGTH_SHORT).show();
                    });
                });
            }
            @Override public void onViewTask(InventoryTask task) {
                // 已完成任务：切到扫描页只读查看该任务的统计 (显式传 taskId, 不改 session);
                // 从扫描页点统计卡可再下钻到结果页看列表 / 导出。
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> navigateToScanView(task.id));
            }
            @Override public void onMoreClicked(InventoryTask task, View anchor) {
                showTaskMorePopup(task, anchor);
            }
        });
        rv.setAdapter(adapter);

        MaterialButton createBtn = v.findViewById(R.id.taskCreateBtn);
        createBtn.setOnClickListener(btn ->
                // 用 parentFragmentManager 而不是 getChildFragmentManager —
                // 后者在底部 nav 切走 (TaskListFragment 被 replace) 时, 父
                // fragment 销毁连带 dialog 也被强制 detach, 但 dialog 内部
                // listener 闭包仍引用 fragment 实例, 后续事件 dispatch 到
                // detach 的 fragment 触发 NPE / 内存 anchor. parentFragmentManager
                // 让 dialog 跟随 Activity 完整生命周期管理.
                new CreateTaskDialogFragment().show(getParentFragmentManager(), "create_task"));

        MaterialButton clearDoneBtn = v.findViewById(R.id.taskClearDoneBtn);
        clearDoneBtn.setOnClickListener(btn -> {
            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setMessage(R.string.task_msg_confirm_clear_done)
                    .setPositiveButton(R.string.task_action_delete,
                            (d, w) -> vm.clearCompleted())
                    .setNegativeButton(R.string.action_cancel, null)
                    .create();
            LifecycleDialogs.showDismissOnDestroy(this, dialog);
        });

        vm.tasks().observe(getViewLifecycleOwner(), this::renderTasks);
    }

    @Override
    public void onResume() {
        super.onResume();
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        Long cur = app.getSession().getCurrentTaskId();
        adapter.setCurrentTaskId(cur == null ? -1L : cur);
    }

    private void renderTasks(List<InventoryTask> tasks) {
        adapter.submit(tasks);
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        Long cur = app.getSession().getCurrentTaskId();
        adapter.setCurrentTaskId(cur == null ? -1L : cur);

        int total = tasks == null ? 0 : tasks.size();
        int inProgress = 0, done = 0;
        if (tasks != null) {
            for (InventoryTask t : tasks) {
                if ("COMPLETED".equals(t.status)) done++;
                else inProgress++;  // IN_PROGRESS + PAUSED 都算"进行中"摘要
            }
        }
        totalNumber.setText(String.valueOf(total));
        progressNumber.setText(String.valueOf(inProgress));
        doneNumber.setText(String.valueOf(done));
        taskEmptyTv.setVisibility(total == 0 ? View.VISIBLE : View.GONE);
    }

    private void navigateToScan() {
        requireActivity().getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, new ScanFragment(), "scan")
                .commit();
        com.google.android.material.bottomnavigation.BottomNavigationView nav =
                requireActivity().findViewById(R.id.bottomNav);
        nav.setSelectedItemId(R.id.nav_scan);
    }

    /**
     * 打开扫描页只读查看指定任务 (任务卡"查看详情") — 通过 {@link ScanFragment#ARG_VIEW_TASK_ID}
     * 传 taskId, 不改 session、不启 RFID、禁用写操作; 底部导航同步高亮"扫描" tab。
     * 从扫描页点统计卡可再下钻到结果页看列表 / 导出。
     */
    private void navigateToScanView(long viewTaskId) {
        // 记入 session: 让底部 tab 新建的扫描/结果 fragment 也能保持查看上下文。
        ((InventoryApp) requireActivity().getApplication())
                .getSession().setViewingTaskId(viewTaskId);
        ScanFragment fragment = new ScanFragment();
        Bundle args = new Bundle();
        args.putLong(ScanFragment.ARG_VIEW_TASK_ID, viewTaskId);
        fragment.setArguments(args);
        requireActivity().getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, fragment, "scan")
                .addToBackStack(null)
                .commit();
        com.google.android.material.bottomnavigation.BottomNavigationView nav =
                requireActivity().findViewById(R.id.bottomNav);
        nav.getMenu().findItem(R.id.nav_scan).setChecked(true);
    }

    /**
     * ⋮ 弹 PopupMenu — 当前只有一项: "删除任务", 语义=释放资产回库存池.
     * 保留 PopupMenu 框架以备后续加更多动作 (导出/复制/查看详情 等).
     */
    private void showTaskMorePopup(InventoryTask task, View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.getMenuInflater().inflate(R.menu.popup_task_more, popup.getMenu());

        // 反射强制显示 icon (PopupMenu 默认不显示).
        // catch 必须有 Log.w, 不能完全静默 — AndroidX 版本变化时反射入口
        // 可能改名/移字段, 不留 trace 后期排障找不到根因.
        try {
            java.lang.reflect.Field f = popup.getClass().getDeclaredField("mPopup");
            f.setAccessible(true);
            Object helper = f.get(popup);
            if (helper != null) {
                helper.getClass().getMethod("setForceShowIcon", boolean.class)
                        .invoke(helper, true);
            }
        } catch (Exception e) {
            Log.w(TAG, "[TaskListFragment] forceShowIcon reflection failed (ignored) "
                    + "— AndroidX version may have moved mPopup field", e);
        }

        // "完成任务"仅在非已完成时显示 (已完成不可再完成)。
        android.view.MenuItem completeItem = popup.getMenu().findItem(R.id.menu_task_complete);
        if (completeItem != null) {
            completeItem.setVisible(!"COMPLETED".equals(task.status));
        }

        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.menu_task_complete) {
                confirmAndComplete(task);
                return true;
            }
            if (item.getItemId() == R.id.menu_task_delete) {
                confirmAndDelete(task);
                return true;
            }
            return false;
        });
        popup.show();
    }

    /**
     * 通用删除入口: 二次确认 → 调 vm.deleteTask → Toast 反馈释放条数.
     * 复用给卡片直接按钮和 ⋮ 弹窗两条路径.
     */
    private void confirmAndDelete(InventoryTask task) {
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setMessage(R.string.task_msg_confirm_delete)
                .setPositiveButton(R.string.task_action_delete, (d, w) ->
                        vm.deleteTask(task.id, (taskId, released) -> {
                            if (!isAdded()) return;
                            requireActivity().runOnUiThread(() -> {
                                if (!isAdded()) return;
                                Toast.makeText(requireContext(),
                                        getString(R.string.task_msg_release_done, released),
                                        Toast.LENGTH_SHORT).show();
                            });
                        }))
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        LifecycleDialogs.showDismissOnDestroy(this, dialog);
    }

    /** 完成任务: 二次确认(不可逆) → vm.completeTask → Toast。 */
    private void confirmAndComplete(InventoryTask task) {
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setMessage(R.string.task_msg_confirm_complete)
                .setPositiveButton(R.string.task_action_complete, (d, w) ->
                        vm.completeTask(task.id, () -> {
                            if (!isAdded()) return;
                            requireActivity().runOnUiThread(() -> {
                                if (!isAdded()) return;
                                Toast.makeText(requireContext(),
                                        R.string.task_msg_completed, Toast.LENGTH_SHORT).show();
                            });
                        }))
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        LifecycleDialogs.showDismissOnDestroy(this, dialog);
    }
}
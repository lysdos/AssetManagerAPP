package com.huaxia.inventory.ui.task;

import android.os.Bundle;
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
import java.util.List;

public class TaskListFragment extends Fragment {

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
                    if (!ok) return;
                    requireActivity().runOnUiThread(() -> navigateToScan());
                });
            }
            @Override public void onPauseTask(InventoryTask task) {
                vm.pauseTask(task.id, () -> requireActivity().runOnUiThread(() -> {
                    Toast.makeText(requireContext(),
                            getString(R.string.task_msg_paused, task.sourceFileName),
                            Toast.LENGTH_SHORT).show();
                }));
            }
            @Override public void onResumeTask(InventoryTask task) {
                vm.resumeTask(task.id, () -> requireActivity().runOnUiThread(() -> {
                    Toast.makeText(requireContext(),
                            getString(R.string.task_msg_resumed, task.sourceFileName),
                            Toast.LENGTH_SHORT).show();
                }));
            }
            @Override public void onViewTask(InventoryTask task) {
                // 已完成任务：切到扫描页查看盘点结果 (currentTaskId 不变)
                requireActivity().runOnUiThread(() -> navigateToScan());
            }
            @Override public void onDeleteTask(InventoryTask task) {
                new AlertDialog.Builder(requireContext())
                        .setMessage(R.string.task_msg_confirm_delete)
                        .setPositiveButton(R.string.task_action_delete, (d, w) -> vm.deleteTask(task.id))
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            }
            @Override public void onMoreClicked(InventoryTask task, String status, View anchor) {
                showTaskMorePopup(task, status, anchor);
            }
        });
        rv.setAdapter(adapter);

        MaterialButton createBtn = v.findViewById(R.id.taskCreateBtn);
        createBtn.setOnClickListener(btn ->
                new CreateTaskDialogFragment().show(getChildFragmentManager(), "create_task"));

        MaterialButton clearDoneBtn = v.findViewById(R.id.taskClearDoneBtn);
        clearDoneBtn.setOnClickListener(btn ->
                new AlertDialog.Builder(requireContext())
                        .setMessage(R.string.task_msg_confirm_clear_done)
                        .setPositiveButton(R.string.task_action_delete,
                                (d, w) -> vm.clearCompleted())
                        .setNegativeButton(R.string.action_cancel, null)
                        .show());

        vm.tasks().observe(getViewLifecycleOwner(), this::renderTasks);
    }

    @Override
    public void onResume() {
        super.onResume();
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        Long cur = app.session.getCurrentTaskId();
        adapter.setCurrentTaskId(cur == null ? -1L : cur);
    }

    private void renderTasks(List<InventoryTask> tasks) {
        adapter.submit(tasks);
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        Long cur = app.session.getCurrentTaskId();
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
     * ⋮ 弹 PopupMenu：按任务状态显示不同菜单项。
     *   - IN_PROGRESS: 仅"删除任务" (释放资产需要先暂停)
     *   - PAUSED / COMPLETED: "释放资产回库存池" + "删除任务"
     */
    private void showTaskMorePopup(InventoryTask task, String status, View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.getMenuInflater().inflate(R.menu.popup_task_more, popup.getMenu());
        // IN_PROGRESS 隐藏"释放资产"项 — 提示先暂停
        boolean canRelease = "PAUSED".equals(status) || "COMPLETED".equals(status);
        popup.getMenu().findItem(R.id.menu_task_release_to_pool).setVisible(canRelease);

        // 反射强制显示 icon (PopupMenu 默认不显示)
        try {
            java.lang.reflect.Field f = popup.getClass().getDeclaredField("mPopup");
            f.setAccessible(true);
            Object helper = f.get(popup);
            if (helper != null) {
                helper.getClass().getMethod("setForceShowIcon", boolean.class)
                        .invoke(helper, true);
            }
        } catch (Exception ignored) {}

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_task_release_to_pool) {
                confirmRelease(task);
                return true;
            }
            if (id == R.id.menu_task_delete) {
                new AlertDialog.Builder(requireContext())
                        .setMessage(R.string.task_msg_confirm_delete)
                        .setPositiveButton(R.string.task_action_delete, (d, w) -> vm.deleteTask(task.id))
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void confirmRelease(InventoryTask task) {
        // 先预览该任务下资产数, 让用户在二次确认对话框里看到具体数字
        // 这里直接 new Thread 而不是 vm 方法, 是为了避免再为个 count 加 VM API.
        new Thread(() -> {
            int n = ((InventoryApp) requireActivity().getApplication()).db.assetDao().countByTask(task.id);
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                if (n <= 0) {
                    Toast.makeText(requireContext(), R.string.task_msg_release_none,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                new AlertDialog.Builder(requireContext())
                        .setMessage(getString(R.string.task_msg_confirm_release, n))
                        .setPositiveButton(R.string.task_action_release_to_pool,
                                (d, w) -> doRelease(task))
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            });
        }).start();
    }

    private void doRelease(InventoryTask task) {
        vm.releaseTaskAssetsToPool(task.id, (taskId, count) ->
                requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(),
                        getString(R.string.task_msg_release_done, count),
                        Toast.LENGTH_SHORT).show()));
    }
}
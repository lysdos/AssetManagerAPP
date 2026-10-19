package com.huaxia.inventory.ui.task;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.util.DateUtil;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务卡片列表。
 *
 * <p>容器是普通 {@link LinearLayout} 而不是 RecyclerView —— RecyclerView 自带滚动, 以
 * {@code wrap_content} 嵌在 ScrollView 里时, 它的 auto-measure 拿"可用视口高度"当排布预算:
 * 内容一旦超过视口, 之后新增的卡片会被静默丢弃 (既不排布也不撑高, 滑到底也看不到)。
 * 任务数量是个位数, item 复用本来也无意义; 整页滚动交给外层 ScrollView。</p>
 */
public class TaskAdapter {

    public interface Listener {
        void onStartTask(InventoryTask task);
        void onPauseTask(InventoryTask task);
        void onResumeTask(InventoryTask task);
        void onViewTask(InventoryTask task);
        /**
         * ⋮ 按钮被点击 — 由宿主 fragment 弹 PopupMenu 处理具体菜单项。
         * @param anchor  弹菜单的锚点 view
         */
        void onMoreClicked(InventoryTask task, View anchor);
    }

    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_PAUSED = "PAUSED";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private final List<InventoryTask> data = new ArrayList<>();
    private final LinearLayout container;
    private final LayoutInflater inflater;
    private final Listener listener;
    private long currentTaskId = -1L;

    public TaskAdapter(LinearLayout container, Listener listener) {
        this.container = container;
        this.inflater = LayoutInflater.from(container.getContext());
        this.listener = listener;
    }

    public void submit(List<InventoryTask> list) {
        data.clear();
        if (list != null) data.addAll(list);
        rebuild();
    }

    public void setCurrentTaskId(long id) {
        if (currentTaskId != id) {
            currentTaskId = id;
            rebuild();
        }
    }

    /** 全量重建卡片。列表是个位数规模, 直接 removeAllViews + 重新 inflate 即可。 */
    private void rebuild() {
        container.removeAllViews();
        for (InventoryTask t : data) {
            View v = inflater.inflate(R.layout.view_task_card, container, false);
            bind(new VH(v), t);
            container.addView(v);
        }
    }

    private void bind(VH h, InventoryTask t) {
        h.name.setText(t.sourceFileName != null ? t.sourceFileName : "未命名任务");

        String status = t.status == null ? STATUS_IN_PROGRESS : t.status;
        bindStatus(h, status);
        bindProgress(h, t, status);
        bindActions(h, t, status);

        h.dept.setText(t.dept == null || t.dept.isEmpty()
                ? h.itemView.getContext().getString(R.string.task_dept_unlimited_fmt)
                : h.itemView.getContext().getString(R.string.task_dept_fmt, t.dept));

        h.date.setText(h.itemView.getContext().getString(
                R.string.task_meta_date_fmt, DateUtil.formatTimestamp(t.importedAt)));
        h.metaTotal.setText(h.itemView.getContext().getString(
                R.string.task_meta_total_fmt, Math.max(t.expectedCount, 0)));

        if (t.remark == null || t.remark.isEmpty()) {
            h.remark.setVisibility(View.GONE);
        } else {
            h.remark.setVisibility(View.VISIBLE);
            h.remark.setText(h.itemView.getContext().getString(
                    R.string.task_remark_fmt, t.remark));
        }

        h.more.setOnClickListener(v -> {
            if (listener != null) listener.onMoreClicked(t, v);
        });
    }

    private void bindStatus(VH h, String status) {
        if (STATUS_COMPLETED.equals(status)) {
            h.status.setText(R.string.task_status_completed);
            h.status.setBackgroundResource(R.drawable.bg_tag_green);
        } else if (STATUS_PAUSED.equals(status)) {
            h.status.setText(R.string.task_status_paused);
            h.status.setBackgroundResource(R.drawable.bg_tag_paused);
            h.status.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.textHint));
        } else {
            h.status.setText(R.string.task_status_in_progress);
            // HTML 设计稿 line 1421: 进行中用 tag-orange (橙)
            h.status.setBackgroundResource(R.drawable.bg_tag_orange);
            h.status.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.tagOrangeFg));
        }
    }

    private void bindProgress(VH h, InventoryTask t, String status) {
        int expected = Math.max(t.expectedCount, 0);
        int scanned = Math.max(t.scannedUniqueCount, 0);
        int pct = expected == 0 ? 0 : (int) Math.round(100.0 * scanned / expected);

        // 进度条颜色: 进行中蓝 / 已完成绿 / 已暂停灰 (HTML line 1411-1419)
        int barColor;
        if (STATUS_COMPLETED.equals(status)) barColor = R.color.statusFound;
        else if (STATUS_PAUSED.equals(status)) barColor = R.color.textDisabled;
        else barColor = R.color.colorPrimary;
        h.progressBar.setProgressTintList(ColorStateList.valueOf(
                ContextCompat.getColor(h.itemView.getContext(), barColor)));
        h.progressBar.setProgress(pct);

        h.progressLeft.setText(h.itemView.getContext().getString(
                R.string.task_progress_checked_fmt, scanned));
        h.progressRight.setText(h.itemView.getContext().getString(
                R.string.task_progress_pct_fmt, pct));
    }

    private void bindActions(VH h, InventoryTask t, String status) {
        if (STATUS_COMPLETED.equals(status)) {
            // HTML line 1426-1429: 只有"查看详情"
            h.primary.setText(R.string.task_action_view);
            h.primary.setEnabled(true);
            // 灰底 OutlinedButton (HTML .task-btn-detail)
            h.primary.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(h.itemView.getContext(), R.color.bgSurface)));
            h.primary.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.textHint));
            h.primary.setStrokeColorResource(R.color.borderDashed);
            h.primary.setStrokeWidth(1);
            h.primary.setOnClickListener(v -> {
                if (listener != null) listener.onViewTask(t);
            });
            h.secondary.setVisibility(View.GONE);
        } else if (STATUS_PAUSED.equals(status)) {
            // HTML line 1431-1434: 开始盘点(disabled 灰) + 恢复(绿实心)
            h.primary.setText(R.string.task_action_start);
            h.primary.setEnabled(false);
            h.primary.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(h.itemView.getContext(), R.color.textDisabled)));
            h.primary.setTextColor(ContextCompat.getColor(h.itemView.getContext(), android.R.color.white));
            h.primary.setOnClickListener(null);
            h.secondary.setVisibility(View.VISIBLE);
            h.secondary.setText(R.string.task_action_resume);
            h.secondary.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(h.itemView.getContext(), R.color.statusFound)));
            h.secondary.setTextColor(ContextCompat.getColor(h.itemView.getContext(), android.R.color.white));
            h.secondary.setStrokeWidth(0);
            h.secondary.setOnClickListener(v -> {
                if (listener != null) listener.onResumeTask(t);
            });
        } else {
            // IN_PROGRESS: HTML line 1438-1441: 开始盘点(蓝实心) + 暂停(橙边框)
            h.primary.setText(R.string.task_action_start);
            h.primary.setEnabled(true);
            h.primary.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(h.itemView.getContext(), R.color.colorPrimary)));
            h.primary.setTextColor(ContextCompat.getColor(h.itemView.getContext(), android.R.color.white));
            h.primary.setStrokeWidth(0);
            h.primary.setOnClickListener(v -> {
                if (listener != null) listener.onStartTask(t);
            });
            h.secondary.setVisibility(View.VISIBLE);
            h.secondary.setText(R.string.task_action_pause);
            // OutlinedButton 风格: 白底橙字橙边
            h.secondary.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(h.itemView.getContext(), android.R.color.white)));
            h.secondary.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.statusExtra));
            h.secondary.setStrokeColorResource(R.color.statusExtra);
            h.secondary.setStrokeWidth(1);
            h.secondary.setOnClickListener(v -> {
                if (listener != null) listener.onPauseTask(t);
            });
        }
    }

    /** 单张卡片的 view 引用。{@code itemView} 保留是为了绑定方法里统一取 context。 */
    static class VH {
        final View itemView;
        final TextView name, status, dept, date, metaTotal, remark;
        final TextView progressLeft, progressRight;
        final ProgressBar progressBar;
        final MaterialButton primary, secondary;
        final ImageButton more;

        VH(View v) {
            itemView = v;
            name = v.findViewById(R.id.taskNameTv);
            status = v.findViewById(R.id.taskStatusTv);
            dept = v.findViewById(R.id.taskDeptTv);
            date = v.findViewById(R.id.taskDateTv);
            metaTotal = v.findViewById(R.id.taskMetaTotalTv);
            remark = v.findViewById(R.id.taskRemarkTv);
            progressLeft = v.findViewById(R.id.taskProgressLeftTv);
            progressRight = v.findViewById(R.id.taskProgressRightTv);
            progressBar = v.findViewById(R.id.taskProgressBar);
            primary = v.findViewById(R.id.taskPrimaryBtn);
            secondary = v.findViewById(R.id.taskSecondaryBtn);
            more = v.findViewById(R.id.taskMoreBtn);
        }
    }
}

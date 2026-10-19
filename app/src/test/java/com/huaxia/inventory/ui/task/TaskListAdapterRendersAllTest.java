package com.huaxia.inventory.ui.task;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.huaxia.inventory.R;
import com.huaxia.inventory.data.entity.InventoryTask;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: 任务列表必须把<b>提交进来的每一个任务</b>都渲染成一张卡片, 即使卡片总高
 * 超过 ScrollView 视口。
 *
 * <p>历史 bug: 列表容器曾是 {@code RecyclerView} + {@code wrap_content} 嵌在 {@code ScrollView}
 * 里。RecyclerView 的 auto-measure 拿"可用视口高度"当排布预算 —— 内容一旦超过视口,
 * 之后新增的卡片会被静默丢弃 (既不排布也不撑高, 滑到底也看不到), 表现就是"建了 3 个任务
 * 只看得见 2 个"。修法: 换成普通 {@link LinearLayout}, 由 {@link TaskAdapter} 直接
 * inflate 卡片填进去, 整页滚动交给外层 ScrollView。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class TaskListAdapterRendersAllTest {

    /** 复现条件: 6 张卡片的总高远超视口 (旧实现下第 3 张起就没了)。 */
    @Test
    public void submit_rendersEveryTask_evenWhenCardsOverflowTheViewport() {
        View root = inflate();
        LinearLayout container = root.findViewById(R.id.taskListContainer);
        TaskAdapter adapter = new TaskAdapter(container, noopListener());

        adapter.submit(tasks(6));
        measureLayout(root);

        assertEquals("每个任务都必须渲染成一张卡片", 6, container.getChildCount());
        assertTrue("容器必须被撑到能装下全部卡片 (而不是卡在视口高度). containerH="
                        + container.getHeight() + " viewportH=" + scrollView(root).getHeight(),
                container.getHeight() > scrollView(root).getHeight());
    }

    @Test
    public void resubmit_replacesRenderedCards() {
        View root = inflate();
        LinearLayout container = root.findViewById(R.id.taskListContainer);
        TaskAdapter adapter = new TaskAdapter(container, noopListener());

        adapter.submit(tasks(3));
        assertEquals(3, container.getChildCount());

        adapter.submit(tasks(1));
        assertEquals(1, container.getChildCount());
    }

    @Test
    public void setCurrentTaskId_keepsAllCards() {
        View root = inflate();
        LinearLayout container = root.findViewById(R.id.taskListContainer);
        TaskAdapter adapter = new TaskAdapter(container, noopListener());
        adapter.submit(tasks(3));

        adapter.setCurrentTaskId(2L);

        assertEquals(3, container.getChildCount());
    }

    /** 卡片内容绑定在换容器后仍要正确: 状态 tag / 按钮态 / 备注。 */
    @Test
    public void cards_areBoundByStatusAndRemark() {
        View root = inflate();
        LinearLayout container = root.findViewById(R.id.taskListContainer);
        TaskAdapter adapter = new TaskAdapter(container, noopListener());

        InventoryTask completed = task(1, "COMPLETED");
        InventoryTask inProgress = task(2, "IN_PROGRESS");
        inProgress.remark = "补充说明";
        List<InventoryTask> list = new ArrayList<>();
        list.add(completed);
        list.add(inProgress);
        adapter.submit(list);

        View completedCard = container.getChildAt(0);
        assertEquals(root.getContext().getString(R.string.task_action_view),
                ((TextView) completedCard.findViewById(R.id.taskPrimaryBtn)).getText().toString());
        assertEquals(View.GONE,
                completedCard.findViewById(R.id.taskSecondaryBtn).getVisibility());

        View inProgressCard = container.getChildAt(1);
        assertEquals(root.getContext().getString(R.string.task_action_start),
                ((TextView) inProgressCard.findViewById(R.id.taskPrimaryBtn)).getText().toString());
        assertEquals(View.VISIBLE,
                inProgressCard.findViewById(R.id.taskSecondaryBtn).getVisibility());
        TextView remark = inProgressCard.findViewById(R.id.taskRemarkTv);
        assertEquals(View.VISIBLE, remark.getVisibility());
        assertEquals(root.getContext().getString(R.string.task_remark_fmt, "补充说明"),
                remark.getText().toString());

        // 没填备注的卡片: 备注行隐藏
        assertEquals(View.GONE,
                completedCard.findViewById(R.id.taskRemarkTv).getVisibility());
    }

    private static View inflate() {
        Context ctx = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(), R.style.Theme_HuaxiaInventory);
        return LayoutInflater.from(ctx).inflate(R.layout.fragment_task_list, null);
    }

    private static void measureLayout(View root) {
        int w = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY);
        int h = View.MeasureSpec.makeMeasureSpec(854, View.MeasureSpec.EXACTLY);
        root.measure(w, h);
        root.layout(0, 0, 480, 854);
    }

    private static ScrollView scrollView(View v) {
        if (v instanceof ScrollView) return (ScrollView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ScrollView s = scrollView(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
    }

    private static List<InventoryTask> tasks(int n) {
        List<InventoryTask> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) list.add(task(i, "IN_PROGRESS"));
        return list;
    }

    private static InventoryTask task(long id, String status) {
        InventoryTask t = new InventoryTask();
        t.id = id;
        t.sourceFileName = "任务" + id;
        t.status = status;
        t.importedAt = 1_700_000_000_000L + id;
        t.expectedCount = 10;
        t.scannedUniqueCount = 3;
        return t;
    }

    private static TaskAdapter.Listener noopListener() {
        return new TaskAdapter.Listener() {
            @Override public void onStartTask(InventoryTask task) {}
            @Override public void onPauseTask(InventoryTask task) {}
            @Override public void onResumeTask(InventoryTask task) {}
            @Override public void onViewTask(InventoryTask task) {}
            @Override public void onMoreClicked(InventoryTask task, View anchor) {}
        };
    }
}

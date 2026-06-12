package com.huaxia.inventory.ui.util;

import android.content.Context;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.LayoutRes;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import com.huaxia.inventory.R;

/**
 * 复用小工具：把 {@code view_tag_pill} 渲染并塞进 {@link LinearLayout} 的样板封装。
 *
 * <p>对应旧代码中 {@code ScanFragment#renderCategoryTags} 与
 * {@code ScanFragment#addPill} 重复出现的 inflate / setText / setBackgroundResource /
 * setTextColor / addView 流程。
 */
public final class TagPills {

    private TagPills() {
        // util class
    }

    /**
     * 渲染一个 pill，文本为 {@code text}，背景与前景色由资源 id 给出。
     *
     * @param container 父容器（pill 会被 add 进去）
     * @param text      pill 显示的文本
     * @param bgRes     pill 背景 drawable
     * @param fgRes     pill 文字颜色
     */
    public static void bind(LinearLayout container,
                            CharSequence text,
                            @DrawableRes int bgRes,
                            @ColorRes int fgRes) {
        Context ctx = container.getContext();
        TextView pill = (TextView) LayoutInflater.from(ctx)
                .inflate(R.layout.view_tag_pill, container, false);
        pill.setText(text);
        pill.setBackgroundResource(bgRes);
        pill.setTextColor(ContextCompat.getColor(ctx, fgRes));
        container.addView(pill);
    }

    /**
     * 渲染一个 pill，文本来自 string 资源并按 {@code tag_format_count} 模板拼接。
     *
     * @param container 父容器
     * @param labelRes  标签文本资源
     * @param count     计数
     * @param bgRes     pill 背景 drawable
     * @param fgRes     pill 文字颜色
     */
    public static void bindCount(LinearLayout container,
                                 @StringRes int labelRes,
                                 int count,
                                 @DrawableRes int bgRes,
                                 @ColorRes int fgRes) {
        Context ctx = container.getContext();
        bind(container, ctx.getString(R.string.tag_format_count, ctx.getString(labelRes), count), bgRes, fgRes);
    }
}

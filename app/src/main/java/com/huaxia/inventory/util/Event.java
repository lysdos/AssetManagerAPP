package com.huaxia.inventory.util;

/**
 * 一次性事件载荷, 供 LiveData 承载"瞬时事件"(Snackbar / 蜂鸣 / 清空输入等)。
 *
 * <p>Fragment 的 ViewModel 跨屏幕旋转存活, 其 LiveData 会保留"上次的值"; 旋转后新
 * View 重新 observe 会立刻收到旧值 → 重放事件 (重复 Snackbar、重复蜂鸣、再次清空
 * 输入)。用本包装: observer 取一次即置空, 重放时返回 {@code null}。</p>
 *
 * <p><b>只用于瞬时事件</b> —— 表示持续状态的 LiveData (如"最近扫码"、"查询结果")
 * 不要用它, 那些在旋转后本就该重新渲染。</p>
 */
public final class Event<T> {

    private final T content;
    private boolean handled;

    public Event(T content) {
        this.content = content;
    }

    /** 首次调用返回内容; 之后返回 {@code null} (表示已被消费, 不再重放)。 */
    public T getContentIfNotHandled() {
        if (handled) return null;
        handled = true;
        return content;
    }
}

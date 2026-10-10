package com.huaxia.inventory.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class DateUtil {
    private static final ThreadLocal<SimpleDateFormat> FMT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA));

    private DateUtil() {}

    public static String formatTimestamp(long millis) {
        return FMT.get().format(new Date(millis));
    }

    /**
     * 今日 00:00:00.000 的毫秒时间戳（本地时区）。
     * 供"今日新增"这类自然日窗口统计使用。
     */
    public static long startOfToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}

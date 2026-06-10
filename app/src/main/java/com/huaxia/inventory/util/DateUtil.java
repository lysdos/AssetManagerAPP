package com.huaxia.inventory.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DateUtil {
    private static final ThreadLocal<SimpleDateFormat> FMT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA));

    private DateUtil() {}

    public static String formatTimestamp(long millis) {
        return FMT.get().format(new Date(millis));
    }
}

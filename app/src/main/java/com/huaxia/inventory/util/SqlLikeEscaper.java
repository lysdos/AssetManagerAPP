package com.huaxia.inventory.util;

import androidx.annotation.Nullable;

/**
 * SQL LIKE 关键词转义 —— App 与 Web 共用，避免两套转义漂移。
 *
 * <p>{@code AssetDao.KEYWORD_WHERE} 使用 {@code ESCAPE '!'}，因此 {@code ! % _}
 * 三个字符必须转义成 {@code !! !% !_}。
 */
public final class SqlLikeEscaper {

    private SqlLikeEscaper() {}

    /** 空关键词返回 "%"（匹配全部），否则返回 "%转义后关键词%"。 */
    public static String likePattern(@Nullable String keyword) {
        if (keyword == null || keyword.isEmpty()) return "%";
        String escaped = keyword.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }
}

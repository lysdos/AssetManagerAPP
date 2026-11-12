package com.huaxia.inventory.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class SqlLikeEscaperTest {

    @Test
    public void emptyKeywordBecomesMatchAll() {
        assertThat(SqlLikeEscaper.likePattern(null)).isEqualTo("%");
        assertThat(SqlLikeEscaper.likePattern("")).isEqualTo("%");
    }

    @Test
    public void plainKeywordIsWrapped() {
        assertThat(SqlLikeEscaper.likePattern("笔记本")).isEqualTo("%笔记本%");
    }

    @Test
    public void wildcardsAreEscaped() {
        assertThat(SqlLikeEscaper.likePattern("50%")).isEqualTo("%50!%%");
        assertThat(SqlLikeEscaper.likePattern("a_b")).isEqualTo("%a!_b%");
        assertThat(SqlLikeEscaper.likePattern("!x")).isEqualTo("%!!x%");
    }
}

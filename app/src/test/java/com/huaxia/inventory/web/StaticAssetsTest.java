package com.huaxia.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class StaticAssetsTest {

    @Test
    public void mimeOf_knownTypes() {
        assertThat(StaticAssets.mimeOf("index.html")).isEqualTo("text/html; charset=utf-8");
        assertThat(StaticAssets.mimeOf("app.css")).isEqualTo("text/css; charset=utf-8");
        assertThat(StaticAssets.mimeOf("app.js")).isEqualTo("application/javascript; charset=utf-8");
        assertThat(StaticAssets.mimeOf("logo.svg")).isEqualTo("image/svg+xml");
    }

    @Test
    public void mimeOf_unknownFallsBackToOctetStream() {
        assertThat(StaticAssets.mimeOf("data.bin")).isEqualTo("application/octet-stream");
    }

    @Test
    public void isSafe_rejectsTraversal() {
        assertThat(StaticAssets.isSafe("/js/app.js")).isTrue();
        assertThat(StaticAssets.isSafe("/../secret")).isFalse();
        assertThat(StaticAssets.isSafe("/js/../../etc/passwd")).isFalse();
        assertThat(StaticAssets.isSafe("/js/..%2Fapp.js")).isFalse();
    }
}

package com.huaxia.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RunWith(RobolectricTestRunner.class)
public class WebAssetsTest {

    private static String readAsset(String path) throws IOException {
        Context ctx = ApplicationProvider.getApplicationContext();
        try (InputStream in = ctx.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        }
    }

    @Test
    public void indexHtml_exists() throws Exception {
        assertThat(readAsset("web/index.html")).contains("资产管家");
    }

    @Test
    public void indexHtml_referencesOnlyExistingFiles() throws Exception {
        String html = readAsset("web/index.html");
        List<String> missing = new ArrayList<>();
        Matcher m = Pattern.compile("/static/([A-Za-z0-9_./-]+)").matcher(html);
        while (m.find()) {
            try (InputStream ignored = ApplicationProvider.getApplicationContext()
                    .getAssets().open("web/" + m.group(1))) {
                // 存在即可
            } catch (IOException e) {
                missing.add(m.group(1));
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    public void dashboardJs_definesRender() throws Exception {
        assertThat(readAsset("web/js/dashboard.js")).contains("export async function render");
    }

    @Test
    public void tokensCss_declaresDarkThemeOverride() throws Exception {
        String css = readAsset("web/css/tokens.css");
        assertThat(css).contains("[data-theme=\"dark\"]");
        assertThat(css).contains("--color-accent");
    }
}

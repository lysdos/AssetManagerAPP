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

    @Test
    public void tasksJs_definesListAndDetail() throws Exception {
        String js = readAsset("web/js/tasks.js");
        assertThat(js).contains("export async function renderList");
        assertThat(js).contains("export async function renderDetail");
    }

    @Test
    public void appJs_registersAllRoutes() throws Exception {
        String js = readAsset("web/js/app.js");
        assertThat(js).contains("'/tasks'");
        assertThat(js).contains("'/tasks/:id'");
        assertThat(js).contains("'/assets'");
        assertThat(js).contains("'/import'");
        assertThat(js).contains("'/report/:id'");
    }

    /**
     * 防回归: 路由切段必须同时剥掉可选的 "#" 和可选的 "/"。
     *
     * <p>注册时传 "/tasks"，location.hash 是 "#/tasks"。若正则只写 {@code ^#\/?}，
     * "/tasks" 会切出 ["", "tasks"]（多一个空段），与 ["tasks"] 长度不等，
     * 所有路由永远匹配不上 —— 表现为点导航链接没反应、页面空白。
     */
    @Test
    public void routerJs_stripsOptionalHashAndSlash() throws Exception {
        String js = readAsset("web/js/router.js");
        assertThat(js)
                .as("router split() must use /^#?\\/?/ so both '/tasks' and '#/tasks' "
                        + "produce [\"tasks\"]")
                .contains("/^#?\\/?/");
    }
}

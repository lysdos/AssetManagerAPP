package com.huaxia.inventory;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: {@code MainActivity.showFragment} (底部导航平级跳转) 必须<b>先清空返回栈</b>
 * 再 {@code replace}。
 *
 * <p>否则从 {@code addToBackStack} 的页面 (任务卡"查看详情"→结果页) 切到别的 tab 后,
 * 按返回会回到旧 tab 的 Fragment, 而底部导航高亮停在当前 tab → 状态错位。</p>
 */
public class MainActivityNavBackStackTest {

    @Test
    public void showFragment_clearsBackStackBeforeReplace() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File f = new File(moduleDir, "src/main/java/com/huaxia/inventory/MainActivity.java");
        if (!f.exists()) {
            f = new File(moduleDir, "app/src/main/java/com/huaxia/inventory/MainActivity.java");
        }
        assertTrue("source not found: " + f.getAbsolutePath(), f.exists());
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);

        String body = extractMethodBody(text, "private boolean showFragment(");
        assertTrue("showFragment body not found. Source:\n" + text, body.length() > 0);

        int pop = body.indexOf("popBackStack(");
        int replace = body.indexOf("replace(");
        assertTrue("showFragment must call popBackStack(...). Body:\n" + body, pop >= 0);
        assertTrue("popBackStack must precede replace. Body:\n" + body, pop < replace);
        assertTrue("showFragment must clear the whole back stack "
                + "(POP_BACK_STACK_INCLUSIVE). Body:\n" + body,
                body.contains("POP_BACK_STACK_INCLUSIVE"));
    }

    private static String extractMethodBody(String text, String signature) {
        int sigStart = text.indexOf(signature);
        if (sigStart < 0) return "";
        int braceStart = text.indexOf('{', sigStart);
        if (braceStart < 0) return "";
        int depth = 1;
        int i = braceStart + 1;
        while (i < text.length() && depth > 0) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        if (depth != 0) return "";
        return text.substring(braceStart + 1, i - 1);
    }
}

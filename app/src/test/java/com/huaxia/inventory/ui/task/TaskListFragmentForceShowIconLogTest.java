package com.huaxia.inventory.ui.task;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: TaskListFragment.showTaskMorePopup 反射 PopupMenu.mPopup 调用
 * setForceShowIcon, catch (Exception ignored) 必须改为 catch (Exception e)
 * + Log.w — AndroidX 版本升级时反射字段改名, 不留 trace 排障时找不到根因.
 */
public class TaskListFragmentForceShowIconLogTest {

    @Test
    public void forceShowIconReflection_catchHasLogW() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/task/TaskListFragment.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/task/TaskListFragment.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        String popBody = extractMethodBody(text,
                "private void showTaskMorePopup(");
        assertTrue(popBody.length() > 0);
        // 必须出现 catch + Log.w
        assertTrue("showTaskMorePopup must wrap reflection in try { } catch (Exception e) "
                + "{ Log.w(TAG, ..., e) } — not silent ignored. Body:\n" + popBody,
                popBody.contains("catch (Exception e)")
                        && popBody.contains("Log.w("));
        assertTrue("catch body must NOT be 'catch (Exception ignored) {}' — that "
                + "swallows the trace completely. Body:\n" + popBody,
                !popBody.contains("catch (Exception ignored)"));
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

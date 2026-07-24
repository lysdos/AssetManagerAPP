package com.huaxia.inventory.ui.result;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: ResultFragment.doExport 错误路径 Snackbar 必须用固定文案
 * (R.string.err_export_failed), 不再传 e.getMessage() 给用户 —
 * 内部异常 (FileNotFoundException/SAF URI/SQL 报错) 会把本地路径
 * 与查询字符串暴露到生产 Snackbar.
 */
public class ErrExportNoLeakInternalTest {

    @Test
    public void doExport_catchBlock_doesNotPassExceptionMessageToSnackbar() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 找 doExport 的 catch (Exception) e 块
        int catchIdx = text.indexOf("} catch (Exception e) {", text.indexOf("private void doExport("));
        assertTrue("doExport must have a catch (Exception) block. Source:\n" + text,
                catchIdx >= 0);
        // extract body until matching close brace
        int braceStart = text.indexOf('{', catchIdx);
        int depth = 1;
        int i = braceStart + 1;
        while (i < text.length() && depth > 0) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        if (depth != 0) return;
        String catchBody = text.substring(braceStart + 1, i - 1);

        // Snackbar 行不能传 e.getMessage()
        assertFalse("doExport Snackbar MUST NOT call Snackbar.make(... e.getMessage() "
                + "— leaks URI / path / SQL state to user. Body:\n" + catchBody,
                catchBody.contains("e.getMessage()")
                        && catchBody.contains("Snackbar.make"));

        // 仍要 Log.e 让运维诊断
        assertTrue("doExport catch should Log.e for ops diagnostics. Body:\n"
                + catchBody, catchBody.contains("Log.e(TAG,"));
    }

    @Test
    public void stringsXml_err_export_failed_hasNoPlaceholder() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir, "src/main/res/values/strings.xml");
        if (!source.exists()) {
            source = new File(moduleDir, "app/src/main/res/values/strings.xml");
        }
        assertTrue("strings.xml not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // err_export_failed 必须是固定文案, 不含 %1$s 占位 (否则会传 e.getMessage 拼进去)
        int idx = text.indexOf("err_export_failed");
        assertTrue("err_export_failed must exist. Source:\n" + text, idx >= 0);
        int gtIdx = text.indexOf('>', idx);
        int ltIdx = text.indexOf("</string>", gtIdx);
        String value = text.substring(gtIdx + 1, ltIdx);
        assertFalse("err_export_failed must not have a placeholder (we no longer "
                + "pass e.getMessage to user). Value:\n" + value,
                value.contains("%1") || value.contains("%s"));
    }
}

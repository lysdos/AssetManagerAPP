package com.huaxia.inventory.ui.import_;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: 导入弹窗的 {@code parsedFile} / {@code parseError} observer 必须带
 * "本次弹窗内真的选过文件" 守卫。
 *
 * <p>背景: {@code importVm} 是 activity 级 ({@code new ViewModelProvider(requireActivity())}),
 * 其 LiveData 会保留上一次的值。导入完成 1.2s 后弹窗自动 dismiss, 再次打开时新实例的
 * observer 会立刻收到旧值 —— 表现为"开始导入"按钮在未选文件时就是亮的 (点了没反应),
 * 以及上一次的解析错误被原样再显示一遍。</p>
 *
 * <p>守卫条件用 {@code pickedUri == null} 而非 {@code commited} —— 后者要到
 * {@code startCommit()} 才置 true, 会把正常解析流程一起挡掉。</p>
 */
public class ImportDialogStaleStateGuardTest {

    @Test
    public void parsedFileAndParseError_observers_guardOnPickedUri() throws Exception {
        String text = read("src/main/java/com/huaxia/inventory/ui/import_/ImportDialogFragment.java");

        String parsedBody = extractFollowingLambdaBody(text, "importVm.parsedFile().observe(");
        assertTrue("importVm.parsedFile().observe(...) callback not found. Source:\n" + text,
                parsedBody.length() > 0);
        assertTrue("parsedFile observer must ignore the replayed value when no file was "
                + "picked in this dialog instance. Body:\n" + parsedBody,
                parsedBody.contains("pickedUri == null"));

        String errorBody = extractFollowingLambdaBody(text, "importVm.parseError().observe(");
        assertTrue("importVm.parseError().observe(...) callback not found. Source:\n" + text,
                errorBody.length() > 0);
        assertTrue("parseError observer must ignore the replayed value when no file was "
                + "picked in this dialog instance. Body:\n" + errorBody,
                errorBody.contains("pickedUri == null"));
    }

    private static String read(String rel) throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File f = new File(moduleDir, rel);
        if (!f.exists()) f = new File(moduleDir, "app/" + rel);
        assertTrue("source not found: " + f.getAbsolutePath(), f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static String extractFollowingLambdaBody(String text, String signature) {
        int idx = text.indexOf(signature);
        if (idx < 0) return "";
        int arrowIdx = text.indexOf("->", idx);
        if (arrowIdx < 0) return "";
        int braceStart = text.indexOf('{', arrowIdx);
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

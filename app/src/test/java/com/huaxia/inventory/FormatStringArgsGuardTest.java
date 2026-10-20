package com.huaxia.inventory;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: 带占位符的字符串资源必须经 {@code getString(...)} 传参引用, 不能把裸资源 id
 * 交给 {@code Snackbar.make(view, @StringRes, ...)} / {@code Toast} / {@code setText(@StringRes)} ——
 * 那些 API 内部只做 {@code getText(resId)} (不格式化), 占位符会原样显示给用户。
 *
 * <p>历史 bug: {@code ScanFragment.confirmResetProgress} 在"无当前任务"分支写
 * {@code Snackbar.make(requireView(), R.string.scan_clear_task_failed, ...)}, 而该串是
 * {@code 重置失败：%1$s} —— 界面直接出现字面量 {@code %1$s}。</p>
 *
 * <p>实现: 先从 strings.xml 收集所有值含 {@code %1$} 的资源名, 再扫 main 源码里
 * {@code R.string.<name>} 的引用, 要求同一语句内 (向前最近的 {@code ; { } =} 之前)
 * 能找到 {@code getString(}。注释行 (以 {@code *} / {@code //} / {@code /*} 开头) 跳过。</p>
 *
 * <p>已知局限: 若某处把含占位符的资源 id 先存进变量、稍后 {@code getString(var, args)},
 * 会被误报 —— 当前代码里用于该模式的只有无占位符的 {@code result_stat_*}。误报时
 * 人工确认即可, 不要为了让它闭嘴而弱化断言。</p>
 */
public class FormatStringArgsGuardTest {

    private static final Pattern STRING_ENTRY =
            Pattern.compile("<string name=\"([^\"]+)\">(.*?)</string>", Pattern.DOTALL);

    @Test
    public void placeholderStrings_areNeverReferencedByRawResourceId() throws Exception {
        Set<String> placeholderNames = collectPlaceholderStringNames();
        assertTrue("strings.xml 里没解析到任何含 %1$ 的字符串 —— 解析逻辑坏了?",
                placeholderNames.size() > 0);

        List<File> sources = mainJavaSources();
        assertTrue("没找到 main 源码 —— 路径解析坏了?", sources.size() > 0);

        List<String> violations = new ArrayList<>();
        for (File f : sources) {
            String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            scan(src, placeholderNames, f.getName(), violations);
        }

        assertTrue("带占位符的字符串被裸资源 id 引用 (占位符会原样显示给用户):\n"
                        + String.join("\n", violations)
                        + "\n改用 getString(R.string.x, args...) 或在 strings.xml 里去掉占位符.",
                violations.isEmpty());
    }

    private static void scan(String src, Set<String> placeholderNames,
                             String fileName, List<String> violations) {
        int idx = 0;
        while ((idx = src.indexOf("R.string.", idx)) != -1) {
            int nameStart = idx + "R.string.".length();
            int nameEnd = nameStart;
            while (nameEnd < src.length()) {
                char c = src.charAt(nameEnd);
                if (!Character.isLetterOrDigit(c) && c != '_') break;
                nameEnd++;
            }
            String name = src.substring(nameStart, nameEnd);
            if (placeholderNames.contains(name) && !isCommentLine(src, idx)) {
                int getStringIdx = src.lastIndexOf("getString(", idx);
                int boundary = Math.max(
                        Math.max(src.lastIndexOf(';', idx), src.lastIndexOf('{', idx)),
                        Math.max(src.lastIndexOf('}', idx), src.lastIndexOf('=', idx)));
                if (getStringIdx < boundary) {
                    violations.add("  " + fileName + ":" + lineOf(src, idx)
                            + "  R.string." + name);
                }
            }
            idx = Math.max(nameEnd, idx + 1);
        }
    }

    private static boolean isCommentLine(String src, int idx) {
        int lineStart = src.lastIndexOf('\n', idx) + 1;
        String line = src.substring(lineStart, idx).trim();
        return line.startsWith("*") || line.startsWith("//") || line.startsWith("/*");
    }

    private static int lineOf(String src, int idx) {
        int line = 1;
        for (int i = 0; i < idx && i < src.length(); i++) {
            if (src.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static Set<String> collectPlaceholderStringNames() throws Exception {
        File stringsXml = resolve("src/main/res/values/strings.xml");
        assertTrue("strings.xml not found: " + stringsXml.getAbsolutePath(), stringsXml.exists());
        String text = new String(Files.readAllBytes(stringsXml.toPath()), StandardCharsets.UTF_8);
        Set<String> names = new HashSet<>();
        Matcher m = STRING_ENTRY.matcher(text);
        while (m.find()) {
            if (m.group(2).contains("%1$")) names.add(m.group(1));
        }
        return names;
    }

    private static List<File> mainJavaSources() throws Exception {
        File root = resolve("src/main/java");
        assertTrue("main java dir not found: " + root.getAbsolutePath(), root.isDirectory());
        List<File> out = new ArrayList<>();
        Path base = Paths.get(root.getAbsolutePath());
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> out.add(p.toFile()));
        }
        return out;
    }

    /** Gradle 测试 JVM 的 user.dir 是 module 目录 (…/huaxiaapp/app); 兜底再试仓库根。 */
    private static File resolve(String relUnderModule) {
        File moduleDir = new File(System.getProperty("user.dir"));
        File f = new File(moduleDir, relUnderModule);
        if (!f.exists()) f = new File(moduleDir, "app/" + relUnderModule);
        return f;
    }
}

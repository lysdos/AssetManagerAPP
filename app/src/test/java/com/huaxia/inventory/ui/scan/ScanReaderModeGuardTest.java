package com.huaxia.inventory.ui.scan;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: ScanViewModel.onEpcScanned 在未绑定任务 ({@code currentTaskId == -1L}) 时
 * 必须走"读码模式"分支 — 只把编码 post 到 {@code latestScan}, <b>不</b>落库。
 *
 * <p>否则扫描页无任务时点"开始盘点"会写入 {@code taskId=-1L} 的孤儿 ScanRecord:
 * 任何 {@code WHERE taskId=} 视图都查不到, 且 {@code deleteOrphans()} 只清
 * {@code matchedAssetId IS NOT NULL} 的行, 这类行永不被回收 → 无界增长。</p>
 */
public class ScanReaderModeGuardTest {

    @Test
    public void onEpcScanned_hasNoTaskReaderBranch_beforeDbWrite() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        String body = extractMethodBody(text, "public void onEpcScanned(");
        assertTrue("onEpcScanned body not found", body.length() > 0);

        int guard = body.indexOf("taskId == -1L");
        assertTrue("onEpcScanned must guard on currentTaskId == -1L (读码模式). Body:\n" + body,
                guard >= 0);

        // 守卫必须在内存去重 add 与 DB 写事务之前 — 否则仍会落库。
        int addIdx = body.indexOf("scannedEpcSet.add(");
        int txIdx = body.indexOf("runInTransaction(");
        assertTrue("reader guard must precede scannedEpcSet.add(. Body:\n" + body,
                addIdx > guard);
        assertTrue("reader guard must precede runInTransaction(. Body:\n" + body,
                txIdx > guard);

        // 守卫分支内必须 post latestScan (把编码广播给 UI) 且立即 return。
        int retIdx = body.indexOf("return", guard);
        assertTrue("reader guard branch must return. Body:\n" + body, retIdx > guard);
        String guardBranch = body.substring(guard, retIdx);
        assertTrue("reader guard branch must latestScan.postValue(...) (广播编码, 不落库). "
                + "Branch:\n" + guardBranch,
                guardBranch.contains("latestScan.postValue("));
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

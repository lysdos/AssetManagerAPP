package com.huaxia.inventory.ui.scan;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: {@code resetScanProgress} 必须<b>先删 ExtraClaim 再删 ScanRecord</b>。
 *
 * <p>顺序错误是静默失败: {@code ExtraClaimDao.deleteByTask} 的谓词为
 * {@code scanRecordId IN (SELECT id FROM ScanRecord WHERE taskId=:taskId)}。若先删
 * ScanRecord, 子查询变空 → 补全信息一条都删不掉, 留孤儿; 又因 ScanRecord 主键未用
 * AUTOINCREMENT (rowid 会复用), 残留补全可能被重扫的新标签挂上, 显示错误的领用人/部门。</p>
 */
public class ScanResetOrderGuardTest {

    @Test
    public void resetScanProgress_deletesExtraClaimBeforeScanRecord() throws Exception {
        String text = readFile("src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        String body = extractMethodBody(text, "public void resetScanProgress()");
        assertTrue("resetScanProgress body not found. Source:\n" + text, body.length() > 0);

        String lambda = extractFollowingLambdaBody(body, body.indexOf("runInTransaction"));
        assertTrue("runInTransaction lambda not found. Body:\n" + body, lambda.length() > 0);

        int claimIdx = lambda.indexOf("extraClaimDao().deleteByTask");
        int scanIdx = lambda.indexOf("scanRecordDao().deleteByTask");
        assertTrue("resetScanProgress must delete ExtraClaims. Lambda:\n" + lambda,
                claimIdx >= 0);
        assertTrue("resetScanProgress must delete ScanRecords. Lambda:\n" + lambda,
                scanIdx >= 0);
        assertTrue("ExtraClaim 必须先于 ScanRecord 删除 (deleteByTask 子查询依赖 ScanRecord 尚存). "
                + "Lambda:\n" + lambda, claimIdx < scanIdx);
    }

    private static String readFile(String rel) throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File f = new File(moduleDir, rel);
        if (!f.exists()) f = new File(moduleDir, "app/" + rel);
        assertTrue("source not found: " + f.getAbsolutePath(), f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
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

    private static String extractFollowingLambdaBody(String text, int startIdx) {
        int arrowIdx = text.indexOf("->", startIdx);
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

package com.huaxia.inventory.ui.scan;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归 (职责正交): "重置扫描进度" 清扫描记录 + 盘盈补全, 并回滚编辑过的资产字段 + 清盘点时间,
 * 但 <b>不动</b> 任务 status / session; "暂停"/"恢复" 只切 status + session, <b>不动</b>
 * 扫描记录 / 资产 —— 两者互不干扰。
 */
public class ResetScanProgressOrthogonalityTest {

    @Test
    public void resetScanProgress_doesNotTouchStatusNorSession() throws Exception {
        String text = readFile("src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        String body = extractMethodBody(text, "public void resetScanProgress()");
        assertTrue("resetScanProgress body not found. Source:\n" + text, body.length() > 0);

        // 确实动扫描记录
        assertTrue("resetScanProgress must delete ScanRecords. Body:\n" + body,
                body.contains("scanRecordDao().deleteByTask"));
        assertTrue("resetScanProgress must delete ExtraClaims. Body:\n" + body,
                body.contains("extraClaimDao().deleteByTask"));
        // 不动 status
        assertFalse("resetScanProgress must NOT change task status. Body:\n" + body,
                body.contains(".status"));
        // 不动 session
        assertFalse("resetScanProgress must NOT touch session. Body:\n" + body,
                body.contains("getSession().clear()"));
    }

    @Test
    public void pauseResume_touchOnlyStatusAndSession_notScans() throws Exception {
        String text = readFile("src/main/java/com/huaxia/inventory/service/TaskService.java");
        for (String sig : new String[]{"public void pauseTask(", "public void resumeTask("}) {
            String body = extractMethodBody(text, sig);
            assertTrue(sig + " body not found. Source:\n" + text, body.length() > 0);
            assertTrue(sig + " must set status. Body:\n" + body, body.contains("t.status ="));
            assertFalse(sig + " must NOT touch ScanRecords. Body:\n" + body,
                    body.contains("scanRecordDao"));
            assertFalse(sig + " must NOT touch ExtraClaims. Body:\n" + body,
                    body.contains("extraClaimDao"));
            assertFalse(sig + " must NOT touch Assets. Body:\n" + body,
                    body.contains("assetDao"));
        }
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
}

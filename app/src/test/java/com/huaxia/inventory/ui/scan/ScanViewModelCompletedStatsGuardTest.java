package com.huaxia.inventory.ui.scan;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 防回归：扫描页统计对「已完成任务」必须读冻结快照，不能查实时表。
 *
 * <p>完成任务后资产已退回库存池、扫描记录已删除。若 {@code recomputeStats} 仍走
 * {@code scanRecordDao.aggregateStats} / {@code assetDao.countByTask}，扫描页会显示
 * 「已盘 0、盘亏=应盘、盘盈 0」。</p>
 *
 * <p>更严重的是 {@code inventoryTaskDao.recomputeScannedCount} 会把任务行上的
 * {@code scannedUniqueCount} 冲成 0 —— 只要在扫描页看一眼已完成任务，任务列表的
 * 进度数字就被污染了。</p>
 */
public class ScanViewModelCompletedStatsGuardTest {

    @Test
    public void recomputeStats_readsSnapshotForCompletedTask() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File src = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        if (!src.exists()) {
            src = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        }
        assertTrue("ScanViewModel.java not found: " + src, src.exists());
        String text = new String(Files.readAllBytes(src.toPath()), StandardCharsets.UTF_8);

        int sig = text.indexOf("private void recomputeStats()");
        assertTrue("recomputeStats not found", sig >= 0);
        int brace = text.indexOf('{', sig);
        int depth = 0;
        int i = brace;
        while (i < text.length()) {
            if (text.charAt(i) == '{') depth++;
            else if (text.charAt(i) == '}') {
                depth--;
                if (depth == 0) break;
            }
            i++;
        }
        String body = text.substring(brace, i + 1);

        assertTrue("recomputeStats must branch on COMPLETED status. Body:\n" + body,
                body.contains("\"COMPLETED\".equals(task.status)"));

        int completedBranch = body.indexOf("\"COMPLETED\".equals(task.status)");
        int recomputeScanned = body.indexOf("recomputeScannedCount(");
        assertTrue("recomputeScannedCount must run only AFTER the COMPLETED branch "
                + "(it would zero out scannedUniqueCount on a completed task). Body:\n" + body,
                recomputeScanned > completedBranch);

        assertTrue("recomputeStats must read counts from ResultRepository for completed "
                + "tasks. Body:\n" + body,
                body.contains("ResultRepository"));
    }
}

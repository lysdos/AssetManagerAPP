package com.huaxia.inventory.ui.result;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: 已完成任务结果页只读 —— {@code ResultFragment} 必须
 * (a) 由 {@code viewTaskId >= 0} 推导 readOnly, (b) 把 readOnly 传给两个详情弹窗。
 *
 * <p>前提: {@code ARG_VIEW_TASK_ID} 只由"任务卡查看详情"传入, 而它只对已完成任务显示,
 * 且完成任务时已清 session —— 故 view 路径与当前任务路径互斥, 该信号可靠。</p>
 */
public class ResultFragmentReadOnlyTest {

    @Test
    public void resultFragment_derivesReadOnlyFromViewTaskId_andPassesDown() throws Exception {
        String text = read("src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java");
        assertTrue("readOnly must be derived from viewTaskId. Source:\n" + text,
                text.contains("readOnly = viewTaskId >= 0L"));
        assertTrue("showAssetDetailDialog must pass readOnly. Source:\n" + text,
                text.contains("AssetDetailDialog.show(this, asset, isMatched, readOnly,"));
        assertTrue("showExtraDetailDialog must pass readOnly. Source:\n" + text,
                text.contains("ExtraDetailDialog.show(this, data, readOnly,"));
    }

    private static String read(String rel) throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File f = new File(moduleDir, rel);
        if (!f.exists()) f = new File(moduleDir, "app/" + rel);
        assertTrue("source not found: " + f.getAbsolutePath(), f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}

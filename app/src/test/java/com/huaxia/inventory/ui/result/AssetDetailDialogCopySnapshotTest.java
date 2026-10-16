package com.huaxia.inventory.ui.result;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: {@code AssetDetailDialog.copyAsset} 必须复制全部 7 个快照列。
 *
 * <p>它是整行 {@code @Update} 的唯一生产调用方 —— 漏复制任何一列, 结果页每次保存都会
 * 把该列的 {@code orig_*} 快照清成 NULL, 破坏"删除/重置回滚"。</p>
 */
public class AssetDetailDialogCopySnapshotTest {

    private static final String[] SNAPSHOT_FIELDS = {
            "origEpc", "origAssetType", "origAssetModel", "origSerialNumber",
            "origCustodian", "origDept", "origRawRowJson",
    };

    @Test
    public void copyAsset_copiesAllSnapshotColumns() throws Exception {
        String text = read("src/main/java/com/huaxia/inventory/ui/result/AssetDetailDialog.java");
        String body = extractMethodBody(text, "private static Asset copyAsset(");
        assertTrue("copyAsset body not found. Source:\n" + text, body.length() > 0);
        for (String f : SNAPSHOT_FIELDS) {
            assertTrue("copyAsset must copy " + f + " = source." + f + " "
                            + "(漏了 → update 整行 REPLACE 会清空快照). Body:\n" + body,
                    body.contains("copy." + f + " = source." + f));
        }
    }

    private static String read(String rel) throws Exception {
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

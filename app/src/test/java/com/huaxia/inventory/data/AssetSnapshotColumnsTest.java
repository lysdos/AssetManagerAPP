package com.huaxia.inventory.data;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: {@code Asset} 必须声明 7 个"快照列"。
 *
 * <p>这组列支撑"划拨建快照 → 进行中编辑=草稿 → 完成=提交 → 删除/重置=回滚"语义:
 * 建任务划拨时 {@code assignPoolToTask} 写它们, 删除/重置时 {@code revertEditedFields}
 * 从它们还原。{@code Asset} 实体少一列, Room 编译期就会与 {@code rawRowJson} 等
 * 现有列对不上 (prepare 阶段报错), 且回滚会静默漏字段。</p>
 *
 * <p>列名必须与 {@code AssetDao} 里 {@code assignPoolToTask} / {@code revertEditedFields}
 * 的 SQL 严格一致 —— 改任一侧都要同步另一侧。</p>
 */
public class AssetSnapshotColumnsTest {

    /** 快照列的列名 (与 Asset.java @ColumnInfo 严格一致). */
    private static final String[] SNAPSHOT_COLUMNS = {
            "orig_epc", "orig_asset_type", "orig_asset_model", "orig_serial_number",
            "orig_custodian", "orig_dept", "orig_raw_row_json",
    };

    @Test
    public void assetDeclaresSevenSnapshotColumns() throws Exception {
        String text = read("src/main/java/com/huaxia/inventory/data/entity/Asset.java");
        for (String col : SNAPSHOT_COLUMNS) {
            assertTrue("Asset must declare @ColumnInfo(name = \"" + col + "\"). Source:\n" + text,
                    text.contains("@ColumnInfo(name = \"" + col + "\")"));
        }
    }

    private static String read(String rel) throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File f = new File(moduleDir, rel);
        if (!f.exists()) f = new File(moduleDir, "app/" + rel);
        assertTrue("source not found: " + f.getAbsolutePath(), f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}

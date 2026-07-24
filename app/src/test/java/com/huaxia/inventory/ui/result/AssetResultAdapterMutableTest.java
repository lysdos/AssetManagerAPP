package com.huaxia.inventory.ui.result;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: AssetResultAdapter 的 items / matchedIds 字段必须可变 (去掉 final),
 * 并暴露 setItems / setMatchedIds — 让 ResultFragment 两个 observer
 * (assets + matchedIds) 复用同一 adapter, 只 notifyDataSetChanged 增量更新,
 * 不反复 new AssetResultAdapter + setAdapter 让 RecyclerView 短链残留.
 */
public class AssetResultAdapterMutableTest {

    @Test
    public void assetResultAdapter_hasSetterMethods() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/ui/result/AssetResultAdapter.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/ui/result/AssetResultAdapter.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        assertTrue("AssetResultAdapter must declare public setItems(List<Asset>) for "
                + "observer reuse pattern. Source:\n" + text,
                text.contains("public void setItems(List<Asset> items)"));
        assertTrue("AssetResultAdapter must declare public setMatchedIds(Set<Long>) "
                + "for matchedIds observer reuse. Source:\n" + text,
                text.contains("public void setMatchedIds(Set<Long> matchedIds)"));
        // items / matchedIds 必须非 final (可变)
        // Find field declarations
        int itemsIdx = text.indexOf("private List<Asset> items;");
        int matchedIdx = text.indexOf("private Set<Long> matchedIds;");
        // 接受 non-final (已经被改造); 旧 final 写法不存在
        assertFalse("AssetResultAdapter.items must NOT be final (would block in-place "
                + "update from observer). Source:\n" + text,
                text.contains("private final List<Asset> items;"));
        assertFalse("AssetResultAdapter.matchedIds must NOT be final. Source:\n"
                + text, text.contains("private final Set<Long> matchedIds;"));
    }
}

package com.huaxia.inventory.data;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: {@code Asset} 必须声明 {@code (taskId, dept)} 复合索引。
 *
 * <p>它服务 {@code countPoolByDept} / {@code distinctDeptPool} / {@code distinctDept} 的
 * {@code WHERE taskId = 0 AND dept = ?} 过滤 (建任务时按部门统计库存池)。索引名/列与
 * {@code @Index} 不一致时 Room 运行时 schema 校验会抛异常。</p>
 */
public class AssetDeptIndexTest {

    @Test
    public void assetDeclaresTaskIdDeptIndex() throws Exception {
        String text = read("src/main/java/com/huaxia/inventory/data/entity/Asset.java");
        assertTrue("Asset must declare @Index(value = {\"taskId\", \"dept\"}). Source:\n" + text,
                text.contains("@Index(value = {\"taskId\", \"dept\"})"));
    }

    private static String read(String rel) throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File f = new File(moduleDir, rel);
        if (!f.exists()) f = new File(moduleDir, "app/" + rel);
        assertTrue("source not found: " + f.getAbsolutePath(), f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}

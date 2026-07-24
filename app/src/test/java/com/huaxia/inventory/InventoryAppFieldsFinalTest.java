package com.huaxia.inventory;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: InventoryApp 公开的 {@code executors / session / rfid / db} 字段
 * 必须 {@code final} 修饰 — 防止跨进程 (主线程 / 后台线程) 重新赋值破坏
 * 应用生命周期. 不能改成 private 因为有 10+ 文件直接读这些字段,
 * 改 getter 会大改 call site. 当前用 {@code @VisibleForTesting} +
 * final 解决一半问题 (JMM 重排 + 引用稳定性).
 */
public class InventoryAppFieldsFinalTest {

    @Test
    public void inventoryApp_coreServicesArePrivate_withGetterBasedAccess() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/InventoryApp.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/InventoryApp.java");
        }
        assertTrue("source not found", source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // P3#3: 4 个核心服务字段 (executors/session/rfid/db) 必须 private —
        // 不再以 public 字段形式暴露 Application 级单例.
        // 旧的 @VisibleForTesting + Javadoc 警示已完成迁移, 字段已彻底私有化.
        for (String field : new String[]{"executors", "session", "rfid", "db"}) {
            // 匹配形如 {@code private <TypeName> <fieldName>;} 的声明.
            // 不能用 {@code text.contains("private " + field)}, 因为中间还隔着类型.
            Pattern decl = Pattern.compile("private\\s+\\w+\\s+" + Pattern.quote(field) + "\\s*[=;]");
            Matcher m = decl.matcher(text);
            assertTrue("InventoryApp must declare 'private <Type> " + field + "' (P3#3 私有化). "
                    + "Source:\n" + text, m.find());
            // 不允许仍然写成 public final 或 public 非 final
            assertFalse("InventoryApp must NOT have public " + field
                    + " — fully migrated to private getter. Source:\n" + text,
                    text.contains("public " + field + ";")
                            || text.contains("public final " + field));
        }

        // 必须有 4 个 getter 暴露访问
        for (String getter : new String[]{
                "getExecutors()", "getSession()", "getRfid()", "getDb()"}) {
            // 匹配形如 {@code public <ReturnType> <getter>()} 的声明 — 返回类型必须存在
            Pattern decl = Pattern.compile("public\\s+\\w+\\s+" + Pattern.quote(getter) + "\\s*\\{");
            Matcher m = decl.matcher(text);
            assertTrue("InventoryApp must expose public <ReturnType> " + getter
                    + " — getter is the only access path. Source:\n" + text, m.find());
        }
    }
}

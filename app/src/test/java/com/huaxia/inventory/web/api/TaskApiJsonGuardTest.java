package com.huaxia.inventory.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 防回归: JSON 请求体必须读成 {@code Map}，不能用自定义 DTO。
 *
 * <p>Jackson 反序列化自定义类依赖<b>字段名反射</b>。release 构建 R8 会重命名字段，
 * 即便加 {@code @JsonProperty} 也实测失效 —— 真机上表现为
 * 「新建盘点任务」报「请求体不是合法 JSON」，而同一请求在 JVM 集成测试里正常。
 *
 * <p>{@code Map.class} 走 Jackson 内置反序列化器，不触碰任何自定义类反射。
 * 仓库里读 {@code rawRowJson} 用的也是这个写法。
 */
public class TaskApiJsonGuardTest {

    @Test
    public void taskApi_parsesBodyAsMapNotCustomDto() throws Exception {
        String text = readTaskApi();

        assertThat(text)
                .as("parseBody must deserialize into Map.class — a custom DTO breaks "
                        + "under R8 field renaming in release builds")
                .contains("WebJson.read(raw, Map.class)");

        assertThat(text)
                .as("no custom request-body DTO should remain in TaskApi")
                .doesNotContain("CreateTaskBody");
    }

    private static String readTaskApi() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/web/api/TaskApi.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/web/api/TaskApi.java");
        }
        assertThat(source.exists()).as("TaskApi.java not found: " + source).isTrue();
        return new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
    }
}

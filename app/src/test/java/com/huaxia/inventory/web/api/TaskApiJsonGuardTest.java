package com.huaxia.inventory.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 防回归: 请求体 DTO 的字段必须显式标注 {@code @JsonProperty}。
 *
 * <p>Jackson 默认按<b>字段名</b>反射映射。release 构建开了 R8，字段会被重命名
 * （例如 {@code name → a}），届时 {@code {"name":"..."}} 映射不到任何字段，
 * 网页「新建任务」会静默变成「任务名称不能为空」。
 *
 * <p>debug 构建不混淆，这个缺陷只在 release 包上暴露 —— 必须靠源码守卫钉住。
 */
public class TaskApiJsonGuardTest {

    @Test
    public void createTaskBody_fieldsDeclareJsonProperty() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/web/api/TaskApi.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/web/api/TaskApi.java");
        }
        assertThat(source.exists()).as("TaskApi.java not found: " + source).isTrue();

        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        for (String field : new String[]{"name", "dept", "remark"}) {
            assertThat(text)
                    .as("CreateTaskBody." + field + " must declare @JsonProperty(\"" + field
                            + "\") so R8 renaming cannot break Jackson deserialization")
                    .contains("@com.fasterxml.jackson.annotation.JsonProperty(\"" + field + "\")");
        }
    }
}

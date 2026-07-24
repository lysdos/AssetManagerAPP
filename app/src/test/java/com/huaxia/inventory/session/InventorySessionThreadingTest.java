package com.huaxia.inventory.session;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: InventorySession.setCurrentTaskId 必须根据调用线程是主线程还是后台线程
 * 选用 {@code setValue} / {@code postValue}. 修复前统一走
 * {@code postValue}, 主线程上也会被异步投递到下一帧, 导致同帧后续
 * {@code getCurrentTaskId()} 读到旧值, 进而被消费方误判为任务未切换.
 */
public class InventorySessionThreadingTest {

    @Test
    public void setCurrentTaskId_usesSetValueOnMainThread_postValueOnWorker() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/session/InventorySession.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/session/InventorySession.java");
        }
        assertTrue("InventorySession.java not found: " + source.getAbsolutePath(),
                source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 必须 import Looper (Android 平台 API), 否则无法判断当前线程.
        assertTrue("InventorySession must import android.os.Looper to detect main thread. "
                + "Source:\n" + text,
                text.contains("import android.os.Looper"));

        // setCurrentTaskId 方法体必须出现 "Looper.getMainLooper()" 判定.
        String setBody = extractMethodBody(text, "public void setCurrentTaskId(");
        assertTrue("setCurrentTaskId body not found.\nSource:\n" + text,
                setBody.length() > 0);
        assertTrue("setCurrentTaskId must branch on Looper.myLooper() == "
                + "Looper.getMainLooper() — else getCurrentTaskId in next-frame reads stale. "
                + "Body:\n" + setBody,
                setBody.contains("Looper.myLooper()")
                        && setBody.contains("Looper.getMainLooper()"));
        assertTrue("setCurrentTaskId main-thread branch must call setValue.\nBody:\n"
                + setBody,
                setBody.contains("setValue("));
        assertTrue("setCurrentTaskId worker-thread branch must call postValue.\nBody:\n"
                + setBody,
                setBody.contains("postValue("));

        // clear() 同样约束 (否则清不掉当前任务, 旧值残留导致扫描页永久 stuck).
        String clearBody = extractMethodBody(text, "public void clear()");
        assertTrue("clear body not found.\nSource:\n" + text, clearBody.length() > 0);
        assertTrue("clear() must also branch on main/worker Looper.\nBody:\n" + clearBody,
                clearBody.contains("Looper.myLooper()")
                        && clearBody.contains("Looper.getMainLooper()"));
    }

    /** 返回方法签名后第一个匹配的 '{' 之后到对应 '}' 之间的内容。 */
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

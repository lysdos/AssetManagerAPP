package com.huaxia.inventory.rfid;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: SdkRfidAdapter 在换成优博讯 UHF SDK 后是<b>回调式</b>, 下面几条不变量
 * 任一被破坏都会重新引入旧实现的坑:
 * <ol>
 *   <li>不允许再出现轮询线程 ({@code pollLoop} / {@code readTagFromBuffer} /
 *       {@code RFIDWithUHFUART}) — 新 SDK 通过 {@code IRfidCallback} 推标签.</li>
 *   <li>订阅者集合必须是 {@link java.util.concurrent.CopyOnWriteArraySet} —
 *       register/unregister 与 binder 线程上的分发并发.</li>
 *   <li>start/stop 必须串行化并且幂等 (compareAndSet), 否则重复点击扫描按钮会
 *       让 scanning 状态与 SDK 实际状态错位.</li>
 *   <li>release() 必须先 unregisterCallback 再置空 reader — 否则 SDK 仍持有可能
 *       已被释放的适配器回调.</li>
 * </ol>
 */
public class SdkRfidAdapterRaceTest {

    @Test
    public void source_hasNoPollingThread() throws Exception {
        String text = readSource();
        assertFalse("SdkRfidAdapter must NOT keep the old polling design. Source:\n" + text,
                text.contains("RFIDWithUHFUART"));
        assertFalse("SdkRfidAdapter must NOT keep pollLoop(). Source:\n" + text,
                text.contains("pollLoop"));
        assertFalse("SdkRfidAdapter must NOT call readTagFromBuffer(). Source:\n" + text,
                text.contains("readTagFromBuffer"));
    }

    @Test
    public void listeners_isCopyOnWriteArraySet() throws Exception {
        String text = readSource();
        assertTrue("listeners must stay a CopyOnWriteArraySet — binder 线程分发与 "
                        + "register/unregister 并发. Source:\n" + text,
                text.contains("new CopyOnWriteArraySet<>()"));
    }

    @Test
    public void init_registersSdkCallback() throws Exception {
        String text = readSource();
        String body = extractMethodBody(text, "public void init(Context appCtx, Callback cb)");
        assertTrue("init body not found.\nSource:\n" + text, body.length() > 0);
        assertTrue("init() must register the IRfidCallback with the Urovo SDK. Body:\n" + body,
                body.contains("registerCallback(sdkCallback)"));
        assertTrue("init() must obtain the reader from RFIDSDKManager. Body:\n" + body,
                body.contains("getRfidManager()"));
    }

    @Test
    public void startStop_areSerializedAndIdempotent() throws Exception {
        String text = readSource();
        String start = extractMethodBody(text, "public int startInventory()");
        assertTrue("startInventory body not found.\nSource:\n" + text, start.length() > 0);
        assertTrue("startInventory must serialize on lifecycleLock. Body:\n" + start,
                start.contains("synchronized (lifecycleLock)"));
        assertTrue("startInventory must be idempotent via scanning.get(). Body:\n" + start,
                start.contains("scanning.get()"));

        String stop = extractMethodBody(text, "public int stopInventory()");
        assertTrue("stopInventory body not found.\nSource:\n" + text, stop.length() > 0);
        assertTrue("stopInventory must serialize on lifecycleLock. Body:\n" + stop,
                stop.contains("synchronized (lifecycleLock)"));
        assertTrue("stopInventory must be idempotent via compareAndSet. Body:\n" + stop,
                stop.contains("compareAndSet(true, false)"));
    }

    @Test
    public void init_hasTimeoutFallback() throws Exception {
        // 优博讯 RFIDSDKManager.init 在内部 catch(Throwable)/DeclaredMethodUtils 反射
        // 失败路径下会静默返回、永不回调. 没有超时兜底时 RfidController 会永远停在
        // INITIALIZING (旧 rscja 实现同步返回 false 会进 ERROR).
        String text = readSource();
        String body = extractMethodBody(text, "public void init(Context appCtx, Callback cb)");
        assertTrue("init body not found.\nSource:\n" + text, body.length() > 0);
        assertTrue("init() must guard the InitListener callback against a never-firing SDK "
                        + "via INIT_TIMEOUT_MS. Body:\n" + body,
                body.contains("INIT_TIMEOUT_MS"));
        assertTrue("init() must settle exactly once via compareAndSet(false, true). Body:\n"
                        + body,
                body.contains("compareAndSet(false, true)"));
        assertTrue("init() must report failure via cb.onStatus(false) when the SDK never "
                        + "calls back. Body:\n" + body,
                body.contains("onStatus(false)"));
    }

    @Test
    public void release_unregistersCallbackBeforeClearingReader() throws Exception {
        String text = readSource();
        String body = extractMethodBody(text, "public void release()");
        assertTrue("release body not found.\nSource:\n" + text, body.length() > 0);
        int unregisterIdx = body.indexOf("unregisterCallback(sdkCallback)");
        int nullIdx = body.indexOf("reader = null");
        assertTrue("release() must unregisterCallback(sdkCallback) before reader = null. "
                + "Body:\n" + body, unregisterIdx >= 0 && nullIdx > unregisterIdx);
    }

    private static String readSource() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/rfid/SdkRfidAdapter.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/rfid/SdkRfidAdapter.java");
        }
        assertTrue("SdkRfidAdapter.java not found: " + source.getAbsolutePath(),
                source.exists());
        return new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
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

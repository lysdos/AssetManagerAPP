package com.huaxia.inventory.rfid;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: SdkRfidAdapter 的 rfid-poll 守护线程与 init/release 必须正确处理竞态:
 * <ol>
 *   <li>{@code reader} 字段必须声明为 {@code volatile}, 让守护线程每次循环重新
 *       读到最新指针 — release 把它置 null 后, 守护线程下一轮立即退出,
 *       不会对已 free 的 SDK 单例调用 readTagFromBuffer().</li>
 *   <li>{@link SdkRfidAdapter#pollLoop} 不能把 reader 缓存到栈上 — 必须
 *       在循环体内重新读 volatile 字段.</li>
 *   <li>{@link SdkRfidAdapter#release} 末尾必须把 reader 置 null.</li>
 *   <li>{@link SdkRfidAdapter#stopInventory} 必须先 join 守护线程, 再清
 *       {@code pollThread} 引用 — 否则 join 期间 startInventory 可能复用旧引用.</li>
 * </ol>
 */
public class SdkRfidAdapterRaceTest {

    @Test
    public void reader_fieldIsVolatile() throws Exception {
        String text = readSource();
        // "private volatile RFIDWithUHFUART reader" 必须出现一次, 不允许被改回非 volatile.
        assertTrue("SdkRfidAdapter.reader must be declared volatile. Source:\n" + text,
                text.contains("private volatile RFIDWithUHFUART reader")
                        || text.contains("private volatile  RFIDWithUHFUART reader"));
        assertTrue("Must not have a non-volatile reader declaration. Source:\n" + text,
                !text.contains("private RFIDWithUHFUART reader;")
                        || text.contains("private volatile RFIDWithUHFUART reader"));
    }

    @Test
    public void pollLoop_reReadsReaderOnEveryLoop() throws Exception {
        String text = readSource();
        // pollLoop 内必须出现 "RFIDWithUHFUART r = reader;" (每次循环重新读 volatile)
        // 或者同等语义, 不允许把 reader 缓存到 loop 外.
        String body = extractMethodBody(text, "private void pollLoop()");
        assertTrue("pollLoop body not found.\nSource:\n" + text, body.length() > 0);
        assertTrue("pollLoop must re-read volatile reader on every iteration. Body:\n" + body,
                body.contains("RFIDWithUHFUART r = reader")
                        || body.contains("RFIDWithUHFUART r=reader"));
        // 必须有 null 检查, 否则 release 之后 reader=null 仍会被缓存进栈上.
        assertTrue("pollLoop must check reader==null each iteration. Body:\n" + body,
                body.contains("r == null"));
    }

    @Test
    public void pollLoop_gatesOnStillBeingThePollThread() throws Exception {
        String text = readSource();
        String body = extractMethodBody(text, "private void pollLoop()");
        assertTrue("pollLoop body not found.\nSource:\n" + text, body.length() > 0);
        // stop 把 scanning 置 false 后, 若 join 超时, 随后的 start 会把 scanning 置回
        // true — 旧线程必须还能凭"自身已不是 pollThread"退出, 否则会复活并与新线程
        // 并发调用 readTagFromBuffer. 循环条件必须含 pollThread == currentThread().
        assertTrue("pollLoop must gate on pollThread == Thread.currentThread() so a "
                + "stop/start race does not revive the old poll thread. Body:\n" + body,
                body.contains("pollThread == Thread.currentThread()"));
    }

    @Test
    public void release_nullsReaderAfterFree() throws Exception {
        String text = readSource();
        String body = extractMethodBody(text, "public void release()");
        assertTrue("release body not found.\nSource:\n" + text, body.length() > 0);
        // 必须包含 reader = null (在 free 之后)
        assertTrue("release() must null out reader after free() — so polling thread "
                + "sees null and exits before next readTagFromBuffer() on freed SDK. Body:\n"
                + body,
                body.contains("reader = null"));
        // 必须先 free 然后 null, 否则守护线程可能 free 之后、reader=null 之前
        // 调用到 free 之后的 SDK 单例.
        int freeIdx = body.indexOf(".free()");
        int nullIdx = body.indexOf("reader = null");
        assertTrue("free() must appear before reader = null. Body:\n" + body,
                freeIdx >= 0 && nullIdx > freeIdx);
    }

    @Test
    public void stopInventory_clearsPollThreadAfterJoin() throws Exception {
        String text = readSource();
        String body = extractMethodBody(text, "public int stopInventory()");
        assertTrue("stopInventory body not found.\nSource:\n" + text, body.length() > 0);
        // join 之后才允许 pollThread = null. 检查 "pollThread = null" 必须出现在
        // t.join( 之后, 否则 join 期间 startInventory 可能复用旧引用.
        int joinIdx = body.indexOf(".join(");
        int nullIdx = body.indexOf("pollThread = null");
        assertTrue("stopInventory must call pollThread.join(...) before clearing "
                + "pollThread = null. Body:\n" + body,
                joinIdx >= 0 && nullIdx > joinIdx);
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

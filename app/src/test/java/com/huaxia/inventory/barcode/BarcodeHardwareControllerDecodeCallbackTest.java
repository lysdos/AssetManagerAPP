package com.huaxia.inventory.barcode;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * 防回归: BarcodeHardwareController.stopScan 必须按固定顺序收尾.
 *
 * <p>优博讯 ScanManager 用广播回调解码结果, 等价于旧 rscja 的 DecodeCallback:
 * 先 {@code stopDecode()} 停掉解码会话, 再注销广播接收器 —— 否则 SDK 仍可能
 * 发出一条 in-flight 广播, 落进 emit() 命中已置 null 的 activeCallback;
 * 反过来若忘了注销, Application Context 会一直持有接收器与 controller 的强引用,
 * 反复进出手动盘点页后 Fragment 视图链不被回收.</p>
 *
 * <p>同时 stopScan 必须重置 {@code lastEmitMs/lastEmitCode} 2s 去抖窗口 —— 否则
 * 重建 controller 时首条相同码被去重窗口吞掉.
 */
public class BarcodeHardwareControllerDecodeCallbackTest {

    @Test
    public void stopScan_stopsDecodeThenUnregistersReceiver_andResetsDebounce()
            throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir,
                "src/main/java/com/huaxia/inventory/barcode/BarcodeHardwareController.java");
        if (!source.exists()) {
            source = new File(moduleDir,
                    "app/src/main/java/com/huaxia/inventory/barcode/"
                            + "BarcodeHardwareController.java");
        }
        assertTrue("source not found: " + source.getAbsolutePath(), source.exists());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        String body = extractMethodBody(text, "public void stopScan()");
        assertTrue("stopScan body not found", body.length() > 0);

        // 必须停解码会话.
        int stopIdx = body.indexOf("stopDecode()");
        assertTrue("stopScan must call stopDecode() — else the SDK keeps producing "
                + "decode broadcasts. Body:\n" + body, stopIdx >= 0);

        // 必须注销广播接收器 (等价于旧的 setDecodeCallback(null)).
        int unregisterIdx = body.indexOf("unregisterDecodeReceiver()");
        assertTrue("stopScan must unregister the decode receiver — else Application "
                + "Context keeps the controller alive, leaking Fragment. Body:\n" + body,
                unregisterIdx >= 0);

        // 顺序: 先 stopDecode 再注销, 避免 in-flight 广播落进 emit().
        assertTrue("stopDecode() must precede unregisterDecodeReceiver(). Body:\n" + body,
                unregisterIdx > stopIdx);

        // 必须重置 2s 去抖窗口.
        assertTrue("stopScan must reset 2s debounce (lastEmitMs=0, lastEmitCode=\"\") so "
                + "a freshly rebuilt controller does not inherit stale state. Body:\n" + body,
                body.contains("lastEmitMs = 0L") && body.contains("lastEmitCode = \"\""));
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

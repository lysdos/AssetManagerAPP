package com.huaxia.inventory.barcode;

import android.content.Intent;
import android.device.ScanManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * BarcodeHardwareController 纯逻辑行为测试:
 * 广播 extra 解析 (string / byte[] + length 截断) 与 2 秒同码去重窗口.
 *
 * <p>不启动硬件会话 —— 只直接调 package-private 的 {@code extractBarcode()} /
 * {@code isDuplicate()}, 由 Robolectric 提供 android.content.Intent 与主 Looper.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class BarcodeHardwareControllerTest {

    @Test
    public void extractBarcode_prefersStringExtra() {
        Intent intent = new Intent();
        intent.putExtra(ScanManager.BARCODE_STRING_TAG, "ABC123");
        // 同时放 byte[] extra, 验证 string extra 优先.
        intent.putExtra(ScanManager.DECODE_DATA_TAG, "BYTES".getBytes(StandardCharsets.UTF_8));

        assertEquals("ABC123", BarcodeHardwareController.extractBarcode(intent));
    }

    @Test
    public void extractBarcode_fallsBackToByteArrayAndTruncatesByLength() {
        Intent intent = new Intent();
        intent.putExtra(ScanManager.DECODE_DATA_TAG,
                "HELLOWORLD".getBytes(StandardCharsets.UTF_8));
        // length 小于数组长度: 只取前 5 个字节.
        intent.putExtra(ScanManager.BARCODE_LENGTH_TAG, 5);

        assertEquals("HELLO", BarcodeHardwareController.extractBarcode(intent));
    }

    @Test
    public void extractBarcode_returnsNullWhenBothExtrasMissing() {
        assertNull(BarcodeHardwareController.extractBarcode(new Intent()));
    }

    @Test
    public void isDuplicate_sameCodeWithinWindowIsDuplicate_differentCodeIsNot() {
        BarcodeHardwareController controller = new BarcodeHardwareController();

        assertFalse("首次出现不应算重复", controller.isDuplicate("CODE-A"));
        assertTrue("紧接着的同码应算重复", controller.isDuplicate("CODE-A"));
        assertFalse("不同码应立即放行", controller.isDuplicate("CODE-B"));
    }

    @Test
    public void stopScan_resetsDedupWindow() {
        BarcodeHardwareController controller = new BarcodeHardwareController();

        assertFalse(controller.isDuplicate("CODE-A"));
        assertTrue(controller.isDuplicate("CODE-A"));

        // stopScan 重置 lastEmitCode/lastEmitMs: 重启后的首条同码必须重新放行.
        controller.stopScan();

        assertFalse("stopScan 后同码应重新返回 false",
                controller.isDuplicate("CODE-A"));
    }
}

package com.huaxia.inventory.ui.scan;

import com.huaxia.inventory.util.EpcNormalizer;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 端到端覆盖扫描流的核心数据转换: SDK 返回的十进制串 → 24 位十进制规范形态。
 *
 * <p>项目约定: 卡上存的就是 16/17/24 位十进制固资编号本身, 不做任何
 * 十六进制转换。{@code ScanFragment.rfidCallback} 直接对 SDK 返回的
 * 字符串调 {@link EpcNormalizer#normalize}。</p>
 *
 * <p>写这个测试是 regression 防御 — 防止以后有人不小心引入 hex 转换层
 * (历史上有过 EpcCodec.fromHex, 后来确认卡上是直接存十进制后删掉)。</p>
 */
public class ScanFlowTest {

    @Test
    public void sdk16Digit_to24digitCanonical() {
        // SDK 返回 16 位十进制 → 补 8 个 0
        assertEquals("000000003500309099027210",
                EpcNormalizer.normalize("3500309099027210"));
    }

    @Test
    public void sdk17Digit_to24digitCanonical() {
        // SDK 返回 17 位十进制 → 补 7 个 0
        assertEquals("000000010000000000000000",
                EpcNormalizer.normalize("10000000000000000"));
    }

    @Test
    public void sdk24Digit_passThrough() {
        // SDK 返回 24 位 (已是规范形态) → 原样
        assertEquals("000000003500309099027210",
                EpcNormalizer.normalize("000000003500309099027210"));
    }

    @Test
    public void sdkHexString_returnsNull() {
        // SDK 万一返回 hex 风格 (E280...), 含字母 → normalize 拒掉
        // 这条 case 是用户旧固件返回错误格式时的兜底, 不应该把脏数据当合法 EPC
        assertNull(EpcNormalizer.normalize("E280699500005003B9D2A1B0"));
    }

    @Test
    public void sdkWrongLength_returnsNull() {
        // 15 / 18 / 25 位都拒
        assertNull(EpcNormalizer.normalize("123456789012345"));
        assertNull(EpcNormalizer.normalize("123456789012345678"));
        assertNull(EpcNormalizer.normalize("1234567890123456789012345"));
    }

    @Test
    public void sdkGarbage_returnsNull() {
        assertNull(EpcNormalizer.normalize("GGGGGGGGGGGGGGGGGGGGGGGG"));
    }
}

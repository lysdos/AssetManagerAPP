package com.huaxia.inventory.rfid;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * SdkRfidAdapter.dispatch() 的 fan-out 行为: epc 过滤、data→tid 归一化、
 * rssi 转字符串, 以及单个 listener 抛异常不影响其余订阅者.
 *
 * <p>普通 JUnit: 直接 new SdkRfidAdapter() 不会触碰 UHF SDK, dispatch() 是
 * package-private 的纯内存扇出.</p>
 */
public class SdkRfidAdapterDispatchTest {

    /** 依次记录 [epc, tid, rssi]. */
    private final List<String[]> received = new ArrayList<>();
    private final SdkRfidAdapter.TagListener recorder = (epc, tid, rssi) ->
            received.add(new String[]{epc, tid, rssi});

    @Test
    public void dispatch_ignoresNullAndEmptyEpc() {
        SdkRfidAdapter adapter = new SdkRfidAdapter();
        adapter.registerCallback(recorder);

        adapter.dispatch(null, "TID", -55);
        adapter.dispatch("", "TID", -55);

        assertTrue("epc 为 null/空串时不应派发, 实际: " + received.size(), received.isEmpty());
    }

    @Test
    public void dispatch_normalizesEmptyDataToNullTid() {
        SdkRfidAdapter adapter = new SdkRfidAdapter();
        adapter.registerCallback(recorder);

        adapter.dispatch("EPC-1", "", -55);

        assertEquals(1, received.size());
        assertNull("空 data 应归一成 tid == null", received.get(0)[1]);
    }

    @Test
    public void dispatch_passesNonEmptyDataThroughAsTid() {
        SdkRfidAdapter adapter = new SdkRfidAdapter();
        adapter.registerCallback(recorder);

        adapter.dispatch("EPC-1", "TID-RAW", -55);

        assertEquals(1, received.size());
        assertEquals("TID-RAW", received.get(0)[1]);
    }

    @Test
    public void dispatch_passesRssiAsStringValueOfInt() {
        SdkRfidAdapter adapter = new SdkRfidAdapter();
        adapter.registerCallback(recorder);

        adapter.dispatch("EPC-1", "TID-RAW", -55);

        assertEquals(1, received.size());
        assertEquals("-55", received.get(0)[2]);
    }

    @Test
    public void dispatch_continuesFanOutWhenOneListenerThrows() {
        SdkRfidAdapter adapter = new SdkRfidAdapter();
        adapter.registerCallback((epc, tid, rssi) -> {
            throw new RuntimeException("listener boom");
        });
        adapter.registerCallback(recorder);

        adapter.dispatch("EPC-1", "TID-RAW", -55);

        assertEquals("第一个 listener 抛异常后, 第二个仍应收到回调",
                1, received.size());
        assertEquals("EPC-1", received.get(0)[0]);
    }
}

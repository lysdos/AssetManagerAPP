package com.huaxia.inventory.rfid;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link RfidController}.
 *
 * <p>These tests use a mix of Mockito and anonymous-subclass patterns.
 * The SDK methods are final/static and hard to mock purely with Mockito,
 * so the {@link SdkRfidAdapter} is subclassed where we need to capture
 * callbacks or short-circuit the SDK entirely.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class RfidControllerTest {

    private SdkRfidAdapter adapter;
    private RfidController controller;
    private final List<SdkRfidAdapter.Callback> initCbs = new ArrayList<>();

    @Before
    public void setup() {
        initCbs.clear();
        adapter = mock(SdkRfidAdapter.class);
        controller = new RfidController(adapter);
        when(adapter.isConnected()).thenReturn(true);
    }

    @Test
    public void init_drivesStateToReady_onSuccess() {
        SdkRfidAdapter realAdapter = new SdkRfidAdapter() {
            @Override
            public void init(Context ignored, Callback cb) {
                initCbs.add(cb);
            }
        };
        RfidController c = new RfidController(realAdapter);
        AtomicBoolean result = new AtomicBoolean();

        c.init(null, result::set);

        // While the SDK is still initializing, the state must be INITIALIZING.
        assertEquals(RfidController.State.INITIALIZING, c.getState());
        // The init call captured exactly one callback.
        assertEquals(1, initCbs.size());

        // Fire the captured callback with success.
        initCbs.get(0).onStatus(true);

        // The state machine must have advanced to READY and the user's
        // callback must have been invoked with true.
        assertEquals(RfidController.State.READY, c.getState());
        assertTrue(result.get());
    }

    @Test
    public void init_drivesStateToError_onFailure() {
        SdkRfidAdapter realAdapter = new SdkRfidAdapter() {
            @Override
            public void init(Context ignored, Callback cb) {
                // Synchronous failure — invoke callback immediately.
                cb.onStatus(false);
            }
        };
        RfidController c = new RfidController(realAdapter);
        AtomicBoolean result = new AtomicBoolean(true);

        c.init(null, result::set);

        assertEquals(RfidController.State.ERROR, c.getState());
        assertFalse(result.get());
    }

    @Test
    public void startInventory_requiresReady() {
        // In IDLE state, start must be a no-op (state is IDLE from setup()).
        controller.startInventory();
        verify(adapter, never()).startInventory();
    }

    @Test
    public void registerCallback_addsAndPropagates() {
        SdkRfidAdapter.TagListener cb = mock(SdkRfidAdapter.TagListener.class);

        // In IDLE state, registerCallback should not yet push the callback
        // down to the SDK — that only happens after init.
        controller.registerCallback(cb);
        verify(adapter, never()).registerCallback(any(SdkRfidAdapter.TagListener.class));

        // After init succeeds, the buffered callback should be propagated.
        AtomicBoolean cbRegistered = new AtomicBoolean(false);
        SdkRfidAdapter realAdapter = new SdkRfidAdapter() {
            @Override
            public void init(Context ignored, Callback c) {
                c.onStatus(true);
            }
            @Override
            public void registerCallback(SdkRfidAdapter.TagListener listener) {
                cbRegistered.set(true);
            }
        };
        RfidController c = new RfidController(realAdapter);
        c.init(null, null);
        c.registerCallback(cb);
        assertTrue("registerCallback should have been delegated to adapter", cbRegistered.get());
    }

    @Test
    public void release_isIdempotent() {
        // Drive the controller to READY so release() actually tears down.
        AtomicInteger releaseCount = new AtomicInteger(0);
        SdkRfidAdapter realAdapter = new SdkRfidAdapter() {
            @Override
            public void init(Context ignored, Callback c) {
                c.onStatus(true);
            }
            @Override
            public void release() {
                releaseCount.incrementAndGet();
            }
        };
        RfidController c = new RfidController(realAdapter);
        c.init(null, null);

        // First release: teardown happens.
        c.release();
        // Second release: short-circuits because state is IDLE again.
        c.release();
        assertEquals("release() should be called exactly once", 1, releaseCount.get());
    }

    @Test
    public void writeEpc_delegatesHexToAdapter() {
        // 把控制器推到 READY
        AtomicReference<String> capturedHex = new AtomicReference<>();
        SdkRfidAdapter realAdapter = new SdkRfidAdapter() {
            @Override
            public void init(Context ignored, Callback c) {
                c.onStatus(true);
            }
            @Override
            public int writeEpc(String hex) {
                capturedHex.set(hex);
                return 0;
            }
        };
        RfidController c = new RfidController(realAdapter);
        c.init(null, null);

        // 3500309099027210 -> 000C6F8338739B0A
        boolean ok = c.writeEpc("3500309099027210");

        assertTrue(ok);
        assertEquals("000C6F8338739B0A", capturedHex.get());
    }

    @Test
    public void writeEpc_returnsFalseWhenNotReady() {
        // setup() 创建的 controller 处于 IDLE
        boolean ok = controller.writeEpc("3500309099027210");

        assertFalse(ok);
        verify(adapter, never()).writeEpc(any(String.class));
    }

    @Test
    public void writeEpc_returnsFalseOnCodecFailure() {
        AtomicBoolean writeEpcCalled = new AtomicBoolean(false);
        SdkRfidAdapter realAdapter = new SdkRfidAdapter() {
            @Override
            public void init(Context ignored, Callback c) {
                c.onStatus(true);
            }
            @Override
            public int writeEpc(String hex) {
                writeEpcCalled.set(true);
                return 0;
            }
        };
        RfidController c = new RfidController(realAdapter);
        c.init(null, null);

        // 非 16 位数字 — codec 失败
        boolean ok = c.writeEpc("123");

        assertFalse(ok);
        assertFalse("adapter.writeEpc should not be called on codec failure", writeEpcCalled.get());
    }
}

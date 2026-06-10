package com.huaxia.inventory.rfid;

import android.content.Context;

import com.ubx.usdk.rfid.aidl.IRfidCallback;

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
import static org.mockito.Mockito.times;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        IRfidCallback cb = mock(IRfidCallback.class);

        // In IDLE state, registerCallback should not yet push the callback
        // down to the SDK — that only happens after init.
        controller.registerCallback(cb);
        verify(adapter, never()).registerCallback(any(IRfidCallback.class));

        // After init succeeds, the buffered callback should be propagated.
        SdkRfidAdapter realAdapter = new SdkRfidAdapter() {
            @Override
            public void init(Context ignored, Callback c) {
                c.onStatus(true);
            }
        };
        RfidController c = new RfidController(realAdapter);
        c.init(null, null);
        c.registerCallback(cb);
        verify(realAdapter, times(1)).registerCallback(cb);
    }

    @Test
    public void release_isIdempotent() {
        when(adapter.isConnected()).thenReturn(true);
        controller.release();
        // Second call must not throw and must not re-invoke release()
        // (release() short-circuits on IDLE state).
        controller.release();
        verify(adapter, times(1)).release();
    }
}

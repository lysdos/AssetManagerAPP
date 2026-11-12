package com.huaxia.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class WebServerServiceTest {

    @Test
    public void start_buildsForegroundIntentWithAction() {
        Context ctx = ApplicationProvider.getApplicationContext();
        Intent intent = WebServerService.buildStartIntent(ctx);
        assertThat(intent.getComponent().getClassName())
                .isEqualTo(WebServerService.class.getName());
        assertThat(intent.getAction()).isEqualTo(WebServerService.ACTION_START);
    }

    @Test
    public void stop_buildsStopIntent() {
        Context ctx = ApplicationProvider.getApplicationContext();
        Intent intent = WebServerService.buildStopIntent(ctx);
        assertThat(intent.getAction()).isEqualTo(WebServerService.ACTION_STOP);
    }

    @Test
    public void activePort_isMinusOneWhenNotRunning() {
        assertThat(WebServerService.isRunning()).isFalse();
        assertThat(WebServerService.activePort()).isEqualTo(-1);
    }

    @Test
    public void pickPort_skipsOccupiedPorts() throws Exception {
        int probe = WebServerService.pickPort(8080, 8089);
        assertThat(probe).isBetween(8080, 8089);

        // 占住刚选中的端口，再选一次必须换一个
        try (java.net.ServerSocket busy = new java.net.ServerSocket(probe)) {
            int next = WebServerService.pickPort(probe, 8089);
            if (probe < 8089) {
                assertThat(next).isNotEqualTo(probe).isBetween(probe + 1, 8089);
            }
        }
    }
}

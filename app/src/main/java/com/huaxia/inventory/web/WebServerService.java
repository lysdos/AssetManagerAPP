package com.huaxia.inventory.web;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.MainActivity;
import com.huaxia.inventory.R;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 承载 {@link WebServer} 的前台服务。
 *
 * <p>Android 8+ 的后台限制下，前台服务是唯一能保证切后台/锁屏不断的方式。
 * 服务只负责生命周期与通知，业务全部在 {@link WebServer} 与其 handler 里。
 */
public class WebServerService extends Service {

    private static final String TAG = "WebServerService";

    public static final String ACTION_START = "com.huaxia.inventory.web.START";
    public static final String ACTION_STOP = "com.huaxia.inventory.web.STOP";

    private static final String CHANNEL_ID = "web_server";
    private static final int NOTIFICATION_ID = 0x5E7B;
    private static final int PORT_START = 8080;
    private static final int PORT_END = 8089;

    private static volatile boolean running = false;
    private static volatile int port = -1;

    private WebServer server;

    public static boolean isRunning() { return running; }

    public static int activePort() { return port; }

    public static Intent buildStartIntent(Context ctx) {
        Intent i = new Intent(ctx, WebServerService.class);
        i.setAction(ACTION_START);
        return i;
    }

    public static Intent buildStopIntent(Context ctx) {
        Intent i = new Intent(ctx, WebServerService.class);
        i.setAction(ACTION_STOP);
        return i;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (server != null) {
            updateNotification();
            return START_STICKY;
        }

        InventoryApp app = (InventoryApp) getApplication();
        int picked = pickPort(PORT_START, PORT_END);
        if (picked < 0) {
            Log.e(TAG, "no free port in " + PORT_START + "-" + PORT_END);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            server = new WebServer(picked, this, app.getDb(), app.getSession());
            server.start(5000, false);
            port = picked;
            running = true;
            Log.i(TAG, "web server started on port " + picked);
        } catch (IOException e) {
            Log.e(TAG, "web server start failed", e);
            server = null;
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundCompat();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
            server = null;
        }
        running = false;
        port = -1;
        clearExportCache();
        Log.i(TAG, "web server stopped");
        super.onDestroy();
    }

    /** 从 startPort 起找第一个可绑定端口；全占用返回 -1。 */
    public static int pickPort(int startPort, int endPort) {
        for (int p = startPort; p <= endPort; p++) {
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(true);
                probe.bind(new InetSocketAddress(p));
                return p;
            } catch (IOException ignored) {
                // 端口占用，继续试下一个
            }
        }
        return -1;
    }

    /** 本机所有 IPv4 非回环地址 —— WiFi 与热点一视同仁，不需要 ACCESS_WIFI_STATE。 */
    public static List<String> localIpv4Addresses() {
        List<String> result = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        result.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "enumerate interfaces failed", e);
        }
        return result;
    }

    private void startForegroundCompat() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.web_server_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                buildStopIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_settings)
                .setContentTitle(getString(R.string.web_server_notification_title))
                .setContentText(describeAddress())
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(0, getString(R.string.web_server_stop), stop)
                .build();
    }

    /** 给通知与对话框共用的地址文案，如 "192.168.1.50:8080"。 */
    public static String describeAddress() {
        List<String> ips = localIpv4Addresses();
        if (ips.isEmpty()) return "未连接网络";
        return ips.get(0) + ":" + (port > 0 ? port : PORT_START);
    }

    private void clearExportCache() {
        for (String dir : new String[]{"web-export", "web-upload"}) {
            File[] files = new File(getCacheDir(), dir).listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.delete()) Log.w(TAG, "failed to delete " + f.getName());
            }
        }
    }
}

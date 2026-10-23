package com.hyremote.ctrl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the HTTP remote alive and shows the ongoing notification.
 *
 * The server now lives in this process (HttpServerLite), not in a shell script
 * under /data/local/tmp. That is what makes a plain sideload work: an app
 * cannot write /data/local/tmp on Android, so the old fallback could never
 * start. If the optional root bridge from setup.ps1 is already serving, this
 * service stays out of the way and lets it own the port.
 */
public class RemoteService extends Service {

    private static final String TAG = "HyRemoteSvc";
    private static final String CHANNEL_ID = "hyremote";

    public static final int PORT = HttpServerLite.PORT_MIN;
    public static final int PORT_MIN = HttpServerLite.PORT_MIN;
    public static final int PORT_MAX = HttpServerLite.PORT_MAX;

    private final AtomicBoolean starting = new AtomicBoolean(false);

    private volatile HttpServerLite server;
    private long nextAttemptAt = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForegroundCompat();
        ensureServer();
        startWatchdog();

        // Warm up the local adbd connection. On the first run adbd may show
        // its "Allow debugging?" dialog; doing it now means the prompt appears
        // while the user is looking at the projector, not on the first key
        // press from the phone.
        new Thread(new Runnable() {
            @Override
            public void run() {
                LocalAdb.available(RemoteService.this);
            }
        }, "adb-warmup").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureServer();
        return START_STICKY;
    }

    /**
     * Starts the in-app server unless the root bridge (installed by setup.ps1)
     * is already up. Runs off the main thread because both the port probe and
     * the bind are network operations.
     */
    private void ensureServer() {
        if (!starting.compareAndSet(false, true)) return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpServerLite current = server;
                    if (current != null && current.isRunning()) return;

                    if (HttpServerLite.shellBridgeUp()) {
                        Log.i(TAG, "root bridge is serving; app server stays off");
                        return;
                    }
                    if (System.currentTimeMillis() < nextAttemptAt) return;

                    HttpServerLite s = new HttpServerLite(RemoteService.this);
                    s.start();
                    server = s;
                    Log.i(TAG, "app server listening on port " + s.getPort());
                } catch (Exception e) {
                    Log.e(TAG, "server start failed", e);
                    // Do not spin on a port range that is temporarily busy.
                    nextAttemptAt = System.currentTimeMillis() + 10000;
                } finally {
                    starting.set(false);
                }
            }
        }, "server-start").start();
    }

    /** Re-checks the listener periodically; Android does not restart us on its own. */
    private void startWatchdog() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    HttpServerLite current = server;
                    if (current == null || !current.isRunning()) ensureServer();
                }
            }
        }, "server-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /** The port the server is actually on, or 0 when nothing is listening. */
    public static int findListeningPort() {
        for (int p = PORT_MIN; p <= PORT_MAX; p++) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", p), 300);
                return p;
            } catch (IOException e) {
                // try the next candidate
            } finally {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
        return 0;
    }

    @Override
    public void onDestroy() {
        HttpServerLite current = server;
        if (current != null) current.stop();
        server = null;
        super.onDestroy();
    }

    private void startForegroundCompat() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Notification n = builder
                .setContentTitle("HyRemote activo")
                .setContentText("Control web en esta red")
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        // specialUse only exists from Android 14. Passing it to older
        // releases can make startForeground throw, so use the two-argument
        // form there; the manifest still declares the type for 14+.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "HyRemote", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }
}

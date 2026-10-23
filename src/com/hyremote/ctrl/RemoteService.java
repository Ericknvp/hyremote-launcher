package com.hyremote.ctrl;

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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Keeps the HTTP remote alive and shows the ongoing notification.
 *
 * The server itself lives in HttpServerLite and is owned by the process, so
 * both the activity and this service can ask for it without racing for the
 * port. If the optional root bridge from setup.ps1 is already serving, the
 * in-app server stays out of the way and lets it own the port.
 */
public class RemoteService extends Service {

    private static final String TAG = "HyRemoteSvc";
    private static final String CHANNEL_ID = "hyremote";

    public static final int PORT = HttpServerLite.PORT_MIN;
    public static final int PORT_MIN = HttpServerLite.PORT_MIN;
    public static final int PORT_MAX = HttpServerLite.PORT_MAX;

    /**
     * Starts the service from the activity or a boot receiver. This never
     * throws: a firmware that rejects the foreground service must not prevent
     * the activity from bootstrapping the server by itself.
     */
    public static void start(Context ctx) {
        if (ctx == null) return;
        Intent svc = new Intent(ctx, RemoteService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(svc);
            } else {
                ctx.startService(svc);
            }
        } catch (Exception e) {
            Log.w(TAG, "service start failed: " + e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Satisfy the five-second foreground deadline first: Android kills a
        // startForegroundService() that has not called startForeground(). The
        // catch is deliberate, though: a firmware that rejects the
        // notification must not stop the server from binding right after.
        try {
            createChannel();
            startForegroundCompat();
        } catch (Throwable t) {
            Log.e(TAG, "foreground notification failed", t);
        }

        // Bind the port, but never on the main thread: ensure() probes the
        // ports to see whether the optional root bridge already owns them, and
        // that probe is network work. During boot the main thread is exactly
        // the resource the first frame needs.
        bindServerAsync();

        startWatchdog();
        warmUpAdb();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        bindServerAsync();
        return START_STICKY;
    }

    /** Starts the listener from a worker thread, whoever asks for it. */
    private void bindServerAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpServerLite.ensure(RemoteService.this);
            }
        }, "server-boot").start();
    }

    /**
     * Opens the ADB authorization window once, after the activity has had a
     * chance to claim HOME with a short probe. That keeps the "Allow
     * debugging?" prompt from covering the first frame of the launcher and
     * from blocking the first-run home switch.
     */
    private void warmUpAdb() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
                LocalAdb.warmUp(RemoteService.this);

                // Some firmwares keep the HOME role but resolve the factory
                // launcher again after a power cycle. Re-writing the choice
                // here is what keeps the server reachable on the next boot.
                HomeSwitcher.reassert(RemoteService.this);
            }
        }, "adb-warmup");
        t.setDaemon(true);
        t.start();
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
                    if (HttpServerLite.current() == null) {
                        HttpServerLite.ensure(RemoteService.this);
                    }
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
        // The listener is owned by the process, not by this instance: the
        // activity may have started it and may still be on screen. Stopping
        // it here used to take the QR and the port away together.
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
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "HyRemote", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }
}

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
 * Keeps the bridge alive and shows the ongoing notification.
 *
 * The port itself normally belongs to the root-owned service declared in
 * /system/etc/init/hyremote.rc, which starts early in boot and is restarted by
 * init. This class deliberately does NOT take the port away from it: a bridge
 * running as the app uid can bind port 8080 perfectly well, but every
 * `input keyevent` it issues is refused with a SecurityException, so the
 * control would answer {"ok":1} while pressing nothing. Taking a working
 * listener down is strictly worse than doing nothing.
 *
 * What is left here is a fallback: if the init service is missing (factory
 * reset wiped /system, or the app was installed without setup.ps1) the bridge
 * still comes up, just without key injection, and the UI says so.
 */
public class RemoteService extends Service {

    private static final String TAG = "HyRemoteSvc";
    private static final String CHANNEL_ID = "hyremote";
    public static final int PORT = 8080;
    public static final int PORT_MIN = 8080;
    public static final int PORT_MAX = 8095;

    private static final String BOOT_SH = "/data/local/tmp/hyremote/boot.sh";

    /** Guarded so onCreate + onStartCommand + watchdog cannot pile up launches. */
    private final AtomicBoolean launching = new AtomicBoolean(false);

    private Process bridgeProcess;
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
        ensureBridge();
        startWatchdog();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureBridge();
        return START_STICKY;
    }

    /**
     * Starts the shell bridge only when nothing is already serving the port,
     * and only after any previous attempt has had time to fail.
     *
     * The port probe is a socket connect, and Android 13 throws
     * NetworkOnMainThreadException for that on the main thread - which kills
     * the process and takes the launcher down with it. Everything here runs
     * on a worker thread for that reason.
     */
    private void ensureBridge() {
        if (!launching.compareAndSet(false, true)) return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (portIsUp()) return;
                    if (bridgeProcess != null && isAlive(bridgeProcess)) return;
                    if (System.currentTimeMillis() < nextAttemptAt) return;

                    ProcessBuilder pb = new ProcessBuilder("sh", BOOT_SH);
                    pb.redirectErrorStream(true);
                    bridgeProcess = pb.start();
                    Log.i(TAG, "fallback bridge launched");
                } catch (Exception e) {
                    Log.e(TAG, "launch failed", e);
                    // Back off, so a missing /system service does not become a
                    // once-a-second spawn loop.
                    nextAttemptAt = System.currentTimeMillis() + 30000;
                } finally {
                    launching.set(false);
                }
            }
        }, "bridge-launch").start();
    }

    /**
     * Re-checks the port periodically. Without this the bridge stays down for
     * the rest of the session the moment the listener dies, because Android
     * never calls onStartCommand again on its own.
     */
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
                    ensureBridge();
                }
            }
        }, "bridge-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /** True when something is accepting connections on a bridge port. */
    private boolean portIsUp() {
        for (int p = PORT_MIN; p <= PORT_MAX; p++) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", p), 300);
                return true;
            } catch (IOException e) {
                // try the next candidate
            } finally {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
        return false;
    }

    /** The port the bridge is actually on, or 0 when nothing is listening. */
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

    private static boolean isAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    @Override
    public void onDestroy() {
        // Deliberately not killing the bridge here: init owns it, and tearing
        // it down would break the control for as long as the launcher is open.
        super.onDestroy();
    }

    private void startForegroundCompat() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("HyRemote activo")
                .setContentText("Control web en esta red")
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

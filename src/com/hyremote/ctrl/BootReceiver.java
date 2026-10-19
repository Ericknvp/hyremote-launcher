package com.hyremote.ctrl;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Starts the bridge when the projector boots, with no ADB involved.
 *
 * Root is deliberately NOT used. /system/xbin/su is root:shell with mode 4110,
 * so the shell group can execute it but cannot read it, and the exec fails from
 * an app process. Worse, the app has no SHELL permission as a fallback. Since
 * /data/local/tmp/hyremote is world-writable and already holds the scripts
 * after the first launch, writing them straight from Java is enough.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "HyRemoteBoot";
    private static final String DIR = "/data/local/tmp/hyremote";
    public static final String RUNTIME_DIR = DIR;
    private static final String SYS_DIR = "/system/etc/hyremote";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Log.i(TAG, "boot received");

        File dir = new File(DIR);
        if (!dir.exists()) dir.mkdirs();

        installScripts(context, dir);
        Log.i(TAG, "scripts installed");

        // Starting the bridge is RemoteService's job. A receiver cannot: its
        // child processes are killed when the broadcast ends, which is why the
        // bridge never came up after a reboot.
        Intent svc = new Intent(context, RemoteService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }
        Log.i(TAG, "service requested");

        // Launch the launcher directly after boot. On this projector firmware
        // the system sits on the "Smart projector" boot screen and never
        // auto-starts the HOME activity, so the user had to press the home
        // button. Starting it here makes the projector land on the launcher
        // immediately after boot, with no button press needed.
        //
        // Only when we ARE the home app: if the user put the stock launcher
        // back, HyRemote must not jump on top of it at every boot.
        if (isDefaultHome(context)) {
            Intent home = new Intent(context, MainActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(home);
            Log.i(TAG, "launcher started");
        } else {
            Log.i(TAG, "not the home app, staying out of the way");
        }
    }

    /** True when this app is the one the system uses as the home screen. */
    private boolean isDefaultHome(Context ctx) {
        try {
            if (ctx == null) return false;
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri =
                    ctx.getPackageManager().resolveActivity(home, 0);
            return ri != null && ri.activityInfo != null
                    && ctx.getPackageName().equals(ri.activityInfo.packageName);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Copies the bridge scripts out of the APK onto the device.
     *
     * Only a fallback. Once setup.ps1 has run, /system/etc/hyremote is the
     * authoritative copy and init runs the bridge from there. Writing the APK
     * copy over it anyway would let an old bundled script silently replace a
     * newer one on /system, which is exactly the kind of drift that makes a
     * "fix is deployed but nothing changed" bug.
     *
     * It is also what makes a fresh projector work with nothing but the APK:
     * the launcher calls this on start, so the fallback bridge has its scripts
     * even before the device has ever been rebooted.
     */
    public static void installScripts(Context context, File dir) {
        if (new File(SYS_DIR).isDirectory()) {
            Log.i(TAG, "system bridge present, skipping APK script install");
            return;
        }
        String[] names = {"serve.sh", "boot.sh", "handler.sh", "listapps.sh", "buildapps.sh",
                "launch.sh"};
        for (String n : names) {
            File out = new File(dir, n);
            try {
                copy(context.getAssets().open(n), out);
                out.setExecutable(true, false);
            } catch (Exception e) {
                Log.e(TAG, "install failed " + n, e);
            }
        }
    }

    private static void copy(InputStream in, File out) throws Exception {
        OutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        os.close();
        in.close();
    }
}
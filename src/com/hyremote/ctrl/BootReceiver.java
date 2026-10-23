package com.hyremote.ctrl;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Starts the in-app remote after the projector boots.
 *
 * The old receiver copied shell scripts into /data/local/tmp/hyremote. That
 * directory is owned by the shell user on Android 13 and an app cannot create
 * or write it, so the copy failed and the fallback bridge never started. The
 * HTTP server now lives in RemoteService itself, so there is nothing to
 * install: start the service and it binds the port.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "HyRemoteBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Log.i(TAG, "boot received");

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
}

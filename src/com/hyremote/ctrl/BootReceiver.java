package com.hyremote.ctrl;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Starts the in-app remote after the projector boots or the APK is updated.
 *
 * The old receiver copied shell scripts into /data/local/tmp/hyremote. That
 * directory is owned by the shell user on Android 13 and an app cannot create
 * or write it, so the copy failed and the fallback bridge never started. The
 * HTTP server now lives in the process, so there is nothing to install: start
 * the service and it binds the port.
 *
 * Android still requires the app to be opened once after a sideload before it
 * leaves the "stopped" state. That single launch is unavoidable; after it,
 * these actions keep the control alive across reboots and updates.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "HyRemoteBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!handles(action)) return;
        Log.i(TAG, "received " + action);

        RemoteService.start(context);

        // After an update there is nothing to put on screen: the user is
        // probably watching something. Only boot brings the launcher forward.
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;

        // On this projector firmware the system sits on the "Smart projector"
        // boot screen and never auto-starts the HOME activity, so the user had
        // to press the home button. Starting it here makes the projector land
        // on the launcher immediately after boot.
        //
        // Only when we ARE the home app: if the user put the stock launcher
        // back, HyRemote must not jump on top of it at every boot.
        if (HomeSwitcher.isHome(context) || HomeSwitcher.claimed(context)) {
            Intent home = new Intent(context, MainActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                context.startActivity(home);
                Log.i(TAG, "launcher started");
            } catch (Exception e) {
                Log.w(TAG, "could not start the launcher: " + e);
            }
        } else {
            Log.i(TAG, "not the home app, staying out of the way");
        }
    }

    private static boolean handles(String action) {
        if (action == null) return false;
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
    }
}

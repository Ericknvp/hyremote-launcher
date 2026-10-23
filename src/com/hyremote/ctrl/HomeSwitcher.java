package com.hyremote.ctrl;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.util.Log;

/**
 * Changes which app is the projector's home screen.
 *
 * This lives outside MainActivity and HttpServerLite so the first-run
 * automation and the /home endpoint use exactly the same path. Everything is
 * done through the projector's own adbd: no PC, no root bridge, no script in
 * /system.
 */
final class HomeSwitcher {

    private static final String TAG = "HyRemoteHome";

    static final String PKG = "com.hyremote.ctrl";
    static final String ACTIVITY = PKG + "/.MainActivity";

    static final String STOCK_PKG = "com.htc.htclauncherhighenglishd08";
    private static final String STOCK_ACTIVITY =
            STOCK_PKG + "/com.mao.launcher.activity.BarrelActivity";

    private static final String PREFS = "hyremote";
    private static final String KEY_CLAIMED = "home_claimed";

    private HomeSwitcher() {
    }

    /**
     * True when the user already chose HyRemote as HOME on this install.
     *
     * It is what lets the boot receiver and the service put the choice back
     * after a reboot without ever stealing HOME from someone who selected the
     * original launcher again.
     */
    static boolean claimed(Context ctx) {
        try {
            return ctx != null && ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_CLAIMED, false);
        } catch (Exception e) {
            return false;
        }
    }

    /** Records that HOME already points at HyRemote (e.g. after a pm clear). */
    static void markClaimed(Context ctx) {
        remember(ctx, true);
    }

    private static void remember(Context ctx, boolean claimed) {
        try {
            if (ctx == null) return;
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_CLAIMED, claimed).apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * Re-applies the HOME preference after a boot. Some firmwares keep the
     * role but resolve HOME to the factory launcher until the preference is
     * written again, which is exactly the state that leaves a projector
     * without a server after a power cycle.
     */
    static void reassert(Context ctx) {
        if (!claimed(ctx)) return;
        if (!LocalAdb.available(ctx)) return;
        LocalAdb.exec(ctx, "cmd package set-home-activity " + ACTIVITY);
        LocalAdb.exec(ctx, "cmd role add-role-holder --user 0 android.app.role.HOME " + PKG);
    }

    /** True when the system resolves HOME to the given package right now. */
    static boolean isHome(Context ctx) {
        try {
            if (ctx == null) return false;
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = ctx.getPackageManager().resolveActivity(home, 0);
            return ri != null && PKG.equals(ri.activityInfo.packageName);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Normal path: the Inicio picker and the /home endpoint. The ADB
     * connection is expected to be authorized already, so this never starts
     * a second authorization prompt just to change HOME.
     */
    static boolean set(Context ctx, String which) {
        // Do not trust the 30-second cache here: a manual "Inicio" tap must
        // work even on the first frame after a boot. exec() is synchronous and
        // runs on a background thread, so a cold probe is safe.
        String who = LocalAdb.exec(ctx, "id -u");
        if (who == null || !who.trim().matches("\\d+")) return false;
        return apply(ctx, which);
    }

    private static boolean apply(Context ctx, String which) {
        String target;
        String pkg;
        if ("hyremote".equals(which)) {
            target = ACTIVITY;
            pkg = PKG;
        } else if ("original".equals(which)) {
            target = STOCK_ACTIVITY;
            pkg = STOCK_PKG;
            // If it was hidden, bring it back before handing HOME over.
            LocalAdb.exec(ctx, "pm enable --user 0 " + pkg);
        } else {
            return false;
        }

        LocalAdb.exec(ctx, "cmd package set-home-activity " + target);
        // The role command is what makes the choice stick on Android 10+.
        // Older firmwares may not have it; set-home-activity above still does
        // the job there.
        LocalAdb.exec(ctx, "cmd role add-role-holder --user 0 android.app.role.HOME " + pkg);

        boolean home = isHome(ctx);

        // Only hide the vendor launcher after HyRemote really owns HOME:
        // doing it the other way around can leave the projector with no home
        // app at all, which looks exactly like a brick to the user.
        if (home && "hyremote".equals(which)) {
            LocalAdb.exec(ctx, "pm disable-user --user 0 " + STOCK_PKG);
            if (!isHome(ctx)) {
                Log.w(TAG, "disabling the stock launcher broke HOME; restoring it");
                LocalAdb.exec(ctx, "pm enable --user 0 " + STOCK_PKG);
                LocalAdb.exec(ctx, "cmd package set-home-activity " + ACTIVITY);
                home = isHome(ctx);
            }
        }

        if (home) {
            remember(ctx, "hyremote".equals(which));
        }

        Log.i(TAG, "home -> " + which + ": " + home);
        return home;
    }
}

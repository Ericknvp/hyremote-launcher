package com.hyremote.ctrl;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.reflect.Method;

/**
 * The core of Phase 0: the only component the OEM task killer cannot take down,
 * because the system binds it into system_server.
 *
 * IMPORTANT API CONSTRAINT
 * -----------------------
 * AccessibilityService has NO public "injectKeyEvent" method. The public surface is:
 *   - performGlobalAction(int)   -> HOME / BACK / RECENTS / NOTIFICATIONS / etc.
 *   - dispatchGesture(...)       -> taps and swipes at screen coordinates
 *   - onKeyEvent(...)            -> receives key events, does NOT send them
 *
 * So arbitrary keycodes (DPAD, volume, numbers) are attempted through reflection
 * on InputManager.injectInputEvent, which historically worked from an accessibility
 * service but is subject to the hidden-API blocklist on Android 9+.
 * On Android 13 that blocklist may deny it, in which case we fall back to the
 * guaranteed-public paths: global actions and gestures.
 */
public class RemoteAccessibilityService extends AccessibilityService {

    private static final String TAG = "HyRemoteAcc";
    private static volatile boolean connected = false;
    static volatile RemoteAccessibilityService instance = null;

    private Object inputManager;
    private Method injectInputEvent;
    private boolean reflectionUsable = false;

    // AccessibilityService.GLOBAL_ACTION_* values, inlined to avoid a bootclasspath quirk.
    public static final int GLOBAL_BACK = 1;
    public static final int GLOBAL_HOME = 2;
    public static final int GLOBAL_RECENTS = 3;
    public static final int GLOBAL_NOTIFICATIONS = 4;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        instance = this;
        initReflection();
        Log.i(TAG, "accessibility CONNECTED, reflectionUsable=" + reflectionUsable);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Nothing to observe in Phase 0; the service only has to stay alive.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        connected = false;
        if (instance == this) instance = null;
        Log.w(TAG, "accessibility UNBOUND");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        connected = false;
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isReflectionUsable() {
        return reflectionUsable;
    }

    private void initReflection() {
        try {
            Context ctx = this;
            Class<?> imClass = Class.forName("android.hardware.input.InputManager");
            Method getInstance = imClass.getMethod("getInstance");
            inputManager = getInstance.invoke(null);
            injectInputEvent = imClass.getMethod("injectInputEvent", KeyEvent.class, int.class);
            injectInputEvent.setAccessible(true);
            reflectionUsable = true;
            Log.i(TAG, "InputManager reflection available");
        } catch (Throwable t) {
            reflectionUsable = false;
            Log.w(TAG, "InputManager reflection unavailable: " + t);
        }
    }

    /**
     * Sends a keycode. Tries reflection first, then falls back to global actions.
     * Returns true if any path accepted it.
     */
    public boolean injectKey(int keycode) {
        if (keycode <= 0) return false;

        if (reflectionUsable) {
            try {
                long now = SystemClock.uptimeMillis();
                KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keycode, 0);
                KeyEvent up = new KeyEvent(now, now + 30, KeyEvent.ACTION_UP, keycode, 0);
                int mode = 2; // INJECT_INPUT_EVENT_MODE_ASYNC
                Object r1 = injectInputEvent.invoke(inputManager, down, mode);
                Object r2 = injectInputEvent.invoke(inputManager, up, mode);
                Log.i(TAG, "reflect inject " + keycode + " -> " + r1 + "/" + r2);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "reflect inject failed, disabling: " + t);
                reflectionUsable = false;
            }
        }

        // Guaranteed-public fallback.
        switch (keycode) {
            case KeyEvent.KEYCODE_HOME:
                return performGlobalAction(GLOBAL_HOME);
            case KeyEvent.KEYCODE_BACK:
                return performGlobalAction(GLOBAL_BACK);
            case KeyEvent.KEYCODE_APP_SWITCH:
                return performGlobalAction(GLOBAL_RECENTS);
            case KeyEvent.KEYCODE_NOTIFICATION:
                return performGlobalAction(GLOBAL_NOTIFICATIONS);
            default:
                Log.w(TAG, "no public fallback for keycode " + keycode);
                return false;
        }
    }

    /** Taps absolute screen coordinates, e.g. tapping the on-screen D-pad. */
    public boolean tap(int x, int y) {
        try {
            long now = SystemClock.uptimeMillis();
            android.accessibilityservice.GestureDescription.Builder b =
                    new android.accessibilityservice.GestureDescription.Builder();
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x, y);
            path.lineTo(x + 1, y + 1);
            android.accessibilityservice.GestureDescription stroke =
                    b.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(
                            path, 0, 60)).build();

            final boolean[] ok = {false};
            dispatchGesture(stroke, new android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(android.accessibilityservice.GestureDescription g) {
                    ok[0] = true;
                }

                @Override
                public void onCancelled(android.accessibilityservice.GestureDescription g) {
                    ok[0] = false;
                }
            }, null);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "tap failed: " + t);
            return false;
        }
    }

    /**
     * Writes text into whatever editable field has input focus. This is the
     * clean fallback when the local adbd is not available: unlike
     * `input text`, it preserves accents and does not need a shell.
     */
    public boolean setText(String text) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus == null) return false;
            android.os.Bundle args = new android.os.Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } catch (Throwable t) {
            Log.w(TAG, "setText failed: " + t);
            return false;
        }
    }

    /** Swipes between two absolute screen coordinates. */
    public boolean swipe(int x1, int y1, int x2, int y2, int durationMs) {
        try {
            long now = SystemClock.uptimeMillis();
            android.accessibilityservice.GestureDescription.Builder b =
                    new android.accessibilityservice.GestureDescription.Builder();
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            b.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(
                    path, 0, Math.max(1, durationMs)));
            final boolean[] ok = {false};
            dispatchGesture(b.build(),
                    new android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                        @Override
                        public void onCompleted(android.accessibilityservice.GestureDescription g) {
                            ok[0] = true;
                        }

                        @Override
                        public void onCancelled(android.accessibilityservice.GestureDescription g) {
                            ok[0] = false;
                        }
                    }, null);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "swipe failed: " + t);
            return false;
        }
    }

    /** Proves the service can reach window content. */
    public String foregroundPackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return null;
            CharSequence pkg = root.getPackageName();
            return pkg == null ? null : pkg.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public int deviceWidth() {
        return getResources().getConfiguration().screenWidthDp;
    }

    public String sdk() {
        return Build.VERSION.SDK_INT + "";
    }
}

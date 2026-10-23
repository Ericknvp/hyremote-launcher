package com.hyremote.ctrl;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.hyremote.ctrl.qr.QrCode;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The projector's HOME launcher — cinematic dark.
 *
 * One signature look: a deep gradient ground, translucent glass surfaces, a
 * single vivid accent and a hero clock. The QR is generated at runtime from
 * this projector's own WiFi address and the port the bridge actually bound,
 * so the same APK works on any HY320 in any home.
 */
public class MainActivity extends Activity {

    // ---------------------------------------------------------------- tokens

    private static final int GROUND     = 0xFF05070C;
    private static final int GROUND_TOP = 0xFF0C1526;
    private static final int SURFACE    = 0x14FFFFFF; // glass fill
    private static final int SURFACE_HI = 0x26FFFFFF; // focused fill
    private static final int HAIRLINE   = 0x1FFFFFFF;
    private static final int INK        = 0xFFF4F7FA;
    private static final int INK_DIM    = 0xFF9BA8B6;
    private static final int INK_FAINT  = 0xFF5E6A78;
    private static final int ACCENT     = 0xFF4C8DFF;
    private static final int LIVE       = 0xFF34D399;
    private static final int WARN       = 0xFFFF6B6B;

    private static final float T_CLOCK  = 48f;
    private static final float T_TITLE  = 17f;
    private static final float T_BODY   = 13.5f;
    private static final float T_LABEL  = 12f;
    private static final float T_STATUS = 11.5f;

    private static final int R_CARD = 22;
    private static final int R_TILE = 16;
    private static final int R_PILL = 999;
    private static final int R_PLATE = 14;

    private static final int LEFT_W_DP = 392;

    static final String CREDIT_HANDLE = "@ericknvp";
    static final String PORTFOLIO_URL = "https://ericknvp.dev";
    static final String PORTFOLIO_HOST = "ericknvp.dev";

    // The bridge picks its own port inside this range; the launcher finds it
    // by probing localhost, so nothing here has to be kept in sync by hand.
    static final int PORT_MIN = 8080;
    static final int PORT_MAX = 8095;

    private static final int REQ_BLUETOOTH = 42;
    private static final int BT_NONE = 0;
    private static final int BT_TOGGLE = 1;
    private int pendingBluetooth = BT_NONE;

    // ---------------------------------------------------------------- state

    private TextView clock;
    private TextView dateView;
    private TextView statusLabel;
    private TextView statusMeta;
    private TextView urlView;
    private TextView ssidView;
    private View statusDot;
    private ImageView qrView;
    private View primaryFocus;
    private GridLayout appGrid;
    private Map<String, Long> recencyMap;

    // Decoding a launcher icon costs real time on this SoC, and the app column
    // is rebuilt every time the launcher resumes. These keep the work of one
    // decode for the life of the process.
    private final Map<String, android.graphics.drawable.Drawable> iconCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> labelCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private Map<String, Integer> usageCache;
    private long usageCacheAt;
    private long recencyCacheAt;

    // The app list is loaded once, off the main thread, and rebuilt from this
    // snapshot every time the launcher resumes.
    private volatile List<ResolveInfo> appList;
    private volatile boolean appListLoading = false;

    private LinearLayout appsColumn;
    private String lastForegroundPkg;
    private StatusIconView wifiIndicator;
    private StatusIconView btIndicator;
    private android.app.Dialog aboutDialog;
    private boolean pendingHomeCheck = false;
    private android.app.Dialog appDialog;
    private android.app.Dialog launcherDialog;
    private BluetoothPanel bluetoothPanel;

    private int bridgePort = RemoteService.PORT;
    private String lastQrUrl = null;

    // Startup is measured from the moment the process was forked, not from
    // onCreate: on a cold boot the launcher pays for the framework, the dex
    // verification and the package scan before our first line runs.
    private static final long PROCESS_START_MS = android.os.Process.getStartElapsedRealtime();
    private boolean firstFrameLogged = false;

    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private boolean animationsEnabled = true;
    private static final long POLL_MS = 2000;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshClock();
            pollBridge();
            updateConnectivity();
            handler.postDelayed(this, POLL_MS);
        }
    };

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The remote comes first. A fresh sideload, a slow SoC or a firmware
        // where one UI control throws must not leave the projector with no
        // server: bind the port and start the service before drawing anything.
        startRemoteService();
        bootstrapServer();

        // Claiming HOME is also a first-frame concern: it uses a short ADB
        // probe and must run before the two-minute authorization warm-up.
        claimHomeIfNeeded();

        try {
            buildUi();
        } catch (Throwable t) {
            // Never lose the QR because a tile, an icon or a vendor API is
            // missing on a projector we have not seen. The fallback screen is
            // the smallest thing that still lets the phone take control.
            Log.e("HyRemote", "launcher UI failed, using the minimal screen", t);
            try {
                showMinimalScreen();
            } catch (Throwable ignored) {
                // Nothing else can be done; the service already owns the port.
            }
        }

        Log.i("HyRemote", "onCreate terminó a los " + sinceProcessStart() + " ms");
    }

    /** Milliseconds since this process was forked, for the boot log. */
    private long sinceProcessStart() {
        return SystemClock.elapsedRealtime() - PROCESS_START_MS;
    }

    /** The launcher screen, isolated so a failure has a fallback. */
    private void buildUi() {
        animationsEnabled = readAnimationsEnabled();
        paintSystemBars();

        FrameLayout root = new FrameLayout(this);
        root.setBackground(buildBackdrop());
        setContentView(root);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(26), dp(18), dp(26), dp(16));
        // NOT focusable: a focusable container swallows the D-pad and the
        // status icons in the top bar become unreachable.
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        content.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int bottom, sides;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars()
                                    | WindowInsets.Type.displayCutout());
                    bottom = bars.bottom;
                    sides = bars.left + bars.right;
                } else {
                    bottom = insets.getSystemWindowInsetBottom();
                    sides = insets.getSystemWindowInsetLeft()
                            + insets.getSystemWindowInsetRight();
                }
                v.setPadding(dp(26) + sides, dp(18), dp(26) + sides, dp(16) + bottom);
                return insets;
            }
        });

        content.addView(buildTopBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));
        content.addView(buildBody(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        content.addView(buildBottomBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        hideStatusBar();

        // The QR must be on screen on the first frame, not after the first
        // poll: a new user is standing in front of the projector with a phone.
        updateConnectCard();

        logFirstFrame(root);
    }

    /**
     * Says how long the projector took to actually paint something.
     *
     * "Tarda en iniciar" has three very different causes - the system
     * delivering BOOT_COMPLETED late, our own main thread doing too much
     * before the first draw, or the SoC being slow to load the dex - and the
     * only way to tell them apart is a number measured at the frame.
     */
    private void logFirstFrame(View root) {
        if (firstFrameLogged) return;
        final ViewTreeObserver vto = root.getViewTreeObserver();
        vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (!firstFrameLogged) {
                    firstFrameLogged = true;
                    Log.i("HyRemote", "primer frame a los "
                            + sinceProcessStart() + " ms del proceso");
                }
                if (vto.isAlive()) vto.removeOnPreDrawListener(this);
                return true;
            }
        });
    }

    /**
     * Starts the in-app listener in this process as well as the service. Both
     * go through the same singleton, so whichever wins binds the port and the
     * other reuses it instead of opening a second one.
     */
    private void bootstrapServer() {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final HttpServerLite s = HttpServerLite.ensure(app);
                if (s == null) return;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        bridgePort = s.getPort();
                        updateConnectCard();
                    }
                });
            }
        }, "server-boot").start();
    }

    /**
     * First run: make HyRemote the home screen with no dialog when the
     * projector's local ADB is available. That is what makes the server come
     * back after a reboot on a plain sideload, without a PC or setup.ps1.
     *
     * If ADB is not available, the system role dialog is the only remaining
     * way to change HOME, and the user confirms it once with the physical
     * remote. The QR and the server do not depend on that choice.
     */
    private void claimHomeIfNeeded() {
        if (HomeSwitcher.isHome(this)) {
            // The role can survive a data wipe even though our prefs do not.
            // Remember it so the boot path keeps writing the preference.
            HomeSwitcher.markClaimed(this);
            return;
        }

        final android.content.SharedPreferences sp =
                getSharedPreferences("hyremote", MODE_PRIVATE);
        if (sp.getBoolean("home_claim_done", false)) return;
        sp.edit().putBoolean("home_claim_done", true).apply();

        new Thread(new Runnable() {
            @Override
            public void run() {
                // Do not open a fresh ADB connection here. On a first run the
                // key is not authorized yet, so a 4 s probe would trigger the
                // "Allow debugging?" prompt and time out, leaving a pending
                // prompt that blocks the 120 s warm-up. Use the system role
                // dialog instead; once the warm-up has the key, the Inicio
                // tile can switch HOME without a second prompt.
                boolean ok = LocalAdb.available(MainActivity.this)
                        && HomeSwitcher.set(MainActivity.this, "hyremote");
                if (!ok) ok = HomeSwitcher.isHome(MainActivity.this);
                final boolean claimed = ok;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (claimed) {
                            toast("HyRemote quedó como inicio");
                            return;
                        }
                        // Do not open a second system dialog here: the
                        // service is about to show the ADB authorization
                        // prompt. The Inicio tile is always available for the
                        // user who wants HyRemote as the home screen.
                        toast("Toca Inicio para fijar HyRemote");
                    }
                });
            }
        }, "claim-home").start();
    }

    /**
     * Last-resort screen: logo, the URL and the QR. It is what a user sees if
     * the full launcher cannot be built on an unknown firmware, and it still
     * delivers the whole point of the app.
     */
    private void showMinimalScreen() {
        paintSystemBars();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(24), dp(28), dp(24));
        root.setBackgroundColor(GROUND);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo_mark);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(logo, new LinearLayout.LayoutParams(dp(84), dp(84)));

        TextView title = text("HyRemote", 28, INK, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        qrView = new ImageView(this);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrView.setBackground(rounded(0xFFFFFFFF, dp(R_PLATE), 0));
        qrView.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(220), dp(220));
        qlp.topMargin = dp(18);
        root.addView(qrView, qlp);

        urlView = text("Buscando la red…", 14, INK_DIM, Typeface.NORMAL);
        urlView.setGravity(Gravity.CENTER);
        urlView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ulp.topMargin = dp(14);
        root.addView(urlView, ulp);

        TextView hint = text("Escanea el QR desde el celular.\nMisma red WiFi.",
                T_BODY, INK_DIM, Typeface.NORMAL);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(10);
        root.addView(hint, hlp);

        setContentView(root);
        hideStatusBar();
        updateConnectCard();
        logFirstFrame(root);
    }

    /** Starts RemoteService with the right API for the running Android version. */
    private void startRemoteService() {
        RemoteService.start(this);
    }

    private LayerDrawable buildBackdrop() {
        GradientDrawable base = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{GROUND_TOP, GROUND, GROUND});
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setColors(new int[]{0x334C8DFF, 0x00101830});
        glow.setGradientCenter(0.5f, 0.02f);
        glow.setGradientRadius(dp(620));
        LayerDrawable layers = new LayerDrawable(new android.graphics.drawable.Drawable[]{base, glow});
        return layers;
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
        refreshApps();
        if (appList != null) publishAppListAsync();
        checkHomeResult();
        // Do not wait two seconds for the first poll: a new user may already
        // be pointing the phone at the screen.
        updateConnectCard();
    }

    /** Rebuilds the app sections with the current recency order. */
    private void refreshApps() {
        if (appsColumn == null) return;
        // A copy: the background thread that publishes the app list for the
        // phone may be reading the current snapshot.
        if (appList != null) appList = sortApps(new ArrayList<>(appList));
        View focused = appsColumn.findFocus();
        Object focusTag = focused == null ? null : focused.getTag();
        fillAppsColumn(appsColumn);
        if (focusTag != null) restoreFocus(appsColumn, focusTag);
    }

    /** Loads (or reloads) the app list without touching the main thread. */
    private void loadAppsAsync() {
        if (appListLoading) return;
        appListLoading = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<ResolveInfo> apps = queryApps();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        appListLoading = false;
                        appList = apps;
                        fillAppsColumn(appsColumn);
                    }
                });
                // The bridge hands the same list to the phone, so it is
                // published from the same background pass.
                publishAppList();
            }
        }, "load-apps").start();
    }

    /** Forgets the cache: an app was hidden or uninstalled. */
    private void reloadApps() {
        appList = null;
        if (appListLoading) return;
        loadAppsAsync();
    }

    /** Gives the focus back to the tile that had it, after a rebuild. */
    private void restoreFocus(View parent, Object tag) {
        if (!(parent instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) parent;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (tag.equals(c.getTag())) {
                c.requestFocus();
                return;
            }
            restoreFocus(c, tag);
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        if (primaryFocus != null && !primaryFocus.hasFocus()) {
            primaryFocus.requestFocus();
        }
        hideStatusBar();
    }

    // ---------------------------------------------------------------- top bar

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        ImageView brand = new ImageView(this);
        brand.setImageResource(R.drawable.logo_mark);
        brand.setScaleType(ImageView.ScaleType.FIT_CENTER);
        brand.setContentDescription("HyRemote");
        bar.addView(brand, new LinearLayout.LayoutParams(dp(32), dp(32)));

        TextView word = text("HyRemote", T_TITLE, INK, Typeface.BOLD);
        word.setLetterSpacing(-0.01f);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        wlp.leftMargin = dp(11);
        bar.addView(word, wlp);

        // Which screen this is, in the same voice as the "APLICACIONES" label.
        View sep = new View(this);
        sep.setBackgroundColor(HAIRLINE);
        LinearLayout.LayoutParams seplp = new LinearLayout.LayoutParams(dp(1), dp(14));
        seplp.leftMargin = dp(12);
        bar.addView(sep, seplp);

        TextView section = text("INICIO", 10.5f, INK_FAINT, Typeface.BOLD);
        section.setLetterSpacing(0.18f);
        LinearLayout.LayoutParams seclp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        seclp.leftMargin = dp(12);
        bar.addView(section, seclp);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        // WiFi and Bluetooth: state-aware icons that open the screen where
        // each radio is configured. On this firmware the Android settings app
        // has no Bluetooth entry at all, so the icon is the only way in.
        // Under the WiFi mark sits the name of the network it is on.
        LinearLayout wifiBlock = new LinearLayout(this);
        wifiBlock.setOrientation(LinearLayout.VERTICAL);
        wifiBlock.setGravity(Gravity.CENTER);
        wifiBlock.setBackground(rounded(SURFACE, dp(R_PILL), dp(1)));
        wifiBlock.setFocusable(true);
        wifiBlock.setFocusableInTouchMode(false);
        wifiBlock.setClickable(true);
        applyFocus(wifiBlock, dp(R_PILL));
        wifiBlock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openWifi();
            }
        });

        wifiIndicator = new StatusIconView(this);
        wifiIndicator.setIcon(StatusIconView.ICON_WIFI);
        wifiIndicator.setColors(ACCENT, INK_FAINT);
        wifiIndicator.setFocusable(false);
        wifiBlock.addView(wifiIndicator, new LinearLayout.LayoutParams(dp(22), dp(22)));

        ssidView = text("", 9.5f, INK_FAINT, Typeface.NORMAL);
        ssidView.setGravity(Gravity.CENTER);
        ssidView.setMaxLines(1);
        ssidView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        slp2.topMargin = dp(1);
        wifiBlock.addView(ssidView, slp2);

        LinearLayout.LayoutParams wifiBlockLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        wifiBlockLp.width = dp(96);
        bar.addView(wifiBlock, wifiBlockLp);

        btIndicator = statusIcon(StatusIconView.ICON_BLUETOOTH, new Runnable() {
            @Override
            public void run() {
                showBluetoothPanel();
            }
        });
        LinearLayout.LayoutParams btlp = new LinearLayout.LayoutParams(dp(34), dp(34));
        btlp.leftMargin = dp(8);
        bar.addView(btIndicator, btlp);

        // Live pill
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(rounded(SURFACE, dp(R_PILL), dp(1)));
        pill.setPadding(dp(13), dp(7), dp(14), dp(7));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = dp(16);
        bar.addView(pill, plp);

        statusDot = new View(this);
        statusDot.setBackground(oval(INK_FAINT));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dlp.rightMargin = dp(8);
        pill.addView(statusDot, dlp);

        statusLabel = text("Conectando", T_STATUS, INK_DIM, Typeface.NORMAL);
        pill.addView(statusLabel);

        return bar;
    }

    private View paintDot(boolean active) {
        View v = new View(this);
        v.setBackground(oval(active ? ACCENT : INK_FAINT));
        return v;
    }

    /** A tappable status icon: WiFi or Bluetooth, lit when the radio is on. */
    private StatusIconView statusIcon(int which, final Runnable action) {
        StatusIconView v = new StatusIconView(this);
        v.setIcon(which);
        v.setColors(ACCENT, INK_FAINT);
        v.setBackground(rounded(SURFACE, dp(R_PILL), dp(1)));
        v.setFocusable(true);
        v.setFocusableInTouchMode(false);
        v.setClickable(true);
        v.setStateListAnimator(null);
        applyFocus(v, dp(R_PILL));
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                action.run();
            }
        });
        return v;
    }

    // One formatter, reused: the old code built two SimpleDateFormat objects
    // every two seconds, which is pure garbage for a clock that only changes
    // once a minute.
    private final java.text.SimpleDateFormat clockFormat =
            new java.text.SimpleDateFormat("HH:mm");
    private final java.text.SimpleDateFormat dateFormat =
            new java.text.SimpleDateFormat("EEEE d MMM", new java.util.Locale("es", "CO"));

    private void refreshClock() {
        if (clock != null) {
            String hhmm = clockFormat.format(new java.util.Date());
            // setText on the same string still walks the whole text layout.
            if (!hhmm.contentEquals(clock.getText())) clock.setText(hhmm);
        }
        if (dateView != null) {
            String d = dateFormat.format(new java.util.Date())
                    .replace('.', ' ').toUpperCase();
            if (!d.contentEquals(dateView.getText())) dateView.setText(d);
        }
    }

    // ---------------------------------------------------------------- body

    private View buildBody() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);

        body.addView(buildHero(), new LinearLayout.LayoutParams(
                dp(LEFT_W_DP), LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams appsLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        appsLp.leftMargin = dp(26);
        body.addView(buildApps(), appsLp);
        return body;
    }

    /**
     * The left column: the clock and the quick actions on the left, the
     * connect card with the QR on the right, where the space was.
     */
    private View buildHero() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);

        dateView = text("", T_LABEL, ACCENT, Typeface.BOLD);
        dateView.setLetterSpacing(0.16f);
        col.addView(dateView);

        clock = text("", T_CLOCK, INK, Typeface.BOLD);
        clock.setLetterSpacing(-0.03f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(-4);
        col.addView(clock, clp);

        // ---- the tiles and the QR share the same row, so they line up
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);

        View quick = buildQuickActions();
        row.addView(quick, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---- right: the connect card with the QR
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(16), dp(22), dp(16), dp(26));
        card.setBackground(rounded(SURFACE, dp(R_CARD), dp(1)));

        qrView = new ImageView(this);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrView.setBackground(rounded(0xFFFFFFFF, dp(R_PLATE), 0));
        qrView.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.addView(qrView, new LinearLayout.LayoutParams(dp(128), dp(128)));

        TextView hint = text("Escanea el QR para controlar\ndesde el celular",
                T_BODY, INK, Typeface.BOLD);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(14);
        card.addView(hint, hlp);

        TextView sameNet = text("Debes estar en la misma red WiFi",
                T_STATUS, INK_DIM, Typeface.NORMAL);
        sameNet.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams snlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        snlp.topMargin = dp(4);
        card.addView(sameNet, snlp);

        urlView = text("Buscando la red…", 9.5f, INK_FAINT, Typeface.NORMAL);
        urlView.setGravity(Gravity.CENTER);
        urlView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ulp.topMargin = dp(10);
        card.addView(urlView, ulp);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                dp(190), LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.leftMargin = dp(16);
        row.addView(card, cardLp);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        rowLp.topMargin = dp(16);
        col.addView(row, rowLp);

        return col;
    }

    private View buildApps() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        appsColumn = col;

        // The first frame must not wait for the package scan. The column is
        // built empty and filled when the list arrives from a background
        // thread; on this SoC the scan plus forty icon decodes is the single
        // most expensive thing the launcher does.
        fillAppsColumn(col);
        loadAppsAsync();
        return col;
    }

    /** Builds the two sections: the recent apps on top, the rest below. */
    private void fillAppsColumn(LinearLayout col) {
        if (col == null) return;
        col.removeAllViews();

        List<ResolveInfo> all = appList;
        if (all == null) {
            TextView loading = text("Cargando aplicaciones…", T_BODY, INK_DIM,
                    Typeface.NORMAL);
            loading.setGravity(Gravity.CENTER);
            col.addView(loading, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            if (!appListLoading) loadAppsAsync();
            return;
        }

        // ---- RECIENTES: the apps opened lately, bigger, on their own row
        List<ResolveInfo> recent = new ArrayList<>();
        List<ResolveInfo> rest = new ArrayList<>();
        splitRecent(all, recent, rest);

        if (!recent.isEmpty()) {
            col.addView(sectionHeader("RECIENTES"), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));
            col.addView(buildRecentRow(recent), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));
        }

        col.addView(sectionHeader("APLICACIONES"), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        // The scroll view must NOT be focusable: a focusable ScrollView eats
        // the DOWN key to scroll, and the bottom bar becomes unreachable with
        // the D-pad. Letting the tiles own the focus makes the view scroll
        // itself to the focused tile instead.
        FrameLayout stack = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(10));
        scroll.addView(buildAppGrid(rest));
        stack.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Fade the last row into the background instead of cutting it off.
        GradientDrawable scrim = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x0005070C, 0xE005070C});
        View fade = new View(this);
        fade.setBackground(scrim);
        fade.setFocusable(false);
        FrameLayout.LayoutParams fadeLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(26));
        fadeLp.gravity = Gravity.BOTTOM;
        stack.addView(fade, fadeLp);

        col.addView(stack, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private View buildAppGrid(List<ResolveInfo> apps) {
        GridLayout grid = new GridLayout(this);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        appGrid = grid;
        fillGrid(grid, apps);
        return grid;
    }

    /** A section title with the hairline rule that runs to the edge. */
    private View sectionHeader(String label) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView h = text(label, T_LABEL, INK_FAINT, Typeface.BOLD);
        h.setLetterSpacing(0.18f);
        header.addView(h);

        View rule = new View(this);
        rule.setBackgroundColor(HAIRLINE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, dp(1), 1f);
        rlp.leftMargin = dp(14);
        header.addView(rule, rlp);
        return header;
    }

    /**
     * Splits the apps into the ones used lately and the rest. Only the apps
     * opened in the last week count as recent, so the row does not fill up
     * with things touched once and never again.
     */
    private void splitRecent(List<ResolveInfo> all, List<ResolveInfo> recent,
                              List<ResolveInfo> rest) {
        Map<String, Long> recency = recencyMap;
        long cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        for (ResolveInfo ri : all) {
            String pkg = ri.activityInfo.packageName;
            Long last = recency == null ? null : recency.get(pkg);
            boolean isRecent = last != null && last > cutoff;
            if (isRecent && recent.size() < 4) {
                recent.add(ri);
            } else {
                rest.add(ri);
            }
        }
    }

    /** The row of recently used apps, with bigger tiles than the grid. */
    private View buildRecentRow(List<ResolveInfo> apps) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (ResolveInfo ri : apps) {
            row.addView(buildRecentTile(ri));
        }
        return row;
    }

    private View buildRecentTile(final ResolveInfo ri) {
        final String label = labelOf(ri);

        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(8), dp(12), dp(8), dp(10));
        tile.setBackground(rounded(SURFACE, dp(R_TILE), dp(1)));
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(false);
        tile.setClickable(true);
        tile.setTag(ri.activityInfo.packageName);
        applyFocus(tile, dp(R_TILE));

        android.graphics.drawable.Drawable icon = null;
        try {
            icon = iconOf(ri);
        } catch (Exception ignored) {
        }
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(iv, new LinearLayout.LayoutParams(dp(56), dp(56)));
        }

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(INK);
        name.setTextSize(12);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(8);
        tile.addView(name, nlp);

        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchApp(ri);
            }
        });
        tile.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showUninstallDialog(ri.activityInfo.packageName, label);
                return true;
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(126), dp(112));
        lp.rightMargin = dp(8);
        tile.setLayoutParams(lp);
        return tile;
    }

    /** Fills the grid with the app tiles, in recency order. */
    private void fillGrid(GridLayout grid, List<ResolveInfo> apps) {
        grid.removeAllViews();
        if (apps.isEmpty()) {
            TextView empty = text("No hay apps para lanzar.", T_BODY, INK_DIM, Typeface.NORMAL);
            empty.setGravity(Gravity.CENTER);
            grid.addView(empty);
            return;
        }

        int screenDp = getResources().getConfiguration().screenWidthDp;
        int cols = Math.max(4, (screenDp - LEFT_W_DP - 70) / 112);
        grid.setColumnCount(cols);

        for (final ResolveInfo ri : apps) {
            View tile = buildTile(ri, labelOf(ri));
            grid.addView(tile, tileParams());
            if (primaryFocus == null) primaryFocus = tile;
        }
    }

    /**
     * The apps, ordered by how recently they were used.
     *
     * The first rows are the ones the user actually opens, so the thing you
     * use every day stays one keypress away instead of buried in an
     * alphabetical list. Apps that were never opened keep the alphabetical
     * order below them.
     */
    /** The installed launcher activities. Heavy: background threads only. */
    private List<ResolveInfo> queryApps() {
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);

        Set<String> seen = new HashSet<>();
        List<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo ri : resolved) {
            if (ri.activityInfo == null) continue;
            if (getPackageName().equals(ri.activityInfo.packageName)) continue;
            if (seen.add(ri.activityInfo.packageName)) apps.add(ri);
        }

        recencyMap = readRecency();
        readUsageStats();
        return sortApps(apps);
    }

    /** Orders a list by recency, then usage, then name. */
    private List<ResolveInfo> sortApps(List<ResolveInfo> apps) {
        final Map<String, Long> recency = readRecency();
        final Map<String, Integer> usage = readUsageStats();
        Collections.sort(apps, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                String pa = a.activityInfo.packageName;
                String pb = b.activityInfo.packageName;
                long ra = recency.containsKey(pa) ? recency.get(pa) : 0L;
                long rb = recency.containsKey(pb) ? recency.get(pb) : 0L;
                if (ra != rb) return ra > rb ? -1 : 1;
                int ua = use(usage, pa);
                int ub = use(usage, pb);
                if (ua != ub) return ub - ua;
                return labelOf(a).compareToIgnoreCase(labelOf(b));
            }
        });
        return apps;
    }

    /** The app's label, decoded once. */
    private String labelOf(ResolveInfo ri) {
        String pkg = ri.activityInfo.packageName;
        String cached = labelCache.get(pkg);
        if (cached != null) return cached;
        String label;
        try {
            label = String.valueOf(ri.loadLabel(getPackageManager()));
        } catch (Exception e) {
            label = pkg;
        }
        labelCache.put(pkg, label);
        return label;
    }

    /** The app's icon, decoded once. */
    private android.graphics.drawable.Drawable iconOf(ResolveInfo ri) {
        String pkg = ri.activityInfo.packageName;
        android.graphics.drawable.Drawable cached = iconCache.get(pkg);
        if (cached != null) return cached;
        try {
            android.graphics.drawable.Drawable d = ri.loadIcon(getPackageManager());
            if (d != null) iconCache.put(pkg, d);
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    /** Records that an app was just opened, so it moves to the top. */
    private void markUsed(String pkg) {
        long now = System.currentTimeMillis();
        try {
            getSharedPreferences("hyremote.recent", MODE_PRIVATE)
                    .edit()
                    .putLong(pkg, now)
                    .apply();
        } catch (Exception ignored) {
        }
        // Sorting right after opening an app must not wait for the prefs to be
        // written and read back.
        if (recencyMap != null) recencyMap.put(pkg, now);
    }

    /**
     * Last time each package was in the foreground, from the system stats and
     * from what this launcher saw itself.
     */
    private Map<String, Long> readRecency() {
        long now = System.currentTimeMillis();
        // The system keeps 90 days of history and querying it is not free.
        // One read per minute is more than enough for an ordering, and
        // markUsed() keeps the map current in between.
        if (recencyMap != null && now - recencyCacheAt < 60L * 1000) {
            return recencyMap;
        }
        Map<String, Long> out = new HashMap<>();
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("hyremote.recent", MODE_PRIVATE);
            for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                Object v = e.getValue();
                if (v instanceof Long) out.put(e.getKey(), (Long) v);
            }
        } catch (Exception ignored) {
        }
        try {
            android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager)
                            getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                recencyMap = out;
                recencyCacheAt = now;
                return out;
            }

            long end = System.currentTimeMillis();
            long start = end - 90L * 24 * 60 * 60 * 1000;
            List<android.app.usage.UsageStats> stats = usm.queryUsageStats(
                    android.app.usage.UsageStatsManager.INTERVAL_BEST, start, end);
            if (stats == null) {
                recencyMap = out;
                recencyCacheAt = now;
                return out;
            }
            for (android.app.usage.UsageStats u : stats) {
                String pkg = u.getPackageName();
                long last = 0;
                try {
                    last = u.getLastTimeUsed();
                } catch (Throwable ignored) {
                }
                Long known = out.get(pkg);
                if (known == null || last > known) out.put(pkg, last);
            }
        } catch (Exception e) {
            Log.w("HyRemote", "recency stats unavailable: " + e);
        }
        recencyMap = out;
        recencyCacheAt = now;
        return out;
    }

    /** Opens an app and moves it to the top of the recent row. */
    private void launchApp(ResolveInfo ri) {
        markUsed(ri.activityInfo.packageName);
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
        // The app just became the most recent one: move it up.
        refreshApps();
    }

    private View buildTile(final ResolveInfo ri, final String label) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(6), dp(14), dp(6), dp(12));
        tile.setBackground(rounded(SURFACE, dp(R_TILE), dp(1)));
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(false);
        tile.setClickable(true);
        tile.setTag(ri.activityInfo.packageName);
        applyFocus(tile, dp(R_TILE));

        android.graphics.drawable.Drawable icon = null;
        try {
            icon = iconOf(ri);
        } catch (Exception ignored) {
        }

        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(iv, new LinearLayout.LayoutParams(dp(46), dp(46)));
        } else {
            TextView iv = new TextView(this);
            iv.setText(label.isEmpty() ? "?" : label.substring(0, 1).toUpperCase());
            iv.setTextColor(GROUND);
            iv.setTextSize(18);
            iv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            iv.setGravity(Gravity.CENTER);
            iv.setBackground(rounded(0xFFE8EAF0, dp(R_PILL), 0));
            tile.addView(iv, new LinearLayout.LayoutParams(dp(46), dp(46)));
        }

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(INK);
        name.setTextSize(T_LABEL);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(9);
        tile.addView(name, nlp);

        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchApp(ri);
            }
        });

        tile.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showUninstallDialog(ri.activityInfo.packageName, label);
                return true;
            }
        });

        return tile;
    }

    /**
     * Long press on a tile.
     *
     * Almost every app on a projector is a system app, and the system
     * installer refuses to uninstall those: it opens nothing and the app stays.
     * So the dialog offers the two real ways out: a normal uninstall for the
     * apps that can be uninstalled, and hiding the system ones - which is done
     * through the root bridge, because only root can disable a package.
     */
    private void showUninstallDialog(final String pkg, final String label) {
        final boolean system = isSystemApp(pkg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(24), dp(28), dp(22));
        root.setBackground(rounded(0xFF0B1220, dp(22), dp(1), 0x1FFFFFFF));

        TextView title = text(system ? "Ocultar " + label + "?" : "Desinstalar " + label + "?",
                19, INK, Typeface.BOLD);
        root.addView(title);

        TextView body = text(system
                ? "Es una app del sistema y no se puede desinstalar.\n"
                + "Se puede ocultar del launcher: seguirá instalada, pero no aparecerá."
                : "Se eliminará la aplicación del proyector.",
                T_BODY, INK_DIM, Typeface.NORMAL);
        body.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(10);
        root.addView(body, blp);

        TextView pkgView = text(pkg, 11, 0xFF5E6A78, Typeface.NORMAL);
        pkgView.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(6);
        root.addView(pkgView, plp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(20);
        root.addView(actions, alp);

        Button primary = chipButton(system ? "Ocultar" : "Desinstalar", new Runnable() {
            @Override
            public void run() {
                if (system) hideApp(pkg);
                else uninstallApp(pkg);
                if (appDialog != null) appDialog.dismiss();
            }
        });
        primary.setTextColor(ACCENT);
        actions.addView(primary);

        actions.addView(chipButton("Cancelar", new Runnable() {
            @Override
            public void run() {
                if (appDialog != null) appDialog.dismiss();
            }
        }));

        appDialog = new android.app.Dialog(this);
        appDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        appDialog.setContentView(root);
        android.view.Window w = appDialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        }
        appDialog.show();
        if (w != null) w.setLayout(dp(440), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** True when the package lives in the system image. */
    private boolean isSystemApp(String pkg) {
        try {
            android.content.pm.ApplicationInfo ai =
                    getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Hides a system app from the launcher, through the root bridge. */
    private void hideApp(final String pkg) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = postBridge("/pkg", "disable " + pkg);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(ok ? "App oculta del launcher" : "No se pudo ocultar");
                        if (ok) {
                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    // The list changed on disk: query it
                                    // again instead of trusting the snapshot.
                                    reloadApps();
                                }
                            }, 600);
                        }
                    }
                });
            }
        }, "hide-app").start();
    }

    /** The normal uninstall, for the apps the installer accepts. */
    private void uninstallApp(String pkg) {
        try {
            Intent intent = new Intent(Intent.ACTION_DELETE);
            intent.setData(android.net.Uri.parse("package:" + pkg));
            startActivity(intent);
        } catch (Exception e) {
            toast("No se pudo desinstalar");
        }
    }

    private GridLayout.LayoutParams tileParams() {
        GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
        gp.width = 0;
        gp.height = GridLayout.LayoutParams.WRAP_CONTENT;
        gp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
        int g = dp(5);
        gp.setMargins(g, g, g, g);
        return gp;
    }

    // ------------------------------------------------------------- bottom bar

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        bar.addView(chipButton("Accesibilidad", new Runnable() {
            @Override
            public void run() {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception ignored) {
                }
            }
        }));
        bar.addView(chipButton("Bridge", new Runnable() {
            @Override
            public void run() {
                try {
                    startRemoteService();
                } catch (Exception ignored) {
                }
                toast("Bridge reiniciado");
            }
        }));
        bar.addView(chipButton("Apps", new Runnable() {
            @Override
            public void run() {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_SETTINGS));
                } catch (Exception ignored) {
                }
            }
        }));
        bar.addView(chipButton("Acerca de", new Runnable() {
            @Override
            public void run() {
                showAboutDialog();
            }
        }));

        return bar;
    }

    // --------------------------------------------------------------- about

    private void showAboutDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(32), dp(30), dp(32), dp(26));
        root.setBackground(rounded(0xFF0B1220, dp(R_CARD), dp(1), HAIRLINE));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo_mark);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(logo, new LinearLayout.LayoutParams(dp(108), dp(108)));

        TextView title = text("HyRemote", 22, INK, Typeface.BOLD);
        title.setLetterSpacing(-0.02f);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(12);
        root.addView(title, tlp);

        TextView sub = text("Launcher y control remoto para tu proyector",
                T_LABEL, INK_DIM, Typeface.NORMAL);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(6);
        root.addView(sub, slp);

        View rule = new View(this);
        rule.setBackgroundColor(HAIRLINE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(190), dp(1));
        rlp.topMargin = dp(22);
        root.addView(rule, rlp);

        TextView by = text("CREADO POR", T_STATUS, INK_FAINT, Typeface.BOLD);
        by.setLetterSpacing(0.16f);
        by.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(18);
        root.addView(by, blp);

        TextView handle = text(CREDIT_HANDLE, 19, ACCENT, Typeface.BOLD);
        handle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(4);
        root.addView(handle, hlp);

        TextView site = text(PORTFOLIO_HOST, T_BODY, INK_DIM, Typeface.NORMAL);
        site.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sitelp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sitelp.topMargin = dp(2);
        root.addView(site, sitelp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(22);
        root.addView(actions, alp);

        Button portfolio = chipButton("Ver portfolio", new Runnable() {
            @Override
            public void run() {
                openPortfolio();
            }
        });
        portfolio.setTextColor(ACCENT);
        actions.addView(portfolio);

        actions.addView(chipButton("Cerrar", new Runnable() {
            @Override
            public void run() {
                if (aboutDialog != null) aboutDialog.dismiss();
            }
        }));

        TextView info = text("Bridge en 127.0.0.1:" + bridgePort,
                T_STATUS, INK_FAINT, Typeface.NORMAL);
        info.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(18);
        root.addView(info, ilp);

        aboutDialog = new android.app.Dialog(this);
        aboutDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        aboutDialog.setContentView(root);
        aboutDialog.setCanceledOnTouchOutside(true);
        android.view.Window win = aboutDialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        }
        aboutDialog.show();
        if (win != null) {
            win.setLayout(dp(440), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        portfolio.requestFocus();
    }

    /**
     * Lets the user choose which launcher is the home screen: HyRemote or the
     * stock one from the projector. Only a root process can set the home app,
     * so the change is made through the bridge; without a root bridge the
     * system screen for the home app is opened instead.
     */
    private void showLauncherPicker() {
        final boolean hyRemoteIsHome = isHomeApp(getPackageName());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(24), dp(28), dp(22));
        root.setBackground(rounded(0xFF0B1220, dp(22), dp(1), 0x1FFFFFFF));

        TextView title = text("Launcher predeterminado", 19, INK, Typeface.BOLD);
        root.addView(title);

        TextView sub = text("Elige la pantalla de inicio del proyector",
                T_STATUS, INK_DIM, Typeface.NORMAL);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        root.addView(sub, subLp);

        root.addView(launcherRow("HyRemote", "Este launcher", hyRemoteIsHome,
                new Runnable() {
                    @Override
                    public void run() {
                        setHomeApp("hyremote");
                    }
                }), rowParams());

        root.addView(launcherRow("Original del proyector", "Launcher de fábrica",
                !hyRemoteIsHome, new Runnable() {
                    @Override
                    public void run() {
                        setHomeApp("original");
                    }
                }), rowParams());

        android.app.Dialog dialog = new android.app.Dialog(this);
        launcherDialog = dialog;
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        }
        dialog.show();
        if (w != null) w.setLayout(dp(430), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        return lp;
    }

    /** A row of the launcher picker: name, state and the focus ring. */
    private View launcherRow(String name, String state, final boolean current,
                             final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackground(rounded(0x14FFFFFF, dp(14), dp(1)));
        row.setFocusable(true);
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                view.setBackground(hasFocus
                        ? rounded(0x26FFFFFF, dp(14), dp(2), 0xFF4C8DFF)
                        : rounded(0x14FFFFFF, dp(14), dp(1)));
                view.setElevation(hasFocus ? dp(8) : 0);
            }
        });

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        row.addView(info, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView n = text(name, 15, INK, Typeface.BOLD);
        info.addView(n);

        TextView s = text(current ? state + " · en uso" : state, 11,
                current ? 0xFF34D399 : 0xFF5E6A78, Typeface.NORMAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        info.addView(s, slp);

        TextView mark = text(current ? "●" : "○", 16,
                current ? 0xFF34D399 : 0xFF5E6A78, Typeface.NORMAL);
        row.addView(mark);
        return row;
    }

    /** True when the given package is the system home app right now. */
    private boolean isHomeApp(String pkg) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri =
                    getPackageManager().resolveActivity(home, 0);
            return ri != null && ri.activityInfo != null
                    && pkg.equals(ri.activityInfo.packageName);
        } catch (Exception e) {
            return false;
        }
    }

    /** Applies the home app choice through the projector's own adbd. */
    private void setHomeApp(final String which) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = HomeSwitcher.set(MainActivity.this, which);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (ok) {
                            toast(which.equals("hyremote")
                                    ? "Listo: HyRemote ya es el launcher"
                                    : "Listo: launcher original restaurado");
                            if (launcherDialog != null) launcherDialog.dismiss();
                        } else {
                            try {
                                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
                                toast("Elige el launcher en Ajustes");
                            } catch (Exception e) {
                                toast("No se pudo cambiar el launcher");
                            }
                        }
                    }
                });
            }
        }, "set-home").start();
    }

    /**
     * Vuelve del dialogo del sistema: comprueba si HyRemote quedo como
     * launcher y lo dice. Sin esto, el usuario se queda sin saber si
     * funciono.
     */
    private void checkHomeResult() {
        if (!pendingHomeCheck) return;
        pendingHomeCheck = false;

        if (isHomeApp(getPackageName())) {
            HomeSwitcher.markClaimed(this);
            toast("Listo: HyRemote ya es el launcher");
            return;
        }

        // The user may have accepted the system dialog but the firmware
        // still resolves HOME to the factory launcher. If the local ADB is
        // available, write the choice directly before giving up.
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = HomeSwitcher.set(MainActivity.this, "hyremote");
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(ok
                                ? "Listo: HyRemote ya es el launcher"
                                : "HyRemote todavía no es el launcher. Inténtalo en Ajustes.");
                    }
                });
            }
        }, "home-result").start();
    }

    /**
     * The system role picker: the user confirms with one click.
     *
     * Returns true only if a dialog was really shown. If the role is already
     * ours there is nothing to ask, and the caller has to be told so: saying
     * "true" without showing anything used to leave the user with no news.
     */
    private boolean askForHomeRole() {
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false;
            // Context.ROLE_MANAGER is not public API; the service name is "role".
            android.app.role.RoleManager rm =
                    (android.app.role.RoleManager) getSystemService("role");
            if (rm == null) return false;
            if (!rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) return false;
            if (rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                // Ya lo tenemos, pero el HOME puede seguir yendo al de
                // fabrica: en ese caso hay que ir a Ajustes.
                return isHomeApp(getPackageName());
            }
            Intent i = rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w("HyRemote", "role request: " + e);
            return false;
        }
    }

    private void openPortfolio() {
        try {
            Intent web = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse(PORTFOLIO_URL));
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(web);
        } catch (Exception e) {
            toast("No se pudo abrir el portfolio");
        }
    }

    private Button chipButton(String label, final Runnable action) {
        return chipButton(label, T_LABEL, action);
    }

    private Button chipButton(String label, float sp, final Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(sp);
        b.setTextColor(INK);
        b.setAllCaps(false);
        // The platform button style carries a lot of horizontal padding and a
        // minimum width; both of them blow past the column budget.
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setSingleLine();
        b.setPadding(dp(13), 0, dp(13), 0);
        b.setBackground(rounded(SURFACE, dp(R_PILL), dp(1)));
        b.setFocusable(true);
        b.setFocusableInTouchMode(false);
        b.setStateListAnimator(null);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        applyFocus(b, dp(R_PILL));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
        lp.rightMargin = dp(5);
        b.setLayoutParams(lp);
        return b;
    }

    // ------------------------------------------------------- quick actions

    /**
     * The four things this projector is actually used for: settings, files,
     * the HDMI input and Bluetooth. Same job as the original launcher's
     * "Applications / Settings / Files / HDMI" row, plus pairing.
     */
    /**
     * The quick actions, now big tiles with icons: two columns, the ones a
     * projector is actually used for.
     */
    private View buildQuickActions() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        grid.addView(left);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rightLp.leftMargin = dp(8);
        grid.addView(right, rightLp);

        left.addView(quickTile(GlyphView.GEAR, "Ajustes", new Runnable() {
            @Override
            public void run() {
                openSettings();
            }
        }));
        left.addView(quickTile(GlyphView.SCREEN, "HDMI", new Runnable() {
            @Override
            public void run() {
                switchInput();
            }
        }));
        left.addView(quickTile(GlyphView.BLUETOOTH, "Bluetooth", new Runnable() {
            @Override
            public void run() {
                showBluetoothPanel();
            }
        }));

        right.addView(quickTile(GlyphView.FOLDER, "Archivos", new Runnable() {
            @Override
            public void run() {
                openFiles();
            }
        }));
        right.addView(quickTile(GlyphView.PROJECT, "Proyección", new Runnable() {
            @Override
            public void run() {
                openProjection();
            }
        }));
        right.addView(quickTile(GlyphView.HOME, "Inicio", new Runnable() {
            @Override
            public void run() {
                showLauncherPicker();
            }
        }));
        return grid;
    }

    /** A big tile with a hand-drawn icon and a label. */
    private View quickTile(int glyph, String label, final Runnable action) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(6), dp(10), dp(6), dp(8));
        tile.setBackground(rounded(SURFACE, dp(R_TILE), dp(1)));
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(false);
        tile.setClickable(true);
        applyFocus(tile, dp(R_TILE));
        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });

        GlyphView icon = new GlyphView(this);
        icon.setGlyph(glyph);
        icon.setColor(ACCENT);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(INK);
        name.setTextSize(10.5f);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(6);
        tile.addView(name, nlp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(88), dp(76));
        lp.bottomMargin = dp(8);
        tile.setLayoutParams(lp);
        return tile;
    }

    /**
     * The settings of the projector, the ones the stock launcher opens:
     * com.htc.htcsettings, with Networking, Bluetooth, Language, Other,
     * Projection, Audio, Date and time and About the device.
     *
     * The fallback is the vendor's quick settings, and after that the plain
     * Android TV settings.
     */
    private void openSettings() {
        if (startVendor("com.htc.htcsettings", "com.htc.activity.HomeActivity")) return;
        if (startVendor("com.droidlogic.tv.settings",
                "com.droidlogic.tv.settings.QuickSettingActivity")) return;
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Exception ignored) {
            toast("Ajustes no disponibles");
        }
    }

    /** The network settings of the projector. */
    private void openWifi() {
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return;
        } catch (Exception ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Exception e) {
            toast("Ajustes de red no disponibles");
        }
    }

    /**
     * The projector's projection settings - the real ones.
     *
     * They live in their own app, com.softwinner.tcorrection, which is what
     * the stock launcher opens: projection mode (auto), zoom in/out, auto
     * trapezoid, fast keystone correction, corner correction and the
     * horizontal calibration. That is the screen this button is for.
     *
     * The keystone entry of the vendor's "More Settings" is a dead link in
     * this firmware (the activity is not in the manifest and the app logs
     * "startKeyStoneCorrectionActivity not found!"), so it is not used here.
     */
    private void openProjection() {
        if (startVendor("com.softwinner.tcorrection",
                "com.softwinner.tcorrection.projection.ProjectionSettingActivity")) return;
        if (startVendor("com.softwinner.tcorrection",
                "com.softwinner.tcorrection.projection.ProjectionSettingActivity")) return;
        if (startVendor("com.droidlogic.tv.settings",
                "com.droidlogic.tv.settings.pqsettings.PictureModeActivity")) return;
        if (startVendor("com.droidlogic.tv.settings",
                "com.droidlogic.tv.settings.QuickSettingActivity")) return;
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Exception e) {
            toast("Ajustes de proyección no disponibles");
        }
    }

    /** Starts a screen of the manufacturer's settings app. True when it worked. */
    private boolean startVendor(String pkg, String cls) {
        try {
            Intent i = new Intent();
            i.setComponent(new android.content.ComponentName(pkg, cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w("HyRemote", "vendor screen " + cls + ": " + e);
            return false;
        }
    }

    private void openFiles() {
        // The projector ships its own file browser; fall back to whatever
        // handles documents on a different device.
        try {
            Intent i = new Intent();
            i.setComponent(new android.content.ComponentName(
                    "com.droidlogic.FileBrower", "com.droidlogic.FileBrower.FileBrower"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Exception ignored) {
        }
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("No hay explorador de archivos");
        }
    }

    /**
     * Switches the projector to the HDMI input.
     *
     * KEYCODE_TV_INPUT (178) is what the physical Source button sends on this
     * firmware, and only a root process may inject it - which is exactly what
     * the bridge is. So the launcher asks the local bridge, and falls back to
     * the vendor input app when the bridge is not running.
     */
    private void switchInput() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean sent = postKey(178);
                if (!sent) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Intent i = new Intent();
                                i.setComponent(new android.content.ComponentName(
                                        "com.droidlogic.android.tv", "com.android.tv.MainActivity"));
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(i);
                            } catch (Exception e) {
                                toast("No se pudo cambiar la entrada");
                            }
                        }
                    });
                }
            }
        }, "switch-input").start();
    }

    /** Sends a keycode through the local bridge. True when it went through. */
    private boolean postKey(int keycode) {
        return postBridge("/key", String.valueOf(keycode));
    }

    /** Posts to the local bridge. True when the bridge answered. */
    private boolean postBridge(String path, String body) {
        if (postTo(bridgePort, path, body)) return true;

        // The port may have changed while the launcher was busy (or the
        // server was still binding when the first action ran). Discover it
        // and retry once instead of failing the button.
        int port = detectBridgePort();
        if (port != 0 && port != bridgePort) {
            bridgePort = port;
            return postTo(port, path, body);
        }
        return false;
    }

    private boolean postTo(int port, String path, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + port + path).openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1500);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length());
            c.setRequestProperty("Content-Type", "text/plain");
            c.getOutputStream().write(body.getBytes("UTF-8"));
            c.getOutputStream().flush();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
            String line = r.readLine();
            r.close();
            return line != null && line.contains("\"ok\":1");
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void openBluetooth() {
        // The chip opens the pairing screen; the top bar icon is the switch.
        showBluetoothPanel();
    }

    /** The launcher's own Bluetooth panel: switch, paired devices, scan. */
    private void showBluetoothPanel() {
        try {
            if (bluetoothPanel != null && bluetoothPanel.isShowing()) {
                bluetoothPanel.dismiss();
            }
            bluetoothPanel = new BluetoothPanel(this, bridgePort);
            bluetoothPanel.show();
        } catch (Exception e) {
            openBluetoothFallback();
        }
    }

    /**
     * The top bar Bluetooth icon is a switch: on -> off, off -> on and the
     * pairing screen opens so a device can be added right away.
     */
    private void toggleBluetooth() {
        BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null) {
            toast("Este equipo no tiene Bluetooth");
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingBluetooth = BT_TOGGLE;
            requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT},
                    REQ_BLUETOOTH);
            return;
        }
        try {
            if (adapter.isEnabled()) {
                adapter.disable();
                toast("Bluetooth desactivado");
            } else {
                adapter.enable();
                toast("Bluetooth activado");
                openBluetoothFallback();
            }
        } catch (SecurityException e) {
            toast("Sin permiso de Bluetooth");
        }
    }

    private void openBluetoothFallback() {
        BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null) {
            toast("Este equipo no tiene Bluetooth");
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT},
                    REQ_BLUETOOTH);
            return;
        }
        continueBluetooth(adapter);
    }

    /**
     * The pairing screen of Android TV: "Add accessory", which lists the
     * devices around and asks to confirm the code. On this firmware the TV
     * settings app has no Bluetooth entry of its own - it hides it under
     * "Remotes & accessories" - so the activity is started directly.
     */
    private void continueBluetooth(BluetoothAdapter adapter) {
        try {
            if (!adapter.isEnabled()) adapter.enable();
        } catch (SecurityException ignored) {
        }
        try {
            Intent i = new Intent();
            i.setComponent(new android.content.ComponentName("com.android.tv.settings",
                    "com.android.tv.settings.accessories.AddAccessoryActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Exception ignored) {
        }
        try {
            Intent i = new Intent();
            i.setComponent(new android.content.ComponentName("com.android.tv.settings",
                    "com.android.tv.settings.bluetooth.AddAccessoryActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Exception ignored) {
        }
        try {
            Intent i = new Intent("android.bluetooth.devicepicker.action.LAUNCH");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Exception ignored) {
        }
        // Last resort: the vendor pairing screen. On this firmware it starts
        // without showing anything, so it is only reached if the Android TV
        // pairing screens are all missing.
        try {
            Intent i = new Intent();
            i.setComponent(new android.content.ComponentName(
                    "com.droidlogic", "com.droidlogic.btpair.BtSetupActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("No se pudo abrir Bluetooth");
        }
    }

    private BluetoothAdapter getBluetoothAdapter() {
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            return bm == null ? null : bm.getAdapter();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_BLUETOOTH) {
            return;
        }
        if (bluetoothPanel != null) {
            bluetoothPanel.onPermissionsResult();
        }
        if (grantResults.length > 0 && grantResults[0]
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (pendingBluetooth == BT_TOGGLE) {
                pendingBluetooth = BT_NONE;
                toggleBluetooth();
            } else {
                continueBluetooth(getBluetoothAdapter());
            }
        } else {
            toast("Sin permiso de Bluetooth");
        }
    }

    // ------------------------------------------------------ connect + QR

    private void updateConnectCard() {
        String ip = localIpv4();
        String url = (ip == null) ? null : "http://" + ip + ":" + bridgePort;

        if (urlView != null) {
            urlView.setText(url != null ? url : "Sin WiFi");
        }

        if (url != null && !url.equals(lastQrUrl) && qrView != null) {
            Bitmap bmp = qrBitmap(url, dp(140));
            if (bmp != null) {
                qrView.setImageBitmap(bmp);
                lastQrUrl = url;
            }
        } else if (url == null && qrView != null) {
            qrView.setImageDrawable(null);
            lastQrUrl = null;
        }
    }

    /** Draws the QR as dark modules on white, with a quiet zone. */
    private Bitmap qrBitmap(String content, int sizePx) {
        try {
            QrCode qr = QrCode.encodeText(content, QrCode.Ecc.MEDIUM);
            int modules = qr.size;
            int quiet = 3;
            int total = modules + quiet * 2;
            int scale = Math.max(2, sizePx / total);
            int dim = total * scale;
            int[] pix = new int[dim * dim];
            int dark = 0xFF05070C;
            int light = 0xFFFFFFFF;
            for (int y = 0; y < dim; y++) {
                int my = y / scale - quiet;
                for (int x = 0; x < dim; x++) {
                    int mx = x / scale - quiet;
                    pix[y * dim + x] = qr.getModule(mx, my) ? dark : light;
                }
            }
            return Bitmap.createBitmap(pix, dim, dim, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            Log.w("HyRemote", "qr failed: " + t);
            return null;
        }
    }

    /** The projector's own LAN IPv4, preferring the WiFi interface. */
    private static final long STATE_TTL_MS = 2000L;
    private String ipCache;
    private long ipCacheAt;

    private String localIpv4() {
        long now = SystemClock.elapsedRealtime();
        if (ipCacheAt != 0 && now - ipCacheAt < STATE_TTL_MS) return ipCache;
        ipCache = localIpv4Now();
        ipCacheAt = now;
        return ipCache;
    }

    /**
     * Enumerating interfaces is a syscall per interface and the ticker calls
     * this every two seconds, from the poll and from the QR card. The address
     * changes once, when WiFi associates.
     */
    private String localIpv4Now() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName();
                if (name.startsWith("rmnet") || name.startsWith("dummy")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip == null || ip.startsWith("169.254.")) continue;
                    if (name.startsWith("wlan") || name.startsWith("ap")) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    /** Finds the port the bridge actually bound by probing localhost. */
    private int detectBridgePort() {
        // The port does not move while the app runs. Trying it first turns the
        // usual case into one request instead of a scan of sixteen, which the
        // poll repeats every two seconds.
        if (bridgePort >= PORT_MIN && bridgePort <= PORT_MAX
                && "pong".equals(http("http://127.0.0.1:" + bridgePort + "/ping"))) {
            return bridgePort;
        }
        for (int p = PORT_MIN; p <= PORT_MAX; p++) {
            if (p == bridgePort) continue;
            if ("pong".equals(http("http://127.0.0.1:" + p + "/ping"))) return p;
        }
        return 0;
    }

    // --------------------------------------------------------- connectivity

    /**
     * Android only hands the SSID to apps holding the location permission.
     * It is asked once, on the first launch; if the user says no, the
     * launcher shows the IP address instead and never asks again.
     */
    private void updateConnectivity() {
        if (wifiIndicator != null) {
            wifiIndicator.setActive(isWifiOn());
        }
        if (btIndicator != null) {
            btIndicator.setActive(isBluetoothOn());
        }
        if (ssidView != null) {
            ssidView.setText(networkName());
        }
    }

    /**
     * The name of the network the projector is on.
     *
     * Android only hands the SSID to apps that hold the location permission;
     * without it the address is shown instead, which tells the same story
     * (which network are we on) and is never a privacy question.
     */
    private String networkName() {
        String ssid = null;
        try {
            android.net.wifi.WifiManager wm =
                    (android.net.wifi.WifiManager) getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                String raw = wm.getConnectionInfo().getSSID();
                if (raw != null) {
                    ssid = raw.replace("\"", "").trim();
                    if (ssid.isEmpty() || ssid.contains("unknown ssid")) ssid = null;
                }
            }
        } catch (Exception ignored) {
        }
        if (ssid != null) return ssid;
        String ip = localIpv4();
        return ip == null ? "Sin red" : ip;
    }

    private boolean isWifiOn() {
        try {
            android.net.wifi.WifiManager wm =
                    (android.net.wifi.WifiManager) getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
            return wm != null && wm.isWifiEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isBluetoothOn() {
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm == null) return false;
            BluetoothAdapter adapter = bm.getAdapter();
            return adapter != null && adapter.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    // --------------------------------------------------------- bridge poll

    private void pollBridge() {
        if (!polling.compareAndSet(false, true)) return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                // One ping in the normal case: the port we already know is
                // tried first, and the range is scanned only when it fails.
                int port = bridgePort;
                long t0 = System.currentTimeMillis();
                String pong = port == 0 ? "" : http("http://127.0.0.1:" + port + "/ping");
                long ms = System.currentTimeMillis() - t0;
                if (!"pong".equals(pong)) {
                    port = detectBridgePort();
                    if (port == 0) {
                        pong = "";
                        ms = 0;
                    } else {
                        t0 = System.currentTimeMillis();
                        pong = http("http://127.0.0.1:" + port + "/ping");
                        ms = System.currentTimeMillis() - t0;
                    }
                }

                final int foundPort = port;
                final String foundPong = pong;
                final long foundMs = ms;
                final String focus = foundPort == 0 ? ""
                        : http("http://127.0.0.1:" + foundPort + "/focus");
                final String status = "pong".equals(foundPong)
                        ? http("http://127.0.0.1:" + foundPort + "/status") : "";

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (foundPort != 0) bridgePort = foundPort;
                        applyBridgeState(foundPong, foundMs, focus, status);
                        updateConnectCard();
                    }
                });
                polling.set(false);
            }
        }, "bridge-poll").start();
    }

    private void applyBridgeState(String pong, long ms, String focus, String status) {
        // Whatever is in the foreground counts as used, even if it was opened
        // with the physical remote: that is what fills the RECIENTES row.
        if (focus != null && !focus.isEmpty()
                && !focus.equals(getPackageName())
                && !focus.equals(lastForegroundPkg)) {
            lastForegroundPkg = focus;
            markUsed(focus);
        }

        boolean up = "pong".equals(pong);
        boolean wasUp = statusDot != null && "on".equals(statusDot.getTag());

        if (statusDot != null) {
            statusDot.setTag(up ? "on" : "off");
            statusDot.setBackground(oval(up ? LIVE : WARN));
            if (up != wasUp) pulse(statusDot);
        }
        // "accessibility" means Home/Back work but D-pad does not; "basic"
        // means no key injection at all. Show that instead of a green
        // "En línea" that would lie about the buttons. The root bridge's
        // status has no mode field, which means it has full control.
        boolean partial = status != null
                && (status.contains("\"mode\":\"basic\"")
                    || status.contains("\"mode\":\"accessibility\""));
        if (statusLabel != null) {
            if (!up) {
                statusLabel.setText("Sin conexión");
            } else if (partial) {
                statusLabel.setText("En línea · control parcial");
            } else {
                statusLabel.setText("En línea · " + ms + " ms");
            }
            statusLabel.setTextColor(up && !partial ? INK : WARN);
        }
    }

    private String http(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1500);
            c.setRequestMethod("GET");
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), "UTF-8"));
            String line = r.readLine();
            r.close();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            return "";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String friendlyName(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    private void pulse(View v) {
        if (!animationsEnabled) return;
        v.setScaleX(1f);
        v.setScaleY(1f);
        ObjectAnimator sx = ObjectAnimator.ofFloat(v, View.SCALE_X, 1f, 1.9f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f, 1.9f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(sx, sy);
        set.setDuration(170);
        set.setInterpolator(new DecelerateInterpolator());
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                v.setScaleX(1f);
                v.setScaleY(1f);
            }
        });
        set.start();
    }

    // ------------------------------------------------- app list for the bridge

    private void publishAppList() {
        try {
            // Same order as the launcher grid: most recently used first, so
            // the phone's app list matches what the projector shows.
            List<ResolveInfo> all = appList;
            if (all == null) all = queryApps();

            File dir = getExternalFilesDir(null);
            if (dir == null) return;

            Map<String, Long> recency = readRecency();

            StringBuilder sb = new StringBuilder();
            Set<String> done = new HashSet<>();
            for (ResolveInfo ri : all) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                if (!done.add(pkg)) continue;

                String label = labelOf(ri);
                label = label.replace('\n', ' ').replace('\r', ' ').replace('|', '/');

                Long last = recency.get(pkg);
                sb.append(pkg).append('|').append(label).append('|')
                        .append(last == null ? 0L : last).append('|')
                        .append(iconFileName(pkg)).append('\n');

                publishIcon(ri, pkg, dir);
            }

            java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "apps.txt"));
            w.write(sb.toString());
            w.close();
        } catch (Exception e) {
            Log.w("HyRemote", "publishAppList failed: " + e);
        }
    }

    /** Publishes the list on a worker: it writes forty PNGs to the SD card. */
    private void publishAppListAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                publishAppList();
            }
        }, "publish-apps").start();
    }

    private static int use(Map<String, Integer> usage, String pkg) {
        Integer v = usage.get(pkg);
        return v == null ? 0 : v;
    }

    private Map<String, Integer> readUsageStats() {
        long now = System.currentTimeMillis();
        if (usageCache != null && now - usageCacheAt < 5L * 60 * 1000) {
            return usageCache;
        }
        Map<String, Integer> out = readUsageStatsNow();
        usageCache = out;
        usageCacheAt = now;
        return out;
    }

    /** The real query. Users stats change slowly, so one call serves a while. */
    private Map<String, Integer> readUsageStatsNow() {
        Map<String, Integer> out = new HashMap<>();
        try {
            android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager)
                            getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return out;

            long end = System.currentTimeMillis();
            long start = end - 30L * 24 * 60 * 60 * 1000;
            List<android.app.usage.UsageStats> stats = usm.queryUsageStats(
                    android.app.usage.UsageStatsManager.INTERVAL_BEST, start, end);
            if (stats == null) return out;
            for (android.app.usage.UsageStats u : stats) {
                out.put(u.getPackageName(), (int) (u.getTotalTimeInForeground() / 1000));
            }
        } catch (Exception e) {
            Log.w("HyRemote", "usage stats unavailable: " + e);
        }
        return out;
    }

    private static String iconFileName(String pkg) {
        return "icon_" + pkg.replace('.', '_') + ".png";
    }

    private void publishIcon(ResolveInfo ri, String pkg, File dir) {
        File out = new File(dir, iconFileName(pkg));
        if (out.exists() && out.length() > 0) return;
        try {
            android.graphics.drawable.Drawable d = iconOf(ri);
            if (d == null) return;
            int size = 144;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
        } catch (Exception e) {
            Log.w("HyRemote", "icon failed " + pkg + ": " + e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean readAnimationsEnabled() {
        try {
            float scale = Settings.Global.getFloat(getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return scale != 0f;
        } catch (Exception e) {
            return true;
        }
    }

    private void paintSystemBars() {
        getWindow().setStatusBarColor(GROUND);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().setNavigationBarColor(GROUND);
        }
    }

    private void hideStatusBar() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private TextView text(String s, float sp, int color, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    private GradientDrawable rounded(int fill, int radiusPx, int strokePx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(radiusPx);
        if (strokePx > 0) gd.setStroke(strokePx, HAIRLINE);
        return gd;
    }

    private GradientDrawable rounded(int fill, int radiusPx, int strokePx, int strokeColor) {
        GradientDrawable gd = rounded(fill, radiusPx, strokePx);
        if (strokePx > 0) gd.setStroke(strokePx, strokeColor);
        return gd;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        return gd;
    }

    /**
     * D-pad focus ring: accent outline, lifted surface and a 1.035 scale.
     * Every focusable view paints its own; the device theme draws nothing.
     */
    private void applyFocus(final View v, final int radiusPx) {
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                float s = hasFocus ? 1.035f : 1f;
                if (animationsEnabled) {
                    view.animate().scaleX(s).scaleY(s)
                            .setDuration(140)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                } else {
                    view.setScaleX(s);
                    view.setScaleY(s);
                }
                if (hasFocus) {
                    view.setBackground(rounded(SURFACE_HI, radiusPx, dp(2), ACCENT));
                    view.setElevation(dp(10));
                } else {
                    view.setBackground(rounded(SURFACE, radiusPx, dp(1), HAIRLINE));
                    view.setElevation(0);
                }
            }
        });
        v.setStateListAnimator(null);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}

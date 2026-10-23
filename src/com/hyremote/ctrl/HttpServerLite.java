package com.hyremote.ctrl;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The projector's HTTP remote, now hosted by the app itself.
 *
 * It used to be a shell script served by `nc`, which forced the bridge to live
 * in /data/local/tmp or /system. That made a fresh sideload useless: /data/
 * local/tmp is shell-owned and apps cannot write it, so no listener ever
 * started. This server removes that dependency entirely. It binds the same
 * port range, serves the same page from the APK assets and speaks the same
 * endpoints, so the web UI and the launcher do not need to know the difference.
 *
 * Key injection goes through LocalAdb (the projector's own adbd on 5555) and
 * falls back to the accessibility service when ADB is unavailable.
 */
public class HttpServerLite {

    public static final String TAG = "HyRemoteHttp";

    static final int PORT_MIN = 8080;
    static final int PORT_MAX = 8095;

    private final Context ctx;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running = false;
    private volatile int port = 0;
    private long startedAt = 0;

    private ExecutorService pool;

    private volatile byte[] indexCache;
    private volatile String appsCache;
    private volatile long appsMtime;
    private volatile long appsBuiltAt;

    private volatile String focusCache = "";
    private volatile long focusAt;

    public HttpServerLite(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return running;
    }

    public long uptimeMs() {
        return startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
    }

    /** Binds the first free port in 8080-8095. Throws when all are taken. */
    public synchronized void start() throws IOException {
        if (running) return;

        IOException last = null;
        for (int p = PORT_MIN; p <= PORT_MAX; p++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new java.net.InetSocketAddress(
                        InetAddress.getByName("0.0.0.0"), p), 16);
                serverSocket = s;
                port = p;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (serverSocket == null) {
            throw last == null ? new IOException("no free port") : last;
        }

        running = true;
        startedAt = System.currentTimeMillis();
        pool = Executors.newCachedThreadPool();
        Log.i(TAG, "listening on 0.0.0.0:" + port);

        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (running) {
                        try {
                            final Socket client = serverSocket.accept();
                            pool.submit(new Runnable() {
                                @Override
                                public void run() {
                                    handle(client);
                                }
                            });
                        } catch (IOException e) {
                            if (running) Log.w(TAG, "accept failed: " + e);
                        }
                    }
                } finally {
                    // If the accept loop dies unexpectedly, let the watchdog
                    // see a stopped server and bind a fresh socket.
                    running = false;
                }
            }
        }, "hyremote-http");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        serverSocket = null;
        port = 0;
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    /**
     * True when the old root bridge (installed by setup.ps1) is already
     * serving. In that case the app stays out of the way: the root bridge is
     * faster and can do package/role changes that a shell cannot.
     */
    static boolean shellBridgeUp() {
        for (int p = PORT_MIN; p <= PORT_MAX; p++) {
            String body = httpGet("http://127.0.0.1:" + p + "/status", 350);
            if (body != null && body.contains("hyremote-bridge")) return true;
        }
        return false;
    }

    private static String httpGet(String url, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("GET");
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------- requests

    private void handle(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(10000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) return;

            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";

            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0
                        && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (contentLength < 0 || contentLength > 1024 * 1024) contentLength = 0;

            byte[] bodyBytes = contentLength == 0 ? new byte[0] : readExactly(in, contentLength);
            String body = bodyBytes == null ? "" : new String(bodyBytes, StandardCharsets.UTF_8);

            respond(out, method, path, body);
        } catch (Exception e) {
            Log.w(TAG, "client error: " + e);
        }
    }

    private void respond(OutputStream out, String method, String path, String body)
            throws IOException {
        String route = path;
        String query = "";
        int q = path.indexOf('?');
        if (q >= 0) {
            route = path.substring(0, q);
            query = path.substring(q + 1);
        }

        if ("/ping".equals(route)) {
            send(out, 200, "OK", "text/plain; charset=utf-8", bytes("pong"));
        } else if ("/status".equals(route)) {
            String json = "{"
                    + "\"ok\":true,"
                    + "\"service\":\"hyremote-app\","
                    + "\"mode\":" + quote(mode()) + ","
                    + "\"accessibility\":" + (acc() != null && acc().isConnected()) + ","
                    + "\"foreground\":" + quote(focus()) + ","
                    + "\"port\":" + port + ","
                    + "\"uptime_s\":" + (uptimeMs() / 1000)
                    + "}";
            send(out, 200, "OK", "application/json; charset=utf-8", bytes(json));
        } else if ("/volume".equals(route)) {
            send(out, 200, "OK", "text/plain; charset=utf-8", bytes(volume()));
        } else if ("/apps".equals(route)) {
            send(out, 200, "OK", "text/plain; charset=utf-8", bytes(apps()));
        } else if ("/focus".equals(route)) {
            send(out, 200, "OK", "text/plain; charset=utf-8", bytes(focus()));
        } else if ("/icon".equals(route)) {
            byte[] icon = iconFromQuery(query);
            if (icon == null) {
                send(out, 404, "Not Found", "text/plain; charset=utf-8", bytes("not found"));
            } else {
                send(out, 200, "OK", "image/png", icon);
            }
        } else if ("/key".equals(route) && "POST".equalsIgnoreCase(method)) {
            int code = -1;
            try {
                code = Integer.parseInt(body.trim());
            } catch (NumberFormatException ignored) {
            }
            boolean ok = key(code);
            send(out, 200, "OK", "application/json; charset=utf-8",
                    bytes("{\"ok\":" + (ok ? 1 : 0) + "}"));
        } else if ("/text".equals(route) && "POST".equalsIgnoreCase(method)) {
            boolean ok = text(body);
            send(out, 200, "OK", "application/json; charset=utf-8",
                    bytes("{\"ok\":" + (ok ? 1 : 0) + "}"));
        } else if ("/app".equals(route) && "POST".equalsIgnoreCase(method)) {
            boolean ok = launch(body.trim());
            send(out, 200, "OK", "application/json; charset=utf-8",
                    bytes("{\"ok\":" + (ok ? 1 : 0) + "}"));
        } else if ("/tap".equals(route) && "POST".equalsIgnoreCase(method)) {
            boolean ok = tap(body);
            send(out, 200, "OK", "application/json; charset=utf-8",
                    bytes("{\"ok\":" + (ok ? 1 : 0) + "}"));
        } else if ("/swipe".equals(route) && "POST".equalsIgnoreCase(method)) {
            boolean ok = swipe(body);
            send(out, 200, "OK", "application/json; charset=utf-8",
                    bytes("{\"ok\":" + (ok ? 1 : 0) + "}"));
        } else if ("/home".equals(route) && "POST".equalsIgnoreCase(method)) {
            boolean ok = home(body.trim());
            send(out, 200, "OK", "application/json; charset=utf-8",
                    bytes("{\"ok\":" + (ok ? 1 : 0) + "}"));
        } else if ("/pkg".equals(route) && "POST".equalsIgnoreCase(method)) {
            boolean ok = packageAction(body.trim());
            send(out, 200, "OK", "application/json; charset=utf-8",
                    bytes("{\"ok\":" + (ok ? 1 : 0) + "}"));
        } else if ("/radio".equals(route) && "POST".equalsIgnoreCase(method)) {
            boolean ok = radio(body.trim());
            send(out, 200, "OK", "application/json; charset=utf-8",
                    bytes("{\"ok\":" + (ok ? 1 : 0) + "}"));
        } else if ("/".equals(route) || "/index.html".equals(route)) {
            byte[] html = index();
            if (html == null) {
                send(out, 500, "Internal Server Error", "text/plain; charset=utf-8",
                        bytes("Falta la pagina en el APK"));
            } else {
                send(out, 200, "OK", "text/html; charset=utf-8", html);
            }
        } else {
            send(out, 404, "Not Found", "application/json; charset=utf-8",
                    bytes("{\"error\":\"not found\"}"));
        }
    }

    private void send(OutputStream out, int status, String reason, String contentType,
                      byte[] payload) throws IOException {
        byte[] body = payload == null ? new byte[0] : payload;
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    // -------------------------------------------------------------- content

    private byte[] index() {
        byte[] cached = indexCache;
        if (cached != null) return cached;
        try {
            InputStream in = ctx.getAssets().open("www/index.html");
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                indexCache = out.toByteArray();
                return indexCache;
            } finally {
                in.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "cannot read www/index.html from assets", e);
            return null;
        }
    }

    private byte[] iconFromQuery(String query) {
        String name = null;
        for (String part : query.split("&")) {
            if (part.startsWith("name=")) {
                name = part.substring(5);
                break;
            }
        }
        if (name == null) return null;
        name = name.replaceAll("[^A-Za-z0-9_.\\-]", "");
        if (name.isEmpty()) return null;
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) return null;
        File f = new File(dir, name);
        if (!f.isFile()) return null;
        return readFile(f);
    }

    /**
     * "package|label|data:image/png;base64,..." per line, the same format the
     * shell bridge's buildapps.sh produced.
     */
    private String apps() {
        String cached = appsCache;
        long now = System.currentTimeMillis();
        if (cached != null && now - appsBuiltAt < 60000) return cached;

        File dir = ctx.getExternalFilesDir(null);
        if (dir != null) {
            File src = new File(dir, "apps.txt");
            if (src.isFile()) {
                long mtime = src.lastModified();
                if (cached != null && mtime == appsMtime) return cached;
                String built = appsFromPublished(dir, src);
                if (built != null) {
                    appsCache = built;
                    appsMtime = mtime;
                    appsBuiltAt = now;
                    return built;
                }
            }
        }

        String built = appsFromPackageManager();
        appsCache = built;
        appsMtime = 0;
        appsBuiltAt = now;
        return built;
    }

    private String appsFromPublished(File dir, File src) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new FileInputStream(src),
                            StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] f = line.split("\\|", -1);
                if (f.length < 2) continue;

                String pkg = f[0].trim();
                String label = f[1].trim();
                String iconName = f.length >= 4 ? f[3].trim() : "";
                if (iconName.isEmpty()) iconName = iconName(pkg);

                String dataUri = "";
                File icon = new File(dir, iconName);
                if (icon.isFile() && icon.length() > 0) {
                    byte[] png = readFile(icon);
                    if (png != null) {
                        dataUri = "data:image/png;base64,"
                                + Base64.encodeToString(png, Base64.NO_WRAP);
                    }
                }
                sb.append(pkg).append('|').append(label).append('|')
                        .append(dataUri).append('\n');
            }
            r.close();
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "appsFromPublished failed: " + e);
            return null;
        }
    }

    private String appsFromPackageManager() {
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ResolveInfo> resolved = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);

            Set<String> seen = new HashSet<>();
            List<ResolveInfo> apps = new ArrayList<>();
            for (ResolveInfo ri : resolved) {
                if (ri.activityInfo == null) continue;
                if (ctx.getPackageName().equals(ri.activityInfo.packageName)) continue;
                if (seen.add(ri.activityInfo.packageName)) apps.add(ri);
            }
            Collections.sort(apps, new Comparator<ResolveInfo>() {
                @Override
                public int compare(ResolveInfo a, ResolveInfo b) {
                    return String.valueOf(a.loadLabel(ctx.getPackageManager()))
                            .compareToIgnoreCase(
                                    String.valueOf(b.loadLabel(ctx.getPackageManager())));
                }
            });

            StringBuilder sb = new StringBuilder();
            for (ResolveInfo ri : apps) {
                String pkg = ri.activityInfo.packageName;
                String label = String.valueOf(ri.loadLabel(pm));
                String dataUri = "";
                try {
                    Drawable d = ri.loadIcon(pm);
                    if (d != null) {
                        Bitmap bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
                        Canvas c = new Canvas(bmp);
                        d.setBounds(0, 0, 96, 96);
                        d.draw(c);
                        ByteArrayOutputStream png = new ByteArrayOutputStream();
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, png);
                        dataUri = "data:image/png;base64,"
                                + Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP);
                    }
                } catch (Exception ignored) {
                }
                sb.append(pkg).append('|').append(label).append('|')
                        .append(dataUri).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "appsFromPackageManager failed: " + e);
            return "";
        }
    }

    // --------------------------------------------------------------- actions

    private String mode() {
        if (LocalAdb.available(ctx)) return "adb";
        RemoteAccessibilityService a = acc();
        if (a != null && a.isConnected()) return "accessibility";
        return "basic";
    }

    private String volume() {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return "0/50/0";
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            boolean mute = am.isStreamMute(AudioManager.STREAM_MUSIC);
            return cur + "/" + max + "/" + (mute ? 1 : 0);
        } catch (Exception e) {
            return "0/50/0";
        }
    }

    private String focus() {
        long now = System.currentTimeMillis();
        if (now - focusAt < 700) return focusCache;

        String found = "";
        if (LocalAdb.available(ctx)) {
            String out = LocalAdb.exec(ctx,
                    "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mResumedActivity' | head -4");
            found = parseFocus(out);
            if (found.isEmpty()) {
                out = LocalAdb.exec(ctx,
                        "dumpsys activity activities 2>/dev/null | grep mResumedActivity | head -2");
                found = parseFocus(out);
            }
        }
        if (found.isEmpty()) {
            RemoteAccessibilityService a = acc();
            if (a != null) {
                String pkg = a.foregroundPackage();
                if (pkg != null) found = pkg;
            }
        }

        focusCache = found;
        focusAt = now;
        return found;
    }

    private static String parseFocus(String text) {
        if (text == null || text.isEmpty()) return "";
        Matcher m = Pattern.compile("([A-Za-z0-9._]+)/[A-Za-z0-9._$]+").matcher(text);
        while (m.find()) {
            String pkg = m.group(1);
            if (pkg != null && !pkg.isEmpty()) return pkg;
        }
        return "";
    }

    private boolean key(int code) {
        if (code <= 0) return false;

        // Volume and mute do not need key injection at all.
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                if (code == 24) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_RAISE, 0);
                    return true;
                }
                if (code == 25) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_LOWER, 0);
                    return true;
                }
                if (code == 164) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_TOGGLE_MUTE, 0);
                    return true;
                }
            }
        } catch (Exception ignored) {
        }

        if (LocalAdb.available(ctx)) {
            String out = LocalAdb.exec(ctx, "input keyevent " + code);
            if (out != null && !commandFailed(out)) return true;
            LocalAdb.invalidate();
        }

        RemoteAccessibilityService a = acc();
        return a != null && a.injectKey(code);
    }

    private boolean text(String raw) {
        if (raw == null) return false;
        String value = raw.replace("%s", " ");
        if (value.trim().isEmpty()) return false;

        RemoteAccessibilityService a = acc();
        if (a != null && a.setText(value)) return true;

        if (LocalAdb.available(ctx)) {
            if (value.indexOf('\n') >= 0) value = value.replace('\n', ' ');
            String safe = value.replace("'", "'\\''").replace(" ", "%s");
            String out = LocalAdb.exec(ctx, "input text '" + safe + "'");
            if (out != null && !commandFailed(out)) return true;
            LocalAdb.invalidate();
        }
        return false;
    }

    private boolean launch(String pkg) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9._]+")) return false;

        if (LocalAdb.available(ctx)) {
            String out = LocalAdb.exec(ctx,
                    "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
            if (out != null && out.contains("Events injected")) return true;
        }

        try {
            PackageManager pm = ctx.getPackageManager();
            Intent i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) {
                Intent main = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(pkg);
                List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
                if (list == null || list.isEmpty()) return false;
                ResolveInfo ri = list.get(0);
                i = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "launch failed " + pkg + ": " + e);
            return false;
        }
    }

    private boolean tap(String body) {
        String[] f = body.trim().split(",");
        if (f.length < 2) return false;
        final int x;
        final int y;
        try {
            x = Integer.parseInt(f[0].trim());
            y = Integer.parseInt(f[1].trim());
        } catch (NumberFormatException e) {
            return false;
        }

        if (LocalAdb.available(ctx)) {
            String out = LocalAdb.exec(ctx, "input tap " + x + " " + y);
            if (out != null && !commandFailed(out)) return true;
        }
        RemoteAccessibilityService a = acc();
        return a != null && a.tap(x, y);
    }

    private boolean swipe(String body) {
        String[] f = body.trim().split(",");
        if (f.length < 4) return false;
        int x1, y1, x2, y2, duration = 300;
        try {
            x1 = Integer.parseInt(f[0].trim());
            y1 = Integer.parseInt(f[1].trim());
            x2 = Integer.parseInt(f[2].trim());
            y2 = Integer.parseInt(f[3].trim());
            if (f.length >= 5) duration = Integer.parseInt(f[4].trim());
        } catch (NumberFormatException e) {
            return false;
        }

        if (LocalAdb.available(ctx)) {
            String out = LocalAdb.exec(ctx,
                    "input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + duration);
            if (out != null && !commandFailed(out)) return true;
        }
        RemoteAccessibilityService a = acc();
        return a != null && a.swipe(x1, y1, x2, y2, duration);
    }

    private boolean home(String which) {
        if (!LocalAdb.available(ctx)) return false;
        String target;
        String pkg;
        if ("hyremote".equals(which)) {
            target = "com.hyremote.ctrl/.MainActivity";
            pkg = "com.hyremote.ctrl";
        } else if ("original".equals(which)) {
            target = "com.htc.htclauncherhighenglishd08/com.mao.launcher.activity.BarrelActivity";
            pkg = "com.htc.htclauncherhighenglishd08";
            LocalAdb.exec(ctx, "pm enable --user 0 " + pkg);
        } else {
            return false;
        }

        LocalAdb.exec(ctx, "cmd package set-home-activity " + target);
        String out = LocalAdb.exec(ctx,
                "cmd role add-role-holder --user 0 android.app.role.HOME " + pkg);
        boolean ok = out != null && !commandFailed(out);

        // Only disable the vendor launcher after HyRemote actually owns the
        // HOME role: doing it the other way around can leave the projector
        // with no home app if the role command is rejected.
        if (ok && "hyremote".equals(which)) {
            LocalAdb.exec(ctx, "pm disable-user --user 0 com.htc.htclauncherhighenglishd08");
        }
        return ok;
    }

    private boolean packageAction(String body) {
        String action;
        String pkg;
        if (body.startsWith("disable ")) {
            action = "disable-user --user 0";
            pkg = body.substring(8).trim();
        } else if (body.startsWith("enable ")) {
            action = "enable --user 0";
            pkg = body.substring(7).trim();
        } else {
            return false;
        }
        if (!pkg.matches("[A-Za-z0-9._]+")) return false;
        if (!LocalAdb.available(ctx)) return false;
        String out = LocalAdb.exec(ctx, "pm " + action + " " + pkg);
        return out != null && !commandFailed(out);
    }

    private boolean radio(String mode) {
        if (!"on".equals(mode) && !"off".equals(mode)) return false;
        if (!LocalAdb.available(ctx)) return false;
        String out = LocalAdb.exec(ctx,
                "cmd bluetooth_manager " + ("on".equals(mode) ? "enable" : "disable"));
        return out != null && !commandFailed(out);
    }

    private static boolean commandFailed(String out) {
        String s = out.toLowerCase();
        return s.contains("permission denied")
                || s.contains("securityexception")
                || s.contains("error:")
                || s.contains("exception")
                || s.contains("not found")
                || s.contains("unknown command");
    }

    // ---------------------------------------------------------------- utils

    private static RemoteAccessibilityService acc() {
        return RemoteAccessibilityService.instance;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String quote(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String iconName(String pkg) {
        return "icon_" + pkg.replace('.', '_') + ".png";
    }

    private static byte[] readFile(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') out.write(c);
        }
        if (c < 0 && out.size() == 0) return null;
        return out.toString("UTF-8");
    }

    private static byte[] readExactly(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) return null;
            off += n;
        }
        return buf;
    }
}

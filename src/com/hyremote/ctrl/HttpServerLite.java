package com.hyremote.ctrl;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal dependency-free HTTP server for Phase 0.
 * Endpoints:
 *   GET  /ping    -> "pong"
 *   GET  /status  -> JSON with uptime / accessibility enabled
 *   POST /key     -> body = android keycode number, injects it
 */
public class HttpServerLite {

    public static final String TAG = "HyRemote";

    private final int port;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running = false;
    private long startedAt = 0;

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final RemoteAccessibilityService acc;

    public HttpServerLite(int port, RemoteAccessibilityService acc) {
        this.port = port;
        this.acc = acc;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port, 8, InetAddress.getByName("0.0.0.0"));
        running = true;
        startedAt = System.currentTimeMillis();
        Log.i(TAG, "HTTP server listening on port " + port);
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
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
            }
        }, "http-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    public boolean isRunning() {
        return running;
    }

    private void handle(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = in.readLine();
            if (requestLine == null) return;

            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";

            // Drain headers + body so the client does not see a broken pipe.
            String line;
            int contentLength = 0;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            String body = "";
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int read = in.read(buf);
                body = read > 0 ? new String(buf, 0, read) : "";
            }

            respond(s, method, path, body);
        } catch (IOException e) {
            Log.w(TAG, "client error: " + e);
        }
    }

    private static String quote(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void respond(Socket s, String method, String path, String body) throws IOException {
        String json;
        int status = 200;

        if (path.startsWith("/ping")) {
            json = "pong";
        } else if (path.startsWith("/status")) {
            long uptime = (System.currentTimeMillis() - startedAt) / 1000;
            json = "{"
                    + "\"ok\":true,"
                    + "\"uptime_s\":" + uptime + ","
                    + "\"accessibility\":" + (acc != null && acc.isConnected()) + ","
                    + "\"foreground\":" + quote(acc == null ? null : acc.foregroundPackage()) + ","
                    + "\"reflect\":" + (acc != null && acc.isReflectionUsable()) + ","
                    + "\"api\":" + android.os.Build.VERSION.SDK_INT
                    + "}";
        } else if (path.startsWith("/key") && "POST".equalsIgnoreCase(method)) {
            int keycode = -1;
            try {
                keycode = Integer.parseInt(body.trim());
            } catch (NumberFormatException ignored) {
            }
            boolean ok = acc != null && acc.injectKey(keycode);
            json = "{\"sent\":" + ok + ",\"keycode\":" + keycode + "}";
        } else if (path.startsWith("/tap") && "POST".equalsIgnoreCase(method)) {
            // Body format: "x,y"
            int x = -1, y = -1;
            try {
                String[] parts = body.trim().split(",");
                x = Integer.parseInt(parts[0].trim());
                y = Integer.parseInt(parts[1].trim());
            } catch (Exception ignored) {
            }
            boolean ok = acc != null && acc.tap(x, y);
            json = "{\"tapped\":" + ok + ",\"x\":" + x + ",\"y\":" + y + "}";
        } else {
            status = 404;
            json = "{\"error\":\"not found\"}";
        }

        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 " + status + " "
                + (status == 200 ? "OK" : "Not Found") + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: application/json; charset=utf-8\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Length: " + payload.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(payload);
        out.flush();
    }
}
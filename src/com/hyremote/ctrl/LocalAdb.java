package com.hyremote.ctrl;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Talks to the projector's own adbd over the loopback interface.
 *
 * This is what makes the APK self-contained. An ordinary app cannot run
 * `input keyevent` on Android 13: the command exists in /system/bin, but the
 * kernel refuses INJECT_EVENTS to an app uid. The shell uid, however, can, and
 * the HY320 exposes adbd on TCP 5555. So instead of installing a root bridge
 * from a PC, the app opens an ADB connection to 127.0.0.1:5555 and asks the
 * device's own shell to press the key.
 *
 * That is not a toy workaround: it is the same path `adb shell input ...`
 * uses, with the same permissions, and it needs no root and no /system write.
 * It only needs network debugging to be on, which is how these projectors
 * ship. When it is off, the app falls back to the accessibility service for
 * Home/Back/Recents and to direct Android APIs for volume and app launch.
 *
 * The ADB wire protocol is implemented here instead of bundling the adb
 * binary: an APK cannot execute a helper from its data directory on Android 10
 * and later, and /data/local/tmp is not writable by apps either. The subset
 * needed for this is small - CNXN, AUTH, OPEN, OKAY, WRTE, CLSE.
 */
final class LocalAdb {

    private static final String TAG = "HyRemoteAdb";

    static final int PORT = 5555;

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_PUBLIC_KEY = 3;

    private static final int MAX_DATA = 4096;

    /** Normal command: a revoked key must not wedge the phone for minutes. */
    private static final int ACTION_TIMEOUT_MS = 12000;

    /** First-run probe: gives up before the authorization prompt can block. */
    private static final int QUICK_TIMEOUT_MS = 4000;

    /** Warm-up: this is the one that waits for the user to accept the prompt. */
    private static final int WARMUP_TIMEOUT_MS = 120000;

    private static volatile long lastCheckAt = 0;
    private static volatile boolean lastCheck = false;

    private static final AtomicBoolean warming = new AtomicBoolean(false);
    private static final AtomicBoolean probing = new AtomicBoolean(false);
    private static volatile boolean warmUpDone = false;

    /**
     * One authenticated connection is kept open and reused for every shell
     * command. Opening a fresh connection per key press means a fresh
     * "Allow debugging?" round trip on firmware that does not persist the
     * key immediately, which makes the D-pad unusable. The ADB protocol
     * multiplexes services, so a single CNXN connection can run any number
     * of `shell:` streams one after another.
     */
    private static volatile Socket conn;
    private static InputStream connIn;
    private static OutputStream connOut;
    private static int nextLocalId = 1;

    private LocalAdb() {
    }

    /**
     * True when a shell can be reached on the local adbd.
     *
     * Never blocks: an HTTP request used to wait behind the two-minute
     * "Allow debugging?" window, so /status and every button looked dead
     * until the user walked over to the projector. Now the probe runs in the
     * background and the caller gets the last known answer.
     */
    static boolean available(Context ctx) {
        if (conn != null) {
            lastCheck = true;
            lastCheckAt = System.currentTimeMillis();
            return true;
        }
        // Do not race the warm-up with a 4 s probe: that probe can consume
        // the only authorization prompt before the 120 s warm-up gets it.
        if (warming.get() || !warmUpDone) return lastCheck;

        long now = System.currentTimeMillis();
        if (now - lastCheckAt < 30000) return lastCheck;

        if (probing.compareAndSet(false, true)) {
            final Context app = ctx.getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String out = execQuick(app, "id -u");
                        boolean ok = out != null && out.trim().matches("\\d+");
                        lastCheck = ok;
                        lastCheckAt = System.currentTimeMillis();
                        if (!ok) Log.w(TAG, "local adbd not reachable on 127.0.0.1:" + PORT);
                    } finally {
                        probing.set(false);
                    }
                }
            }, "adb-probe").start();
        }
        return lastCheck;
    }

    /**
     * Opens the long authorization window once, at service start. This is what
     * makes the "Allow debugging?" dialog appear while the user is looking at
     * the projector instead of on the first key press from the phone.
     */
    static void warmUp(Context ctx) {
        if (!warming.compareAndSet(false, true)) return;
        final Context app = ctx.getApplicationContext();
        try {
            String out = exec(app, "id -u", WARMUP_TIMEOUT_MS);
            boolean ok = out != null && out.trim().matches("\\d+");
            lastCheck = ok;
            lastCheckAt = System.currentTimeMillis();
            if (!ok) Log.w(TAG, "local adbd not reachable on 127.0.0.1:" + PORT);
            else Log.i(TAG, "local adbd ready");
        } finally {
            warming.set(false);
            warmUpDone = true;
        }
    }

    /** True while the authorization window is open. */
    static boolean isWarmingUp() {
        return warming.get();
    }

    /** Forces the next available() call to probe again. */
    static synchronized void invalidate() {
        lastCheckAt = 0;
        lastCheck = false;
        closeConnection();
    }

    /**
     * Runs one command through the device's own shell and returns its output,
     * or null when ADB is not reachable. Callers must treat null as "no ADB".
     */
    static String exec(Context ctx, String command) {
        return exec(ctx, command, ACTION_TIMEOUT_MS);
    }

    /** A short probe that cannot sit on the authorization prompt for long. */
    static String execQuick(Context ctx, String command) {
        return exec(ctx, command, QUICK_TIMEOUT_MS);
    }

    private static synchronized String exec(Context ctx, String command,
                                             int handshakeTimeoutMs) {
        try {
            if (conn == null) {
                conn = connect(ctx, handshakeTimeoutMs);
                connIn = conn.getInputStream();
                connOut = conn.getOutputStream();
                Log.i(TAG, "adb connection kept open for later commands");
            }

            final OutputStream out = connOut;
            final InputStream in = connIn;

            int localId = nextLocalId++;
            if (nextLocalId <= 0) nextLocalId = 1;

            byte[] service = ("shell:" + command + "\0").getBytes("UTF-8");
            Log.d(TAG, "exec open " + localId + ": " + command);
            writePacket(out, A_OPEN, localId, 0, service);

            ByteArrayOutputStream data = new ByteArrayOutputStream();
            int remoteId = 0;
            boolean closed = false;
            long deadline = System.currentTimeMillis() + 10000;

            while (System.currentTimeMillis() < deadline) {
                Packet p = readPacket(in);
                if (p == null) {
                    Log.d(TAG, "exec eof, bytes=" + data.size());
                    closeConnection();
                    return null;
                }
                Log.d(TAG, "exec pkt " + cmdName(p.cmd) + " a0=" + p.arg0
                        + " a1=" + p.arg1 + " len=" + (p.data == null ? 0 : p.data.length));

                if (p.cmd == A_OKAY) {
                    if (p.arg1 == localId) remoteId = p.arg0;
                } else if (p.cmd == A_WRTE) {
                    // A WRTE from the device carries the device's id in arg0
                    // and the host's id in arg1. Checking arg0 against
                    // localId drops every output chunk, so the shell looks
                    // empty and the app reports "no ADB".
                    if ((p.arg1 == localId && (remoteId == 0 || p.arg0 == remoteId))
                            || (p.arg0 == localId && p.arg1 == remoteId)) {
                        if (p.data != null) data.write(p.data);
                        writePacket(out, A_OKAY, remoteId, localId, null);
                    }
                } else if (p.cmd == A_CLSE) {
                    // The device may report CLSE with the host id in arg0
                    // and the device id in arg1, and a late CLSE for the
                    // previous stream can arrive after the next OPEN. Match
                    // either id against this stream's local id so a stale
                    // close cannot end the new command early.
                    if ((p.arg1 == localId && (remoteId == 0 || p.arg0 == remoteId))
                            || (p.arg0 == localId && p.arg1 == remoteId)) {
                        closed = true;
                        break;
                    }
                } else if (p.cmd == A_AUTH) {
                    // A new authorization prompt while a shell is open: the
                    // old key is no longer trusted.
                    closeConnection();
                    lastCheck = false;
                    lastCheckAt = 0;
                    return null;
                }
            }

            if (!closed) {
                Log.w(TAG, "adb shell timed out: " + command);
                closeConnection();
                return null;
            }

            lastCheck = true;
            lastCheckAt = System.currentTimeMillis();
            return data.toString("UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "adb exec failed: " + e);
            closeConnection();
            lastCheck = false;
            lastCheckAt = 0;
            return null;
        }
    }

    // ------------------------------------------------------------ handshake

    private static Socket connect(Context ctx, int handshakeTimeoutMs) throws Exception {
        List<String> hosts = candidateHosts();
        Exception last = null;

        for (String host : hosts) {
            Socket socket = new Socket();
            boolean connected = false;
            try {
                socket.connect(new InetSocketAddress(host, PORT), 800);
                connected = true;
                // The first connection shows Android's "Allow debugging?"
                // dialog. The warm-up gives the user time to pick up the
                // physical remote; normal commands use a short window so a
                // revoked key does not freeze every request.
                socket.setSoTimeout(handshakeTimeoutMs);
                socket.setTcpNoDelay(true);
                handshake(ctx, socket);
                // Shell commands should not hang for two minutes.
                socket.setSoTimeout(15000);
                return socket;
            } catch (Exception e) {
                close(socket);
                if (connected) {
                    // The loopback socket reached adbd but the handshake
                    // failed (usually because the auth prompt is pending).
                    // Trying the WiFi address would open a second prompt for
                    // the same key, so fail now.
                    throw e;
                }
                last = e;
            }
        }
        throw last != null ? last : new IOException("no adb host");
    }

    /**
     * Loopback first, then the projector's own WiFi address. adbd normally
     * listens on 0.0.0.0, but a firmware that binds only to wlan0 still works
     * because the app can reach its own address.
     */
    private static List<String> candidateHosts() {
        List<String> hosts = new ArrayList<>();
        hosts.add("127.0.0.1");
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()
                            && a.isSiteLocalAddress()) {
                        String h = a.getHostAddress();
                        if (!hosts.contains(h)) hosts.add(h);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return hosts;
    }

    private static void handshake(Context ctx, Socket socket) throws Exception {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        byte[] host = "host::\0".getBytes("UTF-8");
        writePacket(out, A_CNXN, 0x01000000, MAX_DATA, host);

        boolean sentSignature = false;
        boolean sentPublicKey = false;

        for (int i = 0; i < 8; i++) {
            Packet p = readPacket(in);
            if (p == null) throw new IOException("adbd closed the handshake");
            Log.d(TAG, "handshake pkt " + cmdName(p.cmd) + " a0=" + p.arg0
                    + " a1=" + p.arg1 + " len=" + (p.data == null ? 0 : p.data.length));

            if (p.cmd == A_CNXN) return;

            if (p.cmd == A_AUTH) {
                if (p.arg0 == AUTH_TOKEN) {
                    if (sentPublicKey) {
                        // adbd accepted the public key (the user tapped
                        // Allow) and sends a fresh token so the host proves
                        // it owns the private key. Signing this token is what
                        // finally produces A_CNXN; without it the handshake
                        // times out just after the prompt is accepted.
                        byte[] sig = signToken(ctx, p.data);
                        writePacket(out, A_AUTH, AUTH_SIGNATURE, 0, sig);
                        Log.i(TAG, "adb key approved; token signed");
                    } else if (!sentSignature) {
                        byte[] sig = signToken(ctx, p.data);
                        writePacket(out, A_AUTH, AUTH_SIGNATURE, 0, sig);
                        sentSignature = true;
                    } else {
                        writePacket(out, A_AUTH, AUTH_PUBLIC_KEY, 0, publicKeyPayload(ctx));
                        sentPublicKey = true;
                        Log.i(TAG, "adb key sent; accept the debugging prompt on the projector");
                    }
                } else {
                    // A device-side AUTH that is not a token is a request
                    // for our public key in some adbd versions.
                    if (!sentPublicKey) {
                        writePacket(out, A_AUTH, AUTH_PUBLIC_KEY, 0, publicKeyPayload(ctx));
                        sentPublicKey = true;
                    } else {
                        throw new IOException("adb auth failed");
                    }
                }
            } else if (p.cmd == A_CLSE) {
                throw new IOException("adbd closed the connection");
            }
        }
        throw new IOException("adb handshake did not finish");
    }

    private static byte[] signToken(Context ctx, byte[] token) throws Exception {
        Signature sig = Signature.getInstance("SHA1withRSA");
        sig.initSign(keys(ctx).privateKey);
        sig.update(token);
        return sig.sign();
    }

    private static byte[] publicKeyPayload(Context ctx) throws Exception {
        String b64 = Base64.encodeToString(keys(ctx).publicKey, Base64.NO_WRAP);
        return (b64 + " hyremote@android\0").getBytes("UTF-8");
    }

    // ----------------------------------------------------------------- keys

    private static final class Keys {
        final PrivateKey privateKey;
        final byte[] publicKey;

        Keys(PrivateKey privateKey, byte[] publicKey) {
            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }
    }

    private static volatile Keys keys;

    private static Keys keys(Context ctx) throws Exception {
        Keys cached = keys;
        if (cached != null) return cached;

        synchronized (LocalAdb.class) {
            if (keys != null) return keys;

            File dir = new File(ctx.getFilesDir(), "adb");
            if (!dir.exists()) dir.mkdirs();
            File privFile = new File(dir, "adbkey.pk8");
            File pubFile = new File(dir, "adbkey.pub");

            if (!privFile.isFile() || !pubFile.isFile()) {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048);
                KeyPair kp = kpg.generateKeyPair();
                writeFile(privFile, kp.getPrivate().getEncoded());
                writeFile(pubFile, encodeAdbPublicKey(kp.getPublic()));
                Log.i(TAG, "adb key generated");
            }

            PrivateKey priv = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(readFile(privFile)));
            keys = new Keys(priv, readFile(pubFile));
            return keys;
        }
    }

    /**
     * Android's custom RSA public key format, which is what a modern adbd
     * decodes. It is not just modulus + exponent:
     *
     *   uint32 modulus_size_words (64 for a 2048-bit key)
     *   uint32 n0inv = -1 / n[0] mod 2^32
     *   uint8  modulus[256], little-endian
     *   uint8  rr[256] = 2^4096 mod n, little-endian
     *   uint32 exponent
     *
     * Writing the shorter "length + modulus + length + exponent" form makes
     * the device reject the key with "Invalid key len", so the Montgomery
     * parameters are calculated here.
     */
    private static byte[] encodeAdbPublicKey(PublicKey key) {
        RSAPublicKey rsa = (RSAPublicKey) key;
        BigInteger n = rsa.getModulus();
        BigInteger e = rsa.getPublicExponent();
        int words = 2048 / 32;

        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.modInverse(r32));
        BigInteger r = BigInteger.ONE.shiftLeft(2048);
        BigInteger rr = r.multiply(r).mod(n);

        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(words);
        bb.putInt(n0inv.intValue());
        bb.put(littleEndian(n, 256));
        bb.put(littleEndian(rr, 256));
        bb.putInt(e.intValue());
        return bb.array();
    }

    private static byte[] littleEndian(BigInteger value, int size) {
        byte[] be = value.toByteArray();
        byte[] out = new byte[size];
        int copy = Math.min(be.length, size);
        for (int i = 0; i < copy; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        FileOutputStream os = new FileOutputStream(f);
        try {
            os.write(data);
        } finally {
            os.close();
        }
        f.setReadable(false, false);
        f.setReadable(true, true);
        f.setWritable(false, false);
        f.setWritable(true, true);
    }

    private static byte[] readFile(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------- packets

    private static final class Packet {
        final int cmd;
        final int arg0;
        final int arg1;
        final byte[] data;

        Packet(int cmd, int arg0, int arg1, byte[] data) {
            this.cmd = cmd;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }

    private static void writePacket(OutputStream out, int cmd, int arg0, int arg1,
                                    byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int checksum = 0;
        for (int i = 0; i < len; i++) checksum += data[i] & 0xff;

        ByteBuffer bb = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(cmd);
        bb.putInt(arg0);
        bb.putInt(arg1);
        bb.putInt(len);
        bb.putInt(checksum);
        bb.putInt(cmd ^ 0xffffffff);
        if (len > 0) bb.put(data);

        out.write(bb.array());
        out.flush();
    }

    private static String cmdName(int cmd) {
        switch (cmd) {
            case A_CNXN: return "CNXN";
            case A_AUTH: return "AUTH";
            case A_OPEN: return "OPEN";
            case A_OKAY: return "OKAY";
            case A_CLSE: return "CLSE";
            case A_WRTE: return "WRTE";
            default: return "0x" + Integer.toHexString(cmd);
        }
    }

    private static Packet readPacket(InputStream in) throws IOException {
        byte[] head = readExactly(in, 24);
        if (head == null) return null;

        ByteBuffer bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = bb.getInt();
        int arg0 = bb.getInt();
        int arg1 = bb.getInt();
        int len = bb.getInt();
        bb.getInt(); // checksum: adbd accepts v2 packets with 0 and v1 with sum
        int magic = bb.getInt();

        if ((cmd ^ 0xffffffff) != magic) throw new IOException("bad adb packet magic");
        if (len < 0 || len > 1024 * 1024) throw new IOException("bad adb packet size " + len);

        byte[] data = len == 0 ? new byte[0] : readExactly(in, len);
        if (data == null) throw new IOException("truncated adb packet");
        return new Packet(cmd, arg0, arg1, data);
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

    private static void closeConnection() {
        Socket s = conn;
        conn = null;
        connIn = null;
        connOut = null;
        close(s);
    }

    private static void close(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}

package com.hyremote.ctrl;

import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The projector's Bluetooth panel.
 *
 * The stock TV settings bury Bluetooth under "Remotes & accessories" and only
 * offer a one-way screen, so the launcher ships its own: the radio switch, the
 * paired devices with connect / forget actions, and a scan for the ones
 * nearby. Pairing is handed to the system dialog, which is the only thing
 * that can confirm the code.
 */
public class BluetoothPanel extends Dialog {

    private static final String TAG = "HyRemoteBt";

    /** Los extras del emparejamiento no son API publica: van por su nombre. */
    private static final String EXTRA_ADDRESS = "android.bluetooth.device.extra.ADDRESS";
    private static final String EXTRA_TYPE = "android.bluetooth.device.extra.TYPE";
    private static final String VARIANT_ASK = "PAIRING_VARIANT_ASK";
    private static final String VARIANT_PIN = "PAIRING_VARIANT_PIN";
    private static final int REQ_PERMS = 7;

    private final Activity activity;
    private final BluetoothAdapter adapter;
    private final int bridgePort;
    private final Map<String, String> found = new LinkedHashMap<>(); // address -> name
    /** Los dispositivos vistos en el escaneo: todavia no estan emparejados. */
    private final Map<String, BluetoothDevice> foundDevices = new HashMap<>();
    private final List<BluetoothDevice> bonded = new ArrayList<>();

    // Live references, so the panel can refresh itself without rebuilding the
    // list (rebuilding would throw away the focus while the user navigates).
    private final Map<String, TextView> stateViews = new HashMap<>();
    private final Map<String, Button> connectButtons = new HashMap<>();
    private final Map<String, Button> forgetButtons = new HashMap<>();

    private LinearLayout listBox;
    private TextView stateView;
    private TextView subtitleView;
    private Toggle switchView;
    private boolean scanning = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshStates();
            handler.postDelayed(this, 800);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null) return;
                found.put(d.getAddress(), deviceName(d));
                foundDevices.put(d.getAddress(), d);
                render();
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                scanning = false;
                render();
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                loadBonded();
                render();
            }
        }
    };

    public BluetoothPanel(Activity activity, int bridgePort) {
        super(activity);
        this.activity = activity;
        this.bridgePort = bridgePort;
        this.adapter = findAdapter();
    }

    private BluetoothAdapter findAdapter() {
        try {
            BluetoothManager bm = (BluetoothManager)
                    activity.getSystemService(Context.BLUETOOTH_SERVICE);
            return bm == null ? null : bm.getAdapter();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(24), dp(28), dp(20));
        root.setBackground(rounded(0xFF0B1220, dp(22), dp(1), 0x1FFFFFFF));

        // ---- header
        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        TextView title = text("Bluetooth", 20, 0xFFF4F7FA, Typeface.BOLD);
        head.addView(title);

        View sp = new View(activity);
        head.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        switchView = new Toggle(activity);
        switchView.setContentDescription("Interruptor de Bluetooth");
        switchView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleRadio();
            }
        });
        head.addView(switchView, new LinearLayout.LayoutParams(dp(60), dp(34)));

        stateView = text("", 12, 0xFF9BA8B6, Typeface.NORMAL);
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        stateLp.topMargin = dp(2);
        root.addView(stateView, stateLp);

        subtitleView = text("", 11.5f, 0xFF5E6A78, Typeface.NORMAL);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        root.addView(subtitleView, subLp);

        // ---- device list
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        listBox = new LinearLayout(activity);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listBox);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        listLp.topMargin = dp(12);
        root.addView(scroll, listLp);

        // ---- footer
        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams footLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        footLp.topMargin = dp(10);
        root.addView(footer, footLp);

        footer.addView(button("Buscar", new Runnable() {
            @Override
            public void run() {
                scan();
            }
        }));
        footer.addView(button("Ajustes BT", new Runnable() {
            @Override
            public void run() {
                openVendorBluetooth();
            }
        }));
        footer.addView(button("Cerrar", new Runnable() {
            @Override
            public void run() {
                dismiss();
            }
        }));

        setContentView(root);

        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
        }
        setOnDismissListener(new OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface dialog) {
                handler.removeCallbacks(ticker);
                stopScan();
                try {
                    activity.unregisterReceiver(receiver);
                } catch (Exception ignored) {
                }
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        Window w = getWindow();
        if (w != null) w.setLayout(dp(490), ViewGroup.LayoutParams.WRAP_CONTENT);
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            activity.registerReceiver(receiver, filter);
        } catch (Exception ignored) {
        }
        ensurePermissions();
        handler.post(ticker);
    }

    /** Called by the activity once the runtime permissions come back. */
    public void onPermissionsResult() {
        loadBonded();
        render();
    }

    // ------------------------------------------------------------- behaviour

    private void ensurePermissions() {
        try {
            boolean connect = activity.checkSelfPermission(
                    android.Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
            boolean scan = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    || activity.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
            if (connect && scan) {
                loadBonded();
                render();
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activity.requestPermissions(new String[]{
                        android.Manifest.permission.BLUETOOTH_CONNECT,
                        android.Manifest.permission.BLUETOOTH_SCAN}, REQ_PERMS);
            } else {
                activity.requestPermissions(new String[]{
                        android.Manifest.permission.BLUETOOTH_CONNECT}, REQ_PERMS);
            }
        } catch (Exception e) {
            Log.w(TAG, "permisos: " + e);
        }
    }

    /**
     * Flips the radio.
     *
     * Android 13 turned BluetoothAdapter.enable()/disable() into no-ops for
     * apps, so the switch asks the local bridge - which runs as root - to do
     * it. Without a root bridge the radio can still be turned on (the system
     * asks for confirmation), but not turned off from here.
     */
    private void toggleRadio() {
        if (adapter == null) return;
        final boolean on = isRadioOn();
        // The bridge call is a socket: it cannot run on the main thread.
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean done = radioViaBridge(on ? "off" : "on");
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (done) {
                            refreshStates();
                            return;
                        }
                        if (on) {
                            // Android 13 has no way to turn the radio off from
                            // an app, and the bridge was not reachable.
                            toast("No se pudo apagar el Bluetooth aquí. "
                                    + "Prueba desde los ajustes del proyector.");
                            return;
                        }
                        try {
                            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            activity.startActivity(i);
                        } catch (Exception e) {
                            Log.w(TAG, "enable: " + e);
                            toast("No se pudo activar el Bluetooth");
                        }
                    }
                });
            }
        }, "bt-toggle").start();
    }

    private boolean isRadioOn() {
        try {
            return adapter != null && adapter.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    /** Asks the local root bridge to switch the radio. True when it did. */
    private boolean radioViaBridge(String onOrOff) {
        if (bridgePort <= 0) {
            Log.w(TAG, "radio: sin puerto de bridge");
            return false;
        }
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(
                    "http://127.0.0.1:" + bridgePort + "/radio").openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(2000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(onOrOff.length());
            c.setRequestProperty("Content-Type", "text/plain");
            c.getOutputStream().write(onOrOff.getBytes("UTF-8"));
            c.getOutputStream().flush();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
            String line = r.readLine();
            r.close();
            Log.i(TAG, "radio " + onOrOff + " -> " + line);
            return line != null && line.contains("\"ok\":1");
        } catch (Exception e) {
            Log.w(TAG, "radio " + onOrOff + ": " + e);
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void scan() {
        if (adapter == null) return;
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            found.clear();
            foundDevices.clear();
            scanning = adapter.startDiscovery();
            if (!scanning) {
                toast("No se pudo buscar");
            }
            render();
        } catch (SecurityException e) {
            Log.w(TAG, "scan: " + e);
        }
    }

    private void stopScan() {
        try {
            if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery();
        } catch (Exception ignored) {
        }
    }

    private void loadBonded() {
        bonded.clear();
        try {
            if (adapter == null) return;
            java.util.Set<BluetoothDevice> set = adapter.getBondedDevices();
            if (set != null) bonded.addAll(set);
        } catch (SecurityException e) {
            Log.w(TAG, "bonded: " + e);
        }
    }

    /** Rebuilds the list of devices. */
    private void render() {
        if (listBox == null) return;
        stateViews.clear();
        connectButtons.clear();
        forgetButtons.clear();
        listBox.removeAllViews();

        if (bonded.isEmpty()) {
            listBox.addView(plain("Sin dispositivos emparejados"),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            listBox.addView(plain("EMPAREADOS"), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (BluetoothDevice d : bonded) {
                listBox.addView(bondedRow(d));
            }
        }

        if (!found.isEmpty()) {
            listBox.addView(plain("CERCA"), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (Map.Entry<String, String> e : found.entrySet()) {
                listBox.addView(foundRow(e.getKey(), e.getValue()));
            }
        }
        refreshStates();
    }

    /** Updates the switch, the labels and the state of every row in place. */
    private void refreshStates() {
        boolean on = false;
        try {
            on = adapter != null && adapter.isEnabled();
        } catch (Exception ignored) {
        }
        if (switchView != null) switchView.setOn(on);
        if (stateView != null) {
            stateView.setText(on ? "Activado" : "Desactivado");
            stateView.setTextColor(on ? 0xFF34D399 : 0xFF9BA8B6);
        }
        if (subtitleView != null) {
            subtitleView.setText(scanning
                    ? "Buscando dispositivos cercanos…"
                    : (found.isEmpty()
                    ? "Toca Buscar para ver los dispositivos cercanos"
                    : found.size() + " dispositivo(s) cerca"));
        }

        for (BluetoothDevice d : bonded) {
            String address = d.getAddress();
            boolean connected = isConnected(d);
            TextView tv = stateViews.get(address);
            if (tv != null) {
                tv.setText(connected ? "Conectado" : "Emparejado");
                tv.setTextColor(connected ? 0xFF34D399 : 0xFF5E6A78);
            }
            Button b = connectButtons.get(address);
            if (b != null) {
                b.setText(connected ? "Desconectar" : "Conectar");
                b.setTextColor(connected ? 0xFFFF6B6B : 0xFF4C8DFF);
            }
        }
    }

    /** A paired device: name, state and connect / forget actions. */
    private View bondedRow(final BluetoothDevice d) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(9), dp(10), dp(9));
        row.setBackground(rounded(0x14FFFFFF, dp(14), dp(1)));

        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        row.addView(info, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView n = text(deviceName(d), 15, 0xFFF4F7FA, Typeface.BOLD);
        n.setMaxLines(1);
        n.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(n);

        TextView s = text("", 11, 0xFF5E6A78, Typeface.NORMAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        info.addView(s, slp);
        stateViews.put(d.getAddress(), s);

        Button connect = smallButton("Conectar", new Runnable() {
            @Override
            public void run() {
                BluetoothDevice dev = findDevice(d.getAddress());
                if (dev == null) return;
                if (isConnected(dev)) {
                    if (disconnect(dev)) toast("Desconectado");
                    else toast("No se pudo desconectar");
                } else if (!connectDevice(dev)) {
                    // Si el perfil no sirve, al menos se intenta el
                    // emparejamiento normal del sistema.
                    pair(dev);
                }
            }
        });
        connectButtons.put(d.getAddress(), connect);
        row.addView(connect);

        Button forget = smallButton("Olvidar", new Runnable() {
            @Override
            public void run() {
                BluetoothDevice dev = findDevice(d.getAddress());
                if (dev == null) return;
                if (unpair(dev)) {
                    toast("Dispositivo olvidado");
                    loadBonded();
                    render();
                } else {
                    toast("No se pudo olvidar");
                }
            }
        });
        forgetButtons.put(d.getAddress(), forget);
        row.addView(forget);

        return row;
    }

    /** A device found by the scan: name and a pair action. */
    private View foundRow(final String address, String name) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(9), dp(10), dp(9));
        row.setBackground(rounded(0x14FFFFFF, dp(14), dp(1)));

        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        row.addView(info, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView n = text(name, 15, 0xFFF4F7FA, Typeface.BOLD);
        n.setMaxLines(1);
        n.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(n);

        TextView s = text(address, 11, 0xFF5E6A78, Typeface.NORMAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        info.addView(s, slp);

        row.addView(smallButton("Emparejar", new Runnable() {
            @Override
            public void run() {
                BluetoothDevice dev = findDevice(address);
                if (dev == null) {
                    toast("No se pudo localizar el dispositivo. Busca otra vez.");
                    return;
                }
                pair(dev);
            }
        }));
        return row;
    }

    /**
     * Busca un dispositivo por su direccion, primero entre los que acaba el
     * escaneo (que todavia no estan emparejados) y luego entre los
     * emparejados. Antes solo miraba estos ultimos, y por eso pulsar
     * 'Emparejar' en un dispositivo nuevo no hacia absolutamente nada.
     */
    private BluetoothDevice findDevice(String address) {
        BluetoothDevice d = foundDevices.get(address);
        if (d != null) return d;
        try {
            if (adapter == null) return null;
            java.util.Set<BluetoothDevice> set = adapter.getBondedDevices();
            if (set != null) {
                for (BluetoothDevice b : set) {
                    if (b.getAddress().equals(address)) return b;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** True when any profile of the device is connected. */
    private boolean isConnected(BluetoothDevice d) {
        int[] profiles = {BluetoothProfile.A2DP, BluetoothProfile.HEADSET,
                BluetoothProfile.HEALTH, BluetoothProfile.GATT};
        try {
            BluetoothManager bm = (BluetoothManager)
                    activity.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm == null) return false;
            for (int p : profiles) {
                java.util.List<BluetoothDevice> connected;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    connected = bm.getConnectedDevices(p);
                } else {
                    connected = connectedThroughProfile(p);
                }
                if (connected == null) continue;
                for (BluetoothDevice c : connected) {
                    if (c.getAddress().equals(d.getAddress())) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Connects a device through the profiles it can have, and says why it
     * failed if it failed. The exception of the Bluetooth stack is the only
     * source of truth here: "no conecto" sin mas no sirve de nada.
     */
    private boolean connectDevice(BluetoothDevice d) {
        int[] profiles = {BluetoothProfile.A2DP, BluetoothProfile.HEADSET,
                BluetoothProfile.HEALTH};
        String reason = null;

        for (int type : profiles) {
            try {
                Method getProfile = BluetoothAdapter.class.getMethod("getProfile", int.class);
                Object profile = getProfile.invoke(adapter, type);
                if (profile == null) continue;
                Method m = profile.getClass().getMethod("connect", BluetoothDevice.class);
                Object res = m.invoke(profile, d);
                if (Boolean.TRUE.equals(res)) {
                    toast("Conectando con " + deviceName(d) + "…");
                    return true;
                }
                reason = "el dispositivo no acepta la conexion";
            } catch (java.lang.reflect.InvocationTargetException e) {
                reason = cause(e);
            } catch (Throwable t) {
                reason = t.getMessage();
            }
        }

        String extra = "";
        if (reason != null && reason.toLowerCase().contains("not support")) {
            extra = ". Los parlantes nuevos usan LE Audio y el chip Bluetooth "
                    + "de este proyector es antiguo: no los ve";
        }
        toast("No se pudo conectar" + (reason == null ? "" : ": " + reason) + extra);
        return false;
    }

    /** El mensaje de la excepcion real, no el de la envoltura. */
    private String cause(java.lang.reflect.InvocationTargetException e) {
        Throwable t = e.getCause();
        if (t == null) return e.getMessage();
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    /** The devices of a profile, on the versions without getConnectedDevices. */
    private java.util.List<BluetoothDevice> connectedThroughProfile(int type) {
        try {
            Method getProfile = BluetoothAdapter.class.getMethod("getProfile", int.class);
            Object profile = getProfile.invoke(adapter, type);
            if (profile == null) return null;
            Method m = profile.getClass().getMethod("getConnectedDevices");
            Object res = m.invoke(profile);
            return res instanceof java.util.List
                    ? (java.util.List<BluetoothDevice>) res : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Disconnects a device. Android has no public API for it, so the profile
     * proxies are asked through reflection - the same thing every Bluetooth
     * app on the device does.
     */
    private boolean disconnect(BluetoothDevice d) {
        int[] profiles = {BluetoothProfile.A2DP, BluetoothProfile.HEADSET,
                BluetoothProfile.HEALTH};
        for (int type : profiles) {
            try {
                Method getProfile = BluetoothAdapter.class.getMethod("getProfile", int.class);
                Object profile = getProfile.invoke(adapter, type);
                if (profile == null) continue;
                Method m = profile.getClass().getMethod("disconnect", BluetoothDevice.class);
                Object res = m.invoke(profile, d);
                if (Boolean.TRUE.equals(res)) return true;
            } catch (Throwable t) {
                // This profile does not know the device: try the next one.
            }
        }
        return false;
    }

    /**
     * Reconnects a paired device through its profile. Like the disconnect,
     * Android keeps the API for itself, so the profile proxies are asked
     * through reflection.
     */
    /** Forgets a paired device (removes the bond). */
    private boolean unpair(BluetoothDevice d) {
        try {
            Method m = d.getClass().getMethod("removeBond");
            Object res = m.invoke(d);
            return Boolean.TRUE.equals(res);
        } catch (Throwable t) {
            Log.w(TAG, "removeBond: " + t);
            return false;
        }
    }

    /**
     * Empareja un dispositivo.
     *
     * El dialogo de emparejamiento de Android TV exige un extra de tipo: sin
     * el se niega a abrirse ('Incorrect pairing type received, not showing
     * any dialog') y el usuario ve que no pasa nada. El extra no es API
     * publica, asi que va por su nombre.
     *
     * Se prueban las dos variantes (ASK para los parlantes modernos, PIN para
     * los que muestran un numero) y, si el dialogo no se puede lanzar, se
     * intenta createBond().
     */
    private void pair(BluetoothDevice d) {
        for (String variant : new String[]{VARIANT_ASK, VARIANT_PIN}) {
            try {
                Intent i = new Intent(BluetoothDevice.ACTION_PAIRING_REQUEST);
                i.putExtra(EXTRA_ADDRESS, d.getAddress());
                i.putExtra(BluetoothDevice.EXTRA_NAME, deviceName(d));
                i.putExtra(EXTRA_TYPE, variant);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        try {
            if (d.createBond()) {
                toast("Emparejando con " + deviceName(d) + "…");
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "createBond: " + e);
        }
        try {
            Intent i = new Intent();
            i.setComponent(new android.content.ComponentName("com.android.tv.settings",
                    "com.android.tv.settings.accessories.AddAccessoryActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Exception e) {
            toast("No se pudo emparejar");
        }
    }

    /**
     * The manufacturer's own Bluetooth screen. On this firmware it has its
     * own pairing flow, and it works for devices that the Android TV pairing
     * screen does not list.
     */
    private void openVendorBluetooth() {
        String[][] targets = {
                {"com.htc.htcsettings", "com.htc.activity.BluetoothSettingActivity"},
                {"com.android.tv.settings",
                        "com.android.tv.settings.accessories.AddAccessoryActivity"},
        };
        for (String[] t : targets) {
            try {
                Intent i = new Intent();
                i.setComponent(new android.content.ComponentName(t[0], t[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        toast("No hay ajustes de Bluetooth");
    }

    private String deviceName(BluetoothDevice d) {
        try {
            String n = d.getName();
            if (n != null && !n.trim().isEmpty()) return n;
        } catch (SecurityException ignored) {
        }
        return d.getAddress();
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(activity, msg,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // ----------------------------------------------------------------- views

    private TextView plain(String s) {
        TextView t = text(s, 11, 0xFF5E6A78, Typeface.BOLD);
        t.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView text(String s, float sp, int color, int style) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    private View button(String label, final Runnable action) {
        Button b = new Button(activity);
        b.setText(label);
        b.setTextSize(12);
        b.setTextColor(0xFFF4F7FA);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setSingleLine();
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setBackground(rounded(0x14FFFFFF, dp(999), dp(1)));
        b.setFocusable(true);
        b.setStateListAnimator(null);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                view.setBackground(hasFocus
                        ? rounded(0x26FFFFFF, dp(999), dp(2), 0xFF4C8DFF)
                        : rounded(0x14FFFFFF, dp(999), dp(1)));
                view.setElevation(hasFocus ? dp(8) : 0);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    /** A compact pill for the per-device actions. */
    private Button smallButton(String label, final Runnable action) {
        Button b = new Button(activity);
        b.setText(label);
        b.setTextSize(11);
        b.setTextColor(0xFF4C8DFF);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setSingleLine();
        b.setPadding(dp(11), 0, dp(11), 0);
        b.setBackground(rounded(0x14FFFFFF, dp(999), dp(1)));
        b.setFocusable(true);
        b.setStateListAnimator(null);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                view.setBackground(hasFocus
                        ? rounded(0x2E4C8DFF, dp(999), dp(2), 0xFF4C8DFF)
                        : rounded(0x14FFFFFF, dp(999), dp(1)));
                view.setElevation(hasFocus ? dp(8) : 0);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable rounded(int fill, int radiusPx, int strokePx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(radiusPx);
        if (strokePx > 0) gd.setStroke(strokePx, 0x1FFFFFFF);
        return gd;
    }

    private GradientDrawable rounded(int fill, int radiusPx, int strokePx, int strokeColor) {
        GradientDrawable gd = rounded(fill, radiusPx, strokePx);
        if (strokePx > 0) gd.setStroke(strokePx, strokeColor);
        return gd;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                activity.getResources().getDisplayMetrics());
    }

    /**
     * The radio switch: a pill with a knob that slides.
     *
     * The focus ring is drawn in white and outside the track, because the
     * track itself is blue when the radio is on - an accent ring on an accent
     * track would be invisible.
     */
    private class Toggle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean on = false;

        Toggle(Context c) {
            super(c);
            setFocusable(true);
            setClickable(true);
            setStateListAnimator(null);
            setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean hasFocus) {
                    invalidate();
                }
            });
        }

        void setOn(boolean on) {
            if (this.on == on) return;
            this.on = on;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float r = h / 2f;

            // Track
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(on ? 0xFF4C8DFF : 0x26FFFFFF);
            canvas.drawRoundRect(0, 0, w, h, r, r, paint);

            // Knob
            paint.setColor(hasFocus() ? 0xFFFFFFFF : (on ? 0xFFFFFFFF : 0xFF9BA8B6));
            float knob = r * 0.74f;
            float cx = on ? w - r : r;
            canvas.drawCircle(cx, h / 2f, knob, paint);

            // Focus ring, drawn inside the bounds so it is never clipped
            if (hasFocus()) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(0xFFFFFFFF);
                float pad = dp(1.5f);
                canvas.drawRoundRect(pad, pad, w - pad, h - pad, r, r, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }
}

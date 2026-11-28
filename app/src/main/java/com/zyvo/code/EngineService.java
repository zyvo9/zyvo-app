package com.zyvo.code;

import com.termux.R;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import com.zyvo.code.adb.AdbPairClient;
import com.zyvo.code.adb.AdbShellController;
import com.zyvo.code.adb.PairState;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the zyvo engine process alive and supervised, AND owns the ADB
 * pairing flow: the single "Zyvo Code" notification CONVERTS to the pairing
 * status (inline code field + Stop) while pairing runs and converts back to
 * the engine status once paired - never two notifications (user decision).
 */
public final class EngineService extends Service implements EngineManager.Listener {

    private static final String TAG = "ZyvoEngineService";
    public static final String CHANNEL_ID = ZyvoNotif.CHANNEL_ID;
    public static final int NOTIF_ID = ZyvoNotif.ID;

    public static final String ACTION_ADB_START = "com.zyvo.code.ADB_START";
    public static final String ACTION_ADB_CODE = ZyvoNotif.ACTION_ADB_CODE;
    public static final String ACTION_ADB_STOP = ZyvoNotif.ACTION_ADB_STOP;

    private EngineManager engine;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            try {
                if (!engine.isReady()) engine.ensureStarted();
            } catch (Throwable t) {
                Log.w(TAG, "watchdog", t);
            }
            handler.postDelayed(this, 30_000L);
        }
    };

    // -------------------------------------------------- adb pairing state
    private static volatile boolean pairing;
    /** Pairing port just resolved from the live mDNS service, -1 when none. */
    private static volatile int livePairPort = -1;
    private static volatile long livePairPortAt = 0;

    private NsdManager nsd;
    private volatile boolean discovering;
    private final AtomicBoolean resolvingPair = new AtomicBoolean(false);
    private final AtomicBoolean resolvingConnect = new AtomicBoolean(false);
    private int discoveryRetries;

    @Override
    public void onCreate() {
        super.onCreate();
        engine = EngineManager.get(this);
        ZyvoNotif.ensureChannel(this);
        startForegroundCompat(ZyvoNotif.build(this));
        engine.addListener(this);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zyvo:engine");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        engine.ensureStarted();
        handler.postDelayed(watchdog, 20_000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        engine.ensureStarted();
        handleAdbIntent(intent);
        return START_STICKY;
    }

    // ------------------------------------------------------------ adb pairing

    private void handleAdbIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (ACTION_ADB_START.equals(action)) {
            startAdbPairing();
        } else if (ACTION_ADB_CODE.equals(action)) {
            String code = intent.getStringExtra(ZyvoNotif.KEY_CODE);
            if (code != null) code = code.trim().replace(" ", "");
            if (code == null || code.isEmpty()) {
                Bundle results = RemoteInput.getResultsFromIntent(intent);
                if (results != null && results.getCharSequence(ZyvoNotif.KEY_CODE) != null) {
                    code = results.getCharSequence(ZyvoNotif.KEY_CODE)
                            .toString().trim().replace(" ", "");
                }
            }
            if (code != null && !code.isEmpty() && !pairing) pairFlow(code);
        } else if (ACTION_ADB_STOP.equals(action)) {
            stopAdbPairing();
        }
    }

    /** The drawer's ADB Connect tap: discovery runs, the ONE notification
     *  converts to pairing mode with the inline code field. */
    private void startAdbPairing() {
        if (pairing) return;
        boolean detected = livePairPort > 0
                && System.currentTimeMillis() - livePairPortAt < 90_000;
        // the inline code field appears only when a port was actually detected
        ZyvoNotif.setAdbStatus(this, detected
                ? "Pairing screen detected — port " + livePairPort + " — just type the code"
                : "Searching… open Wireless debugging > 'Pair device with pairing code'",
                detected);
        startDiscovery();
    }

    private void stopAdbPairing() {
        stopDiscovery();
        livePairPort = -1;
        // null converts the notification back to the engine status
        ZyvoNotif.setAdbStatus(this, null, false);
    }

    private void startDiscovery() {
        if (discovering) return;
        try {
            nsd = (NsdManager) getSystemService(NSD_SERVICE);
            discovering = true;
            // NSD requires the FULL type ("._tcp") or discovery never starts
            nsd.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, pairListener);
            // connect type too: seeds the port cache so post-pair connect is instant
            nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, connectListener);
        } catch (Throwable t) {
            discovering = false;
            scheduleDiscoveryRetry();
        }
    }

    private void scheduleDiscoveryRetry() {
        if (discoveryRetries++ > 5 || !pairingActive()) return;
        handler.postDelayed(this::startDiscovery, 2000);
    }

    private void stopDiscovery() {
        if (!discovering) return;
        discovering = false;
        try {
            nsd.stopServiceDiscovery(pairListener);
        } catch (Throwable ignored) {
        }
        try {
            nsd.stopServiceDiscovery(connectListener);
        } catch (Throwable ignored) {
        }
    }

    /** Kept ALIVE on purpose: a rotated pairing dialog advertises a NEW
     *  service and the listener picks it up within milliseconds. */
    private final NsdManager.DiscoveryListener pairListener = new NsdManager.DiscoveryListener() {
        @Override public void onDiscoveryStarted(String type) { discoveryRetries = 0; }
        @Override public void onStartDiscoveryFailed(String type, int error) {
            discovering = false;
            scheduleDiscoveryRetry();
        }
        @Override public void onDiscoveryStopped(String type) { }
        @Override public void onStopDiscoveryFailed(String type, int error) { }
        @Override public void onServiceLost(NsdServiceInfo info) { livePairPort = -1; }
        @Override public void onServiceFound(NsdServiceInfo info) {
            // the port only arrives after resolve - found alone usually has 0
            if (!resolvingPair.compareAndSet(false, true)) return;
            try {
                nsd.resolveService(info, pairResolveListener);
            } catch (Throwable t) {
                resolvingPair.set(false);
            }
        }
    };

    private final NsdManager.ResolveListener pairResolveListener = new NsdManager.ResolveListener() {
        @Override public void onResolveFailed(NsdServiceInfo info, int error) {
            resolvingPair.set(false);
        }
        @Override public void onServiceResolved(NsdServiceInfo info) {
            resolvingPair.set(false);
            int p = info.getPort();
            if (p <= 0 || !isPortAvailable(p)) return;
            livePairPort = p;
            livePairPortAt = System.currentTimeMillis();
            if (!pairing) {
                // detection moment: NOW the notification grows the code field
                ZyvoNotif.setAdbStatus(EngineService.this,
                        "Pairing screen detected — port " + p + " — just type the code",
                        true);
            }
        }
    };

    private final NsdManager.DiscoveryListener connectListener = new NsdManager.DiscoveryListener() {
        @Override public void onDiscoveryStarted(String type) { }
        @Override public void onStartDiscoveryFailed(String type, int error) { }
        @Override public void onDiscoveryStopped(String type) { }
        @Override public void onStopDiscoveryFailed(String type, int error) { }
        @Override public void onServiceLost(NsdServiceInfo info) { }
        @Override public void onServiceFound(NsdServiceInfo info) {
            if (!resolvingConnect.compareAndSet(false, true)) return;
            try {
                nsd.resolveService(info, connectResolveListener);
            } catch (Throwable t) {
                resolvingConnect.set(false);
            }
        }
    };

    private final NsdManager.ResolveListener connectResolveListener = new NsdManager.ResolveListener() {
        @Override public void onResolveFailed(NsdServiceInfo info, int error) {
            resolvingConnect.set(false);
        }
        @Override public void onServiceResolved(NsdServiceInfo info) {
            resolvingConnect.set(false);
            int p = info.getPort();
            if (p <= 0 || !isPortAvailable(p)) return;
            // seed the same cache AdbShellController's fast path reads first
            AdbShellController.noteConnectPort(EngineService.this, p);
        }
    };

    /** true = the port is really listening (bind refused), false = stale. */
    private static boolean isPortAvailable(int port) {
        try (ServerSocket ss = new ServerSocket()) {
            ss.bind(new InetSocketAddress("127.0.0.1", port), 1);
            return false;
        } catch (Throwable e) {
            return true;
        }
    }

    /** ONE handshake against the discovered port - Shizuku style. Success
     *  connects; failure is a clean message the user can retry. */
    private void pairFlow(String code) {
        pairing = true;
        final String c = code;
        new Thread(() -> {
            try {
                int port = livePairPort;
                if (port <= 0) {
                    ZyvoNotif.setAdbStatus(this, "No pairing port detected — reopen"
                            + " 'Pair device with pairing code' and try again", false);
                    return;
                }
                ZyvoNotif.setAdbStatus(this, "Pairing… port " + port, true);
                String host = AdbShellController.pairHostFor(port);
                if (host == null) host = "127.0.0.1";
                AdbPairClient.Result r = AdbPairClient.pair(host, port, c);
                if (r != null && r.ok) {
                    PairState.markPaired(this);
                    ZyvoNotif.setAdbStatus(this, "Paired ✓ — connecting…", true);
                    boolean up = AdbShellController.ensureConnected();
                    stopDiscovery();
                    // paired = auto-convert back to the default notification
                    ZyvoNotif.setAdbStatus(this, null, false);
                    UiUtil.toast(this, up
                            ? "ADB connected ✓ — the AI shell can control the phone"
                            : "Paired ✓ — keep Wireless debugging ON, tap ADB Connect again");
                } else {
                    // the old port is dead - the code field hides until the
                    // reloaded pairing screen advertises a fresh one
                    ZyvoNotif.setAdbStatus(this, "Pair failed — reload the pairing"
                            + " screen (new code) and try again", false);
                }
            } catch (Throwable t) {
                ZyvoNotif.setAdbStatus(this, "Pair failed: "
                        + (t.getMessage() != null ? t.getMessage() : t.toString()), false);
            } finally {
                pairing = false;
            }
        }).start();
    }

    /** True while the engine service lives — the notification owner. */
    private boolean pairingActive() {
        return true;
    }

    // ----------------------------------------------------------- engine core

    private Notification buildNotification(String text) {
        ZyvoNotif.setText(this, text);
        return ZyvoNotif.build(this);
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void updateNotification(String text) {
        // during ADB pairing the notification shows pairing content instead
        if (ZyvoNotif.adbStatusOrNull() != null) {
            ZyvoNotif.refresh(this);
            return;
        }
        ZyvoNotif.setText(this, text);
    }

    @Override
    public void onEngineState(EngineManager.State state, String detail) {
        String text;
        switch (state) {
            case READY:
                text = "Agent ready — online";
                break;
            case STARTING:
                text = "Starting… " + (detail == null ? "" : detail);
                break;
            case ERROR:
                text = "Engine stopped: " + (detail == null ? "unknown" : detail);
                break;
            default:
                text = "Engine stopped";
                break;
        }
        updateNotification(text);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(watchdog);
        if (engine != null) engine.removeListener(this);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopDiscovery();
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

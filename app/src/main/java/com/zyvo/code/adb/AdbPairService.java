package com.zyvo.code.adb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import androidx.core.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * Shizuku-style wireless-debugging pairing — fully automatic.
 *
 * Ported from Shizuku's open-source pairing flow (Apache-2.0,
 * moe.shizuku.manager.adb.AdbMdns / AdbPairingService): mDNS discovery runs
 * CONTINUOUSLY while the notification is up, every found service is RESOLVED
 * (the port comes from the resolve — onServiceFound alone usually carries 0),
 * a stale port is filtered by a loopback bind test, and the moment the system
 * pairing dialog opens the notification switches to "type the code" with the
 * port already known — so submitting the code fires ONE handshake, ~1s.
 * If the fast path fails (stale port, ROM quirk) the flow falls back to the
 * parallel port scan and retries with the SAME code while the dialog stays
 * open (the port rotates, the code does not).
 */
public class AdbPairService extends Service {
    private static final String TAG = "AdbPairService";
    public static final String CHANNEL_ID = "adb_pair";
    private static final int NOTIF_ID = 1102;
    public static final String KEY_CODE = "adb_code";

    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_CODE = "code";

    private static volatile boolean pairing;

    // ---- live discovery state (read by AdbPairActivity) ----
    /** Pairing port just resolved from the live mDNS service, -1 when none. */
    public static volatile int livePairPort = -1;
    public static volatile long livePairPortAt = 0;
    /** Last status line shown in the notification (the activity mirrors it). */
    public static volatile String liveStatus = "";
    /** Manual port typed in the activity (-1 = trust discovery only). */
    public static volatile int manualPort = -1;

    // ---- continuous discovery (Shizuku AdbMdns pattern) ----
    private NsdManager nsd;
    private volatile boolean discovering;

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, AdbPairService.class).setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, AdbPairService.class));
        } catch (Throwable ignored) {
        }
    }

    /** The activity routes "Pair & connect" through the service so BOTH entry
     *  points share one pairing brain (discovered port first, scan fallback). */
    public static void requestPair(Context c, String code, int manual) {
        manualPort = manual;
        if (pairing) return;
        try {
            Intent i = new Intent(c, AdbPairService.class)
                    .setAction(ACTION_CODE)
                    .putExtra(KEY_CODE, code == null ? "" : code);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    /** Re-posts the status notification (pairing progress / result). */
    public static void update(Context c, String status) {
        liveStatus = status == null ? "" : status;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && isRunning) {
                nm.notify(NOTIF_ID, build(c, status));
            }
        } catch (Throwable ignored) {
        }
    }

    static volatile boolean isRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            // Shizuku uses a HIGH channel so the pairing notification pops up
            // instead of hiding silently in the shade
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "ADB Connect", NotificationManager.IMPORTANCE_HIGH);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            isRunning = false;
            stopDiscovery();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        isRunning = true;
        Notification n = build(this, statusText(this));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
        // Shizuku trick: discovery runs from notification start, so the port is
        // ALREADY known when the user opens the system pairing dialog.
        startDiscovery();

        // the pairing code: explicit extra from the activity, or the inline
        // RemoteInput typed straight on the notification
        if (intent != null && ACTION_CODE.equals(intent.getAction())) {
            String code = intent.getStringExtra(KEY_CODE);
            if (code != null) code = code.trim().replace(" ", "");
            if (code == null || code.isEmpty()) {
                Bundle results = RemoteInput.getResultsFromIntent(intent);
                if (results != null && results.getCharSequence(KEY_CODE) != null) {
                    code = results.getCharSequence(KEY_CODE).toString().trim().replace(" ", "");
                }
            }
            if (code != null && !code.isEmpty() && !pairing) {
                // the pairing service stays advertised while the dialog is open,
                // so a freshly-read code may arrive long after detection — keep
                // the discovered port valid for two minutes
                int port = livePairPort > 0
                        && System.currentTimeMillis() - livePairPortAt < 120_000 ? livePairPort : -1;
                pairFlow(code, port);
            }
        }
        return START_STICKY;
    }

    private String statusText(Context c) {
        if (pairing) return "Pairing… keep the pairing screen open";
        if (PairState.isPaired(c)) {
            return AdbShellController.isConnected()
                    ? "Connected ✓ — shell ready"
                    : "Paired — open Wireless debugging, then type the code again to reconnect";
        }
        int p = livePairPort;
        if (p > 0 && System.currentTimeMillis() - livePairPortAt < 90_000) {
            return "Pairing screen detected — port " + p + " — just type the code";
        }
        return "Searching… open Wireless debugging > 'Pair device with pairing code'";
    }

    // ------------------------------------------------- continuous discovery

    private void startDiscovery() {
        if (discovering) return;
        try {
            nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
            discovering = true;
            // NSD requires the FULL type ("._tcp") — the bare "_adb-tls-pairing"
            // never starts discovery on any ROM, which is why detection was dead
            nsd.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Throwable t) {
            discovering = false;
            scheduleDiscoveryRetry();
        }
        // Shizuku parity: the CONNECT service is advertised whenever wireless
        // debugging is ON — resolving it here seeds AdbShellController's port
        // cache so post-pair connect is a single handshake instead of a scan
        try {
            nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, connectListener);
        } catch (Throwable ignored) {
        }
    }

    private final android.os.Handler retryHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private int discoveryRetries;

    /** Some ROMs refuse the first discovery attempt — retry a few times. */
    private void scheduleDiscoveryRetry() {
        if (discoveryRetries++ > 5 || !isRunning) return;
        retryHandler.postDelayed(this::startDiscovery, 2000);
    }

    private void stopDiscovery() {
        if (!discovering) return;
        discovering = false;
        try {
            nsd.stopServiceDiscovery(discoveryListener);
        } catch (Throwable ignored) {
        }
        try {
            nsd.stopServiceDiscovery(connectListener);
        } catch (Throwable ignored) {
        }
    }

    /** NsdManager allows one resolve at a time — found events can outrun it. */
    private final java.util.concurrent.atomic.AtomicBoolean resolving =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Kept ALIVE on purpose: a rotated pairing dialog advertises a NEW service
     *  and this listener picks it up within milliseconds. */
    private final NsdManager.DiscoveryListener discoveryListener = new NsdManager.DiscoveryListener() {
        @Override public void onDiscoveryStarted(String type) { discoveryRetries = 0; }
        @Override public void onStartDiscoveryFailed(String type, int error) {
            discovering = false;
            scheduleDiscoveryRetry();
        }
        @Override public void onDiscoveryStopped(String type) { }
        @Override public void onStopDiscoveryFailed(String type, int error) { }
        @Override public void onServiceLost(NsdServiceInfo info) {
            livePairPort = -1;
        }
        @Override public void onServiceFound(NsdServiceInfo info) {
            // the port only arrives after resolve — found alone usually has 0
            if (!resolving.compareAndSet(false, true)) return;
            try {
                nsd.resolveService(info, resolveListener);
            } catch (Throwable t) {
                resolving.set(false);
            }
        }
    };

    private final NsdManager.ResolveListener resolveListener = new NsdManager.ResolveListener() {
        @Override public void onResolveFailed(NsdServiceInfo info, int error) {
            resolving.set(false);
        }
        @Override public void onServiceResolved(NsdServiceInfo info) {
            resolving.set(false);
            int p = info.getPort();
            if (p <= 0) return;
            // Shizuku's stale-resolve guard: bind a socket to test that
            // something really listens there right now
            if (!isPortAvailable(p)) return;
            livePairPort = p;
            livePairPortAt = System.currentTimeMillis();
            update(AdbPairService.this,
                    "Pairing screen detected — port " + p + " — just type the code");
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

    // ---- continuous connect discovery (Shizuku AdbMdns, second type) ----
    private final java.util.concurrent.atomic.AtomicBoolean resolvingConnect =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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
            AdbShellController.noteConnectPort(AdbPairService.this, p);
        }
    };

    // ------------------------------------------------------------- pairing

    /** ONE handshake against the discovered port - Shizuku style. The port is
     *  known before the code arrives (continuous discovery), so this is a
     *  single pair attempt: success connects, failure is a clean message the
     *  user can retry. No port scans, no candidate pools, no retry loops. */
    private void pairFlow(String code, int preferredPort) {
        pairing = true;
        final String c = code;
        new Thread(() -> {
            try {
                int port = preferredPort > 0 ? preferredPort : manualPort;
                if (port <= 0) {
                    update(this, "No pairing port detected — reopen 'Pair device with"
                            + " pairing code' and try again");
                    return;
                }
                update(this, "Pairing… port " + port);
                String host = AdbShellController.pairHostFor(port);
                if (host == null) host = "127.0.0.1";
                AdbPairClient.Result r = AdbPairClient.pair(host, port, c);
                if (r != null && r.ok) {
                    finishPair();
                } else {
                    update(this, "Pair failed — reload the pairing screen (new code)"
                            + " and try again");
                }
            } catch (Throwable t) {
                update(this, "Pair failed: " + (t.getMessage() != null ? t.getMessage() : t.toString()));
            } finally {
                pairing = false;
            }
        }).start();
    }

    private void finishPair() {
        PairState.markPaired(this);
        update(this, "Paired ✓ — connecting…");
        boolean up = AdbShellController.ensureConnected();
        if (up) {
            update(this, "Connected ✓ — the AI shell can run adb commands");
            // one notification normally (user decision): show the success beat,
            // then fold away so only the main "Zyvo Code" notification remains
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (isRunning && !pairing) {
                    stopForeground(true);
                    stopSelf();
                }
            }, 4500);
        } else {
            update(this, "Paired ✓ — connect failed: keep Wireless debugging ON"
                    + " and tap ADB Connect again");
        }
    }

    private static Notification build(Context c, String status) {
        Intent open = new Intent(c, AdbPairActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 1103, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // inline code entry on the notification itself
        RemoteInput remote = new RemoteInput.Builder(KEY_CODE)
                .setLabel("6-digit pairing code")
                .build();
        Intent code = new Intent(c, AdbPairService.class)
                .setAction(ACTION_CODE);
        PendingIntent codePi = PendingIntent.getService(c, 1104, code,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        NotificationCompat.Action.WearableExtender ext =
                new NotificationCompat.Action.WearableExtender();
        NotificationCompat.Action codeAction = new NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_edit, "Enter code", codePi)
                .addRemoteInput(remote)
                .setAllowGeneratedReplies(false)
                .extend(ext)
                .build();

        // fold the whole pairing flow away without pairing
        Intent stop = new Intent(c, AdbPairService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(c, 1105, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(c, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Zyvo ADB Connect")
                .setContentText(status)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(codeAction)
                .addAction(new NotificationCompat.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi).build())
                .build();
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        stopDiscovery();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

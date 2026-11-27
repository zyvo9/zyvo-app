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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "ADB Connect", NotificationManager.IMPORTANCE_LOW);
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
                int port = livePairPort > 0
                        && System.currentTimeMillis() - livePairPortAt < 20_000 ? livePairPort : -1;
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
        if (p > 0 && System.currentTimeMillis() - livePairPortAt < 30_000) {
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
            nsd.discoverServices("_adb-tls-pairing", NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Throwable t) {
            discovering = false;
        }
    }

    private void stopDiscovery() {
        if (!discovering) return;
        discovering = false;
        try {
            nsd.stopServiceDiscovery(discoveryListener);
        } catch (Throwable ignored) {
        }
    }

    /** Kept ALIVE on purpose: a rotated pairing dialog advertises a NEW service
     *  and this listener picks it up within milliseconds. */
    private final NsdManager.DiscoveryListener discoveryListener = new NsdManager.DiscoveryListener() {
        @Override public void onDiscoveryStarted(String type) { }
        @Override public void onStartDiscoveryFailed(String type, int error) { discovering = false; }
        @Override public void onDiscoveryStopped(String type) { }
        @Override public void onStopDiscoveryFailed(String type, int error) { }
        @Override public void onServiceLost(NsdServiceInfo info) {
            livePairPort = -1;
        }
        @Override public void onServiceFound(NsdServiceInfo info) {
            // the port only arrives after resolve — found alone usually has 0
            try {
                nsd.resolveService(info, resolveListener);
            } catch (Throwable ignored) {
            }
        }
    };

    private final NsdManager.ResolveListener resolveListener = new NsdManager.ResolveListener() {
        @Override public void onResolveFailed(NsdServiceInfo info, int error) { }
        @Override public void onServiceResolved(NsdServiceInfo info) {
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

    // ------------------------------------------------------------- pairing

    /** Single pairing brain: discovered port first, then candidate rounds. */
    private void pairFlow(String code, int preferredPort) {
        pairing = true;
        update(this, "Pairing… keep the pairing screen open");
        final String c = code;
        new Thread(() -> {
            try {
                // Shizuku fast path: one handshake against the already-known port
                if (preferredPort > 0) {
                    update(this, "Pairing… port " + preferredPort);
                    String host = AdbShellController.pairHostFor(preferredPort);
                    if (host == null) host = "127.0.0.1";
                    AdbPairClient.Result fast = AdbPairClient.pair(host, preferredPort, c);
                    if (fast != null && fast.ok) {
                        finishPair();
                        return;
                    }
                }

                // fallback + port-rotation recovery: the SAME code stays valid
                // while the dialog is open, so keep retrying new ports with it
                long deadline = System.currentTimeMillis() + 40_000L;
                Set<Integer> tried = new HashSet<>();
                LinkedHashSet<Integer> scanned = new LinkedHashSet<>();
                long lastScanAt = 0;
                while (isRunning && System.currentTimeMillis() < deadline) {
                    LinkedHashSet<Integer> candidates = new LinkedHashSet<>();
                    if (manualPort > 0) candidates.add(manualPort);
                    if (livePairPort > 0
                            && System.currentTimeMillis() - livePairPortAt < 30_000) {
                        candidates.add(livePairPort);
                    }
                    if (scanned.isEmpty() || System.currentTimeMillis() - lastScanAt > 8_000) {
                        scanned = new LinkedHashSet<>(AdbShellController.scanPairPorts());
                        lastScanAt = System.currentTimeMillis();
                    }
                    candidates.addAll(scanned);
                    candidates.removeAll(tried);
                    if (!candidates.isEmpty()) {
                        update(this, "Pairing… trying " + candidates.size() + " port(s)");
                        ExecutorService pool = Executors.newFixedThreadPool(
                                Math.min(8, Math.max(2, candidates.size())));
                        final AtomicReference<AdbPairClient.Result> winner =
                                new AtomicReference<>();
                        final CountDownLatch win = new CountDownLatch(1);
                        for (final int p : candidates) {
                            pool.execute(() -> {
                                if (winner.get() != null) return;
                                try {
                                    String host = AdbShellController.pairHostFor(p);
                                    if (host == null) host = "127.0.0.1";
                                    AdbPairClient.Result r = AdbPairClient.pair(host, p, c);
                                    if (r.ok && winner.compareAndSet(null, r)) win.countDown();
                                } catch (Throwable ignored) {
                                }
                            });
                        }
                        win.await(15, TimeUnit.SECONDS);
                        pool.shutdownNow();
                        tried.addAll(candidates);
                        if (winner.get() != null && winner.get().ok) {
                            finishPair();
                            return;
                        }
                        update(this, "Pair failed on those ports — keep the pairing"
                                + " screen open, retrying with the same code…");
                    }
                    Thread.sleep(1200);
                }
                update(this, "Pair failed — reload the pairing screen (port rotates)"
                        + " and type the new code");
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
        update(this, up
                ? "Connected ✓ — the AI shell can run adb commands"
                : "Paired ✓ — connect failed: keep Wireless debugging ON and retry the code");
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

        return new NotificationCompat.Builder(c, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Zyvo ADB Connect")
                .setContentText(status)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(codeAction)
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

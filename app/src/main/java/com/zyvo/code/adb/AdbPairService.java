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
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * Shizuku-style wireless-debugging pairing — fully automatic. The notification
 * itself carries an inline text field: the user types the 6-digit code shown on
 * the system pairing screen and taps send. Pairing port (mDNS _adb-tls-pairing)
 * and connect port (mDNS _adb-tls-connect / probe) are both auto-discovered.
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

    /** Re-posts the status notification (pairing progress / result). */
    public static void update(Context c, String status) {
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
        // the inline code arrived from the notification's RemoteInput
        if (intent != null && ACTION_CODE.equals(intent.getAction())) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            if (results != null) {
                String code = results.getCharSequence(KEY_CODE) != null
                        ? results.getCharSequence(KEY_CODE).toString().trim().replace(" ", "") : "";
                if (!code.isEmpty() && !pairing) pairFlow(code);
            }
        }
        return START_STICKY;
    }

    private String statusText(Context c) {
        if (pairing) return "Pairing… keep the pairing screen open";
        if (PairState.isPaired(c)) {
            return AdbShellController.isConnected()
                    ? "Connected ✓ — shell ready" : "Paired — open Wireless debugging, then type the code again to reconnect";
        }
        return "Type the 6-digit code from the pairing screen";
    }

    /** The full automatic flow: discover pair port -> pair -> discover connect port -> connect. */
    private void pairFlow(String code) {
        pairing = true;
        update(this, "Pairing… keep the pairing screen open");
        new Thread(() -> {
            try {
                // DEMO RACE STYLE: mDNS port (one attempt, concurrent) + loopback
                // TLS scan — every candidate pairs in parallel (8 threads, first
                // success wins instantly). No serial retries.
                final java.util.LinkedHashSet<Integer> candidates = new java.util.LinkedHashSet<>();
                final java.util.concurrent.CountDownLatch mdnsDone = new java.util.concurrent.CountDownLatch(1);
                final java.util.concurrent.atomic.AtomicInteger mdnsPort = new java.util.concurrent.atomic.AtomicInteger(-1);
                Thread mdns = new Thread(() -> {
                    try {
                        mdnsPort.set(AdbShellController.findPairPort(this));
                    } catch (Throwable ignored) {
                    } finally {
                        mdnsDone.countDown();
                    }
                });
                mdns.start();
                java.util.List<Integer> scanned = AdbShellController.scanPairPorts();
                candidates.addAll(scanned);
                mdnsDone.await(6, java.util.concurrent.TimeUnit.SECONDS);
                if (mdnsPort.get() > 0) candidates.add(mdnsPort.get());
                final int nPorts = candidates.size();
                update(this, "Pairing… trying " + nPorts + " port(s)");

                java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                        Math.min(8, Math.max(2, nPorts)));
                final java.util.concurrent.atomic.AtomicReference<AdbPairClient.Result> winner =
                        new java.util.concurrent.atomic.AtomicReference<>();
                final java.util.concurrent.CountDownLatch win = new java.util.concurrent.CountDownLatch(1);
                for (final int p : candidates) {
                    pool.execute(() -> {
                        if (winner.get() != null) return;
                        try {
                            String host = AdbShellController.pairHostFor(p);
                            if (host == null) host = "127.0.0.1";
                            AdbPairClient.Result r = AdbPairClient.pair(host, p, code);
                            if (r.ok && winner.compareAndSet(null, r)) win.countDown();
                        } catch (Throwable ignored) {
                        }
                    });
                }
                win.await(20, java.util.concurrent.TimeUnit.SECONDS);
                pool.shutdownNow();
                AdbPairClient.Result rr = winner.get();
                if (rr == null || !rr.ok) {
                    update(this, "Pair failed — reload the pairing screen (port rotates) and type the new code");
                    pairing = false;
                    return;
                }
                PairState.markPaired(this);
                update(this, "Paired ✓ — connecting…");
                boolean up = AdbShellController.ensureConnected();
                update(this, up
                        ? "Connected ✓ — the AI shell can run adb commands"
                        : "Paired ✓ — connect failed: keep Wireless debugging ON and retry the code");
            } catch (Throwable t) {
                update(this, "Pair failed: " + (t.getMessage() != null ? t.getMessage() : t.toString()));
            } finally {
                pairing = false;
            }
        }).start();
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
    public IBinder onBind(Intent intent) {
        return null;
    }
}

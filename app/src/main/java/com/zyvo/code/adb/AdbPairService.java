package com.zyvo.code.adb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * Shizuku-style wireless-debugging pairing service: one low-priority ongoing
 * notification that carries the "enter pairing code" action while the user is
 * on the system's wireless debugging screen.
 */
public class AdbPairService extends Service {
    private static final String TAG = "AdbPairService";
    public static final String CHANNEL_ID = "adb_pair";
    private static final int NOTIF_ID = 1102;

    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";

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
            if (nm != null && AdbPairService.isRunning) {
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
        Notification n = build(this, PairState.isPaired(this)
                ? "Paired — tap to manage" : "Tap to enter the pairing code");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
        return START_STICKY;
    }

    private static Notification build(Context c, String status) {
        Intent open = new Intent(c, AdbPairActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 1103, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(c, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Zyvo ADB Connect")
                .setContentText(status)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

package com.zyvo.code;

import com.termux.R;
import com.termux.app.TermuxService;
import com.termux.shared.termux.TermuxConstants;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

/**
 * The one and only notification of the app.
 *
 * The terminal service and the engine service both run as foreground services.
 * They share this notification id and channel, so the shade shows a single
 * "Zyvo Code" entry. During ADB pairing the SAME notification converts: its
 * text becomes the live pairing status and it grows the inline pairing-code
 * field (+ Stop); once paired it converts back to the engine status
 * automatically (user decision: never two notifications).
 */
public final class ZyvoNotif {

    public static final String CHANNEL_ID = "zyvo_engine";
    public static final int ID = 4101;

    public static final String ACTION_ADB_CODE = "com.zyvo.code.ADB_CODE";
    public static final String KEY_CODE = "adb_code";
    public static final String ACTION_ADB_STOP = "com.zyvo.code.ADB_STOP";

    private static volatile String text = "Terminal running";
    private static volatile boolean wakeHeld = false;
    /** Non-null while the ADB pairing flow owns this notification. */
    private static volatile String adbStatus = null;

    private ZyvoNotif() {
    }

    /** Create (or update) the low-importance channel used by both services. */
    public static void ensureChannel(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        // Remove the old separate "Termux" notification left behind by earlier builds.
        nm.cancel(TermuxConstants.TERMUX_APP_NOTIFICATION_ID);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Zyvo Code",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps the zyvo agent and terminal running");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    /** Set the status line shown in the notification. */
    public static void setText(Context ctx, String t) {
        text = t;
        refresh(ctx);
    }

    /** Remember whether the terminal wake lock is held (drives the Awake action label). */
    public static void setWakeHeld(Context ctx, boolean held) {
        wakeHeld = held;
        refresh(ctx);
    }

    /**
     * ADB pairing mode: while {@code s} is non-null the ONE notification shows
     * the live pairing status with the inline code field; null converts it
     * back to the engine status (called from EngineService).
     */
    public static void setAdbStatus(Context ctx, String s) {
        adbStatus = (s == null || s.trim().isEmpty()) ? null : s;
        refresh(ctx);
    }

    /** Current pairing status, null when the notification is in engine mode. */
    public static String adbStatusOrNull() {
        return adbStatus;
    }

    /** "Task done" push: fired when a turn finishes with the screen off or the
     *  app in the background. Tap = open the chat. */
    public static void notifyTaskDone(Context ctx, String firstLine) {
        ensureChannel(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel("task_done", "Task finished",
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(ctx, ChatActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 4105, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(ctx, "task_done")
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle("✓ Task done")
                .setContentText(firstLine)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(4106, n);
    }

    public static Notification build(Context ctx) {
        Intent open = new Intent(ctx, ChatActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // ADB pairing mode: same notification, pairing content + inline code field
        String adb = adbStatus;
        if (adb != null) {
            RemoteInput remote = new RemoteInput.Builder(KEY_CODE)
                    .setLabel("6-digit pairing code")
                    .build();
            Intent code = new Intent(ctx, EngineService.class).setAction(ACTION_ADB_CODE);
            PendingIntent codePi = PendingIntent.getService(ctx, 1104, code,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            NotificationCompat.Action codeAction = new NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_edit, "Enter code", codePi)
                    .addRemoteInput(remote)
                    .setAllowGeneratedReplies(false)
                    .extend(new NotificationCompat.Action.WearableExtender())
                    .build();
            Intent stop = new Intent(ctx, EngineService.class).setAction(ACTION_ADB_STOP);
            PendingIntent stopPi = PendingIntent.getService(ctx, 1105, stop,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            return new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_terminal)
                    .setContentTitle("Zyvo Code")
                    .setContentText(adb)
                    .setContentIntent(contentIntent)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .addAction(codeAction)
                    .addAction(new NotificationCompat.Action.Builder(
                            android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi).build())
                    .build();
        }

        Intent wake = new Intent(ctx, TermuxService.class);
        wake.setAction(wakeHeld
                ? TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_WAKE_UNLOCK
                : TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_WAKE_LOCK);
        PendingIntent wakeIntent = PendingIntent.getService(ctx, 1, wake,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent exit = new Intent(ctx, ExitReceiver.class);
        PendingIntent exitIntent = PendingIntent.getBroadcast(ctx, 2, exit,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle("Zyvo Code")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(R.drawable.ic_terminal, wakeHeld ? "Release" : "Awake", wakeIntent)
                .addAction(R.drawable.ic_terminal, "Exit", exitIntent)
                .build();
    }

    /** Re-post the notification (safe to call from any service in the app). */
    public static void refresh(Context ctx) {
        ensureChannel(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ID, build(ctx));
    }
}

package com.zyvo.code;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.app.NotificationManager;
import android.os.Build;

import com.termux.app.TermuxService;

/**
 * Notification "Exit": stops every service, cancels the notifications and
 * force-stops the whole app process — exactly what the old action failed to do.
 */
public class ExitReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        try { ctx.stopService(new Intent(ctx, EngineService.class)); } catch (Throwable ignored) {}
        try { ctx.stopService(new Intent(ctx, TermuxService.class)); } catch (Throwable ignored) {}
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancelAll();
        } catch (Throwable ignored) {}
        // let the stops land, then hard-kill the process (force stop)
        new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // wake locks die with the process; a plain kill is the force-stop
                android.os.Process.killProcess(android.os.Process.myPid());
            }
            java.lang.System.exit(0);
        }).start();
    }
}

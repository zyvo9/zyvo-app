package com.zyvo.code.adb;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Invisible trampoline (translucent theme, finishes immediately): everything
 * the user needs lives on the AdbPairService NOTIFICATION - the inline
 * pairing-code field and the live status. Tapping "ADB Connect" in the drawer
 * starts the pairing service, jumps to the system wireless debugging screen
 * and lands back with nothing extra on screen (user decision: no separate
 * yellow form, one notification only once paired).
 */
public class AdbPairActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WdbContext.init(getApplicationContext());

        // already paired: no service, no notification, no settings jump -
        // just silently make sure the shell link is up
        if (PairState.isPaired(this)) {
            new Thread(() -> {
                final boolean up = AdbShellController.ensureConnected();
                runOnUiThread(() -> {
                    com.zyvo.code.UiUtil.toast(this, up
                            ? "ADB connected ✓ — the AI shell can control the phone"
                            : "Wireless debugging OFF — turn it on, then tap ADB Connect again");
                    finish();
                });
            }).start();
            return;
        }

        // first pairing: the service posts the notification (inline code field,
        // live port detection), we open the system pairing page and disappear
        AdbPairService.start(this);
        try {
            startActivity(new Intent("com.android.settings.WIRELESS_DEBUGGING_SETTINGS"));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Throwable ignored) {
            }
        }
        finish();
    }
}

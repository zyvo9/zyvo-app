package com.zyvo.code.adb;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import androidx.core.content.ContextCompat;

import com.zyvo.code.EngineService;

/**
 * Invisible trampoline (translucent theme, finishes immediately): everything
 * the user needs lives on the ONE "Zyvo Code" notification - during pairing
 * it CONVERTS to show the live status with the inline pairing-code field,
 * and converts back to the engine status once paired. Tapping "ADB Connect"
 * in the drawer tells EngineService to enter pairing mode, jumps to the
 * system wireless debugging screen and lands back with nothing extra on
 * screen (user decision: no separate form, never two notifications).
 */
public class AdbPairActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WdbContext.init(getApplicationContext());

        // already paired: no pairing mode, no settings jump - just silently
        // make sure the shell link is up
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

        // first pairing: EngineService converts the single notification into
        // pairing mode (inline code field, live port detection); we open the
        // system pairing page and disappear
        Intent i = new Intent(this, EngineService.class).setAction(EngineService.ACTION_ADB_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, i);
        } else {
            startService(i);
        }
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

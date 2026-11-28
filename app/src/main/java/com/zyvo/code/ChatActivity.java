package com.zyvo.code;

import com.termux.R;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

/**
 * Chat-first launcher activity for the Zyvo Code build on top of Termux.
 * The hamburger opens the ChatGPT-style session drawer; the terminal and
 * settings live in the drawer and the plus menu.
 */
public final class ChatActivity extends AppCompatActivity {

    private DrawerLayout drawer;

    /** True once the Termux prefix is installed (or install is known-unnecessary).
     *  The engine must not start before it: doStart() writes usr/bin + usr/etc
     *  and reads usr/bin/bash for the shell config, and a concurrent writer
     *  inside files/usr is what made the FIRST bootstrap install fail. */
    private boolean bootstrapReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Zyvo Code is a dark-first app: force night mode so the Zai dark tokens
        // always resolve, whatever the system theme is set to.
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (night != Configuration.UI_MODE_NIGHT_YES) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        Window w = getWindow();
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        drawer = findViewById(R.id.drawer_layout);

        com.zyvo.code.adb.WdbContext.init(getApplicationContext());
        com.zyvo.code.adb.AdbBridgeServer.start(this);
        // ADB pairing notification is ON-DEMAND only (user decision: one
        // notification normally). It appears when the user taps ADB Connect
        // (AdbPairActivity) and removes itself a few seconds after Connected.

        // notifications (pairing + engine) need the runtime grant on API 33+
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 4102);
        }

        // ChatActivity is the only launcher, so the stock Termux first-run flow
        // never triggers. Install the bootstrap (files/usr: bash, pkg, apt)
        // ourselves, or the engine's shell is stuck with toybox-only commands.
        // The engine is gated on it: whenDone fires only after the prefix is
        // complete, so writeTermuxBrand/writeAdbBridge land in the real prefix
        // and never race the installer's staging rename.
        try {
            com.termux.app.TermuxInstaller.setupBootstrapIfNeeded(this, () -> {
                bootstrapReady = true;
                startEngine();
            });
        } catch (Throwable t) {
            // engine still works on toybox; the log dialog shows what failed
            bootstrapReady = true;
            startEngine();
        }

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.zyvo_chat_container, new ChatFragment())
                    .commit();
        }

        findViewById(R.id.btn_drawer_new).setOnClickListener(v -> {
            ChatFragment f = chat();
            if (f != null) f.newSession();
            closeDrawer();
        });
        findViewById(R.id.btn_drawer_terminal).setOnClickListener(v -> {
            openTerminal();
            closeDrawer();
        });
        findViewById(R.id.btn_drawer_log).setOnClickListener(v -> {
            showEngineLog();
            closeDrawer();
        });
        findViewById(R.id.btn_drawer_adb).setOnClickListener(v -> {
            try {
                startActivity(new Intent(this, com.zyvo.code.adb.AdbPairActivity.class));
            } catch (Throwable ignored) {
            }
            closeDrawer();
        });
        findViewById(R.id.btn_drawer_settings).setOnClickListener(v -> {
            openSettings();
            closeDrawer();
        });

        View profile = findViewById(R.id.btn_drawer_profile);
        if (profile != null) {
            profile.setOnClickListener(v -> {
                openSettings();
                closeDrawer();
            });
        }

        batteryPrompt();
        checkUpdate();
        // onboarding needs the window up first, or the dialog has no token
        findViewById(R.id.drawer_layout).post(this::onboarding);
    }

    /** First run: one welcome dialog — what the app is, what the free lane does. */
    private void onboarding() {
        try {
            Prefs p = new Prefs(this);
            if (p.onboardingDone()) return;
            int pad = Math.round(20 * getResources().getDisplayMetrics().density);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(pad, pad / 2, pad, 0);
            TextView body = new TextView(this);
            body.setTextSize(15);
            body.setTextColor(Color.parseColor("#E4E4EB"));
            body.setLineSpacing(Math.round(3 * getResources().getDisplayMetrics().density), 1f);
            body.setText("Your AI agent runs on THIS phone.\n\n"
                    + "• Ask anything — code, files, modding, earning bots\n"
                    + "• Tools really run here: bash, files, web, adb\n"
                    + "• Free model lane can rate-limit — switching mobile data (fresh IP) fixes it\n"
                    + "• Long tasks keep running with the screen off");
            box.addView(body);
            new AlertDialog.Builder(this)
                    .setTitle("Welcome to Zyvo Code")
                    .setView(box)
                    .setPositiveButton("Start", (d, w) -> p.setOnboardingDone())
                    .setCancelable(false)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    /** Long background tasks die under battery optimization — ask once. */
    private void batteryPrompt() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("zyvo_app", MODE_PRIVATE);
            if (sp.getBoolean("battery_asked", false)) return;
            sp.edit().putBoolean("battery_asked", true).apply();
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Battery optimization")
                    .setMessage("Long AI tasks can be killed in the background. Allow Zyvo to run unrestricted?")
                    .setPositiveButton("Allow", (d, w) -> {
                        try {
                            startActivity(new android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:" + getPackageName())));
                        } catch (Throwable ignored) {}
                    })
                    .setNegativeButton("Not now", null)
                    .show();
        } catch (Throwable ignored) {}
    }

    /** GitHub release check: a newer apk-NN exists -> offer it. Silent when same.
     *  Points at zyvo-app — the repo the user consolidated all work into. */
    private void checkUpdate() {
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            try {
                java.net.URL url = new java.net.URL(
                        "https://api.github.com/repos/zyvo9/zyvo-app/releases/latest");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                String tag = "";
                try (java.io.BufferedReader in = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = in.readLine()) != null) sb.append(l);
                    String body = sb.toString();
                    int i = body.indexOf("\"tag_name\":\"");
                    if (i >= 0) {
                        int a = i + 12;
                        int e = body.indexOf('"', a);
                        tag = body.substring(a, e);
                    }
                }
                int latestRaw = -1;
                if (tag.startsWith("apk-")) latestRaw = Integer.parseInt(tag.substring(4).trim());
                final int latest = latestRaw;
                final int mine = com.termux.BuildConfig.ZYVO_BUILD;
                android.content.SharedPreferences sp = getSharedPreferences("zyvo_app", MODE_PRIVATE);
                if (latest > mine && latest != sp.getInt("update_seen", -1)) {
                    sp.edit().putInt("update_seen", latest).apply();
                    runOnUiThread(() -> new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Update available (apk-" + latest + ")")
                            .setMessage("A newer Zyvo Code build is out. Open the release page to install it?")
                            .setPositiveButton("Open", (d, w) -> {
                                try {
                                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://github.com/zyvo9/zyvo-app/releases/tag/apk-"
                                                    + latest)));
                                } catch (Throwable ignored) {}
                            })
                            .setNegativeButton("Later", null)
                            .show());
                }
            } catch (Throwable ignored) {}
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Also restarts the foreground engine service after the user taps Exit in
        // the notification and later comes back to the app. On the very first
        // launch the bootstrap install is still running (whenDone fires later and
        // calls startEngine itself) - starting now would write into files/usr
        // while the installer is extracting and break the staging rename.
        if (bootstrapReady) startEngine();
    }

    private ChatFragment chat() {
        return (ChatFragment) getSupportFragmentManager()
                .findFragmentById(R.id.zyvo_chat_container);
    }

    public void closeDrawer() {
        if (drawer != null) drawer.closeDrawer(GravityCompat.START);
    }

    private void openTerminal() {
        try {
            startActivity(new Intent(this, Class.forName("com.termux.app.TermuxActivity")));
        } catch (Throwable t) {
            UiUtil.toast(this, "Terminal is not available");
        }
    }

    private void openSettings() {
        try {
            startActivity(new Intent(this, SettingsActivity.class));
        } catch (Throwable t) {
            UiUtil.toast(this, "Settings are not available");
        }
    }

    /**
     * Plain debug surface: the tail of the engine's own stderr/stdout. When a
     * turn dies silently this is the only place that says why.
     */
    private void showEngineLog() {
        String tail = EngineManager.get(this).tailLog(250);
        if (tail == null || tail.trim().isEmpty()) tail = "No engine output yet.";
        final String text = tail;

        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        TextView view = new TextView(this);
        view.setPadding(pad, pad, pad, pad);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setText(text);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);

        new AlertDialog.Builder(this)
                .setTitle(R.string.engine_logs)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.copy, (d, w) -> {
                    ClipboardManager cm = (ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("engine log", text));
                        UiUtil.toast(this, getString(R.string.copied));
                    }
                })
                .show();
    }

    @Override
    public void onBackPressed() {
        if (drawer != null && drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
            return;
        }
        super.onBackPressed();
    }

    private void startEngine() {
        try {
            Intent i = new Intent(this, EngineService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, i);
            } else {
                startService(i);
            }
        } catch (Throwable ignored) {
            // ChatFragment shows the engine banner and lets the user retry.
        }
    }
}

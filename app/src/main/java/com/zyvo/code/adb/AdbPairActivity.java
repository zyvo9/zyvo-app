package com.zyvo.code.adb;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shizuku-style pairing dialog: opens the system wireless debugging page,
 * then the user just types the 6-digit pairing code. The port is discovered
 * LIVE by AdbPairService's continuous mDNS watcher (this screen mirrors its
 * status and auto-fills the port); a manual port stays optional for ROMs
 * where mDNS is blocked. Pair runs against 127.0.0.1 — the phone pairs with
 * itself. Pairing itself happens in the SERVICE so the flow also works when
 * the code is typed straight on the notification.
 */
public class AdbPairActivity extends Activity {

    private TextView status;
    private EditText pairPort;
    private EditText code;
    private EditText connectPort;
    private Button action;
    private Button disconnect;
    private boolean connectTried;

    private final Handler poll = new Handler(Looper.getMainLooper());
    private final Runnable pollTick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            // mirror the service's live status (notification text)
            String s = AdbPairService.liveStatus;
            if (s != null && !s.isEmpty()) status.setText(s);
            // auto-fill the pairing port the moment discovery resolves it
            int p = AdbPairService.livePairPort;
            if (p > 0 && System.currentTimeMillis() - AdbPairService.livePairPortAt < 60_000
                    && pairPort.getText().toString().trim().isEmpty()) {
                pairPort.setText(String.valueOf(p));
            }
            // paired but not connected, and the user typed a manual connect port
            if (s != null && s.contains("connect failed") && !connectTried) {
                String ct = connectPort.getText().toString().trim();
                if (!ct.isEmpty()) {
                    connectTried = true;
                    try {
                        AdbShellController.connectManual(AdbPairActivity.this,
                                Integer.parseInt(ct));
                    } catch (Throwable ignored) {
                    }
                }
            }
            poll.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WdbContext.init(getApplicationContext());
        AdbPairService.start(this);

        int pad = Math.round(18 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#1A1A1A"));
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("ADB Connect");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        status = new TextView(this);
        status.setText("1) Turn ON Wireless debugging\n2) Tap \"Pair device with pairing code\"\n3) Type the code below");
        status.setTextSize(13);
        status.setTextColor(Color.parseColor("#9A9A9A"));
        status.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(status);

        pairPort = field(root, "Pairing port (auto-detected)");
        code = field(root, "6-digit pairing code");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);

        connectPort = field(root, "Connect port (only if auto-connect fails)");
        connectPort.setInputType(InputType.TYPE_CLASS_NUMBER);

        action = button("Pair & connect");
        action.setOnClickListener(v -> runPair());
        root.addView(action, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        disconnect = button("Disconnect");
        disconnect.setOnClickListener(v -> {
            PairState.clear(this);
            AdbShellController.close();
            AdbPairService.stop(this);
            status.setText("Disconnected.");
        });
        root.addView(disconnect, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        // jump straight to the system wireless debugging screen
        try {
            startActivity(new Intent("com.android.settings.WIRELESS_DEBUGGING_SETTINGS"));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Throwable ignored) {
            }
        }

        refreshState();
        poll.post(pollTick);
    }

    @Override
    protected void onDestroy() {
        poll.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private EditText field(LinearLayout root, String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.parseColor("#6B6B6B"));
        e.setTextSize(14);
        e.setSingleLine(true);
        root.addView(e, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackgroundColor(Color.parseColor("#E58E3A"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(8 * getResources().getDisplayMetrics().density);
        b.setLayoutParams(lp);
        return b;
    }

    private void refreshState() {
        boolean paired = PairState.isPaired(this);
        // the service's live discovery fills this within ms of the dialog
        // opening; only fall back to an on-demand hunt when it has nothing
        int port = AdbPairService.livePairPort;
        if (port <= 0 || System.currentTimeMillis() - AdbPairService.livePairPortAt > 30_000) {
            port = AdbShellController.findPairPort(this);
        }
        if (port > 0 && pairPort.getText().toString().trim().isEmpty()) {
            pairPort.setText(String.valueOf(port));
        }
        if (paired) {
            boolean connected = AdbShellController.ensureConnected();
            status.setText(connected
                    ? "Connected ✓ — the AI shell can run adb commands."
                    : "Paired — enable Wireless debugging and pair again if the port changed.");
        } else {
            AdbPairService.update(this, "Searching for the pairing screen…");
        }
    }

    /** No more hard port requirement: the service pairs the discovered port
     *  first, then falls back to candidates + same-code retries on its own. */
    private void runPair() {
        String codeText = code.getText().toString().trim().replace(" ", "");
        if (codeText.length() != 6) {
            status.setText("Type the 6-digit pairing code first.");
            return;
        }
        int manual = -1;
        try {
            manual = Integer.parseInt(pairPort.getText().toString().trim());
        } catch (Exception ignored) {
        }
        connectTried = false;
        status.setText("Pairing… keep the pairing screen open");
        AdbPairService.requestPair(this, codeText, manual);
    }
}

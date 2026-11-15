package com.zyvo.code.adb;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/**
 * Shizuku-style pairing dialog: opens the system wireless debugging page,
 * then the user types the 6-digit pairing code (and, if auto-discovery fails,
 * the connect port). Pair runs against 127.0.0.1 — the phone pairs with itself.
 */
public class AdbPairActivity extends Activity {

    private TextView status;
    private EditText pairPort;
    private EditText code;
    private EditText connectPort;
    private Button action;
    private Button disconnect;

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

        pairPort = field(root, "Pairing port (auto-filled if found)");
        code = field(root, "6-digit pairing code");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);

        connectPort = field(root, "Connect port (only if auto-discovery fails)");
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
        int port = AdbShellController.findPairPort(this);
        if (port > 0 && pairPort.getText().toString().trim().isEmpty()) {
            pairPort.setText(String.valueOf(port));
        }
        if (paired) {
            boolean connected = AdbShellController.ensureConnected();
            status.setText(connected
                    ? "Connected ✓ — the AI shell can run adb commands."
                    : "Paired — enable Wireless debugging and pair again if the port changed.");
        } else {
            AdbPairService.update(this, "Tap to enter the pairing code");
        }
    }

    private void runPair() {
        String codeText = code.getText().toString().trim().replace(" ", "");
        String portText = pairPort.getText().toString().trim();
        action.setEnabled(false);
        status.setText("Pairing…");
        AdbPairService.update(this, "Pairing…");
        final int port;
        try {
            port = Integer.parseInt(portText);
        } catch (Exception e) {
            status.setText("Type the pairing PORT shown on the pairing screen.");
            action.setEnabled(true);
            return;
        }
        new Thread(() -> {
            AdbPairClient.Result r = AdbPairClient.pair("127.0.0.1", port, codeText);
            runOnUiThread(() -> {
                action.setEnabled(true);
                if (r.ok) {
                    PairState.markPaired(this);
                    status.setText("Paired ✓ — connecting…");
                    AdbPairService.update(this, "Paired — connecting…");
                    new Thread(() -> {
                        final String manual = connectPort.getText().toString().trim();
                        boolean up;
                        if (!manual.isEmpty()) {
                            try {
                                up = AdbShellController.connectManual(this, Integer.parseInt(manual));
                            } catch (Exception e) {
                                up = false;
                            }
                        } else {
                            up = AdbShellController.ensureConnected();
                        }
                        final boolean ok = up;
                        runOnUiThread(() -> {
                            status.setText(ok
                                    ? "Connected ✓ — the AI shell can run adb commands."
                                    : "Paired ✓ — but connect failed. Type the connect port from the Wireless debugging screen and tap Pair & connect again.");
                        });
                    }).start();
                } else {
                    status.setText("Pair failed: " + r.message
                            + "\nReload the pairing screen (the port changes every time) and try again.");
                }
            });
        }).start();
    }
}

package com.zyvo.code;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.termux.BuildConfig;
import com.termux.R;

/**
 * Zyvo's own settings screen: model, lane key, system prompt, engine control
 * and about. Plain programmatic dark UI (same style as AdbPairActivity).
 * Every value the engine reads at startup (key, prompt, model) restarts the
 * engine on save, so a change applies without leaving the screen.
 */
public final class SettingsActivity extends Activity {

    private Prefs prefs;
    private EngineManager engine;

    private TextView engineState;
    private TextView modelValue;
    private TextView keyValue;
    private TextView promptValue;
    private TextView a11yState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        engine = EngineManager.get(this);

        int pad = dp(16);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0B0B0F"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, dp(24));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        LinearLayout engineCard = card(root, "Engine");
        engineState = value(engineCard, "");
        action(engineCard, "Engine logs", v -> showEngineLog());
        action(engineCard, "Restart engine", v -> {
            engine.restart();
            UiUtil.toast(this, "Engine restarting…");
        });

        LinearLayout modelCard = card(root, "Model");
        modelValue = value(modelCard, "");
        action(modelCard, "Change model", v -> showModelDialog());

        LinearLayout keyCard = card(root, "API key");
        keyValue = value(keyCard, "");
        action(keyCard, "Set custom key", v -> showKeyDialog());

        LinearLayout promptCard = card(root, "System prompt");
        promptValue = value(promptCard, "");
        action(promptCard, "Edit prompt", v -> showPromptDialog());

        LinearLayout a11yCard = card(root, "Phone control");
        a11yState = value(a11yCard, "");
        action(a11yCard, "Enable / manage in Settings", v -> {
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable t) {
                UiUtil.toast(this, "Open Settings > Accessibility > Zyvo Phone Control");
            }
        });

        LinearLayout aboutCard = card(root, "About");
        value(aboutCard, "Zyvo Code · Termux fork with a bundled AI agent");
        value(aboutCard, "Build: apk-" + BuildConfig.ZYVO_BUILD
                + " · version " + BuildConfig.VERSION_NAME);
        String abi = android.os.Build.SUPPORTED_ABIS.length > 0
                ? android.os.Build.SUPPORTED_ABIS[0] : "?";
        value(aboutCard, "Device: Android " + android.os.Build.VERSION.RELEASE
                + " (" + abi + ")");

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    /** Re-reads every value from prefs + the engine's live state. */
    private void refresh() {
        engineState.setText(engine.state() + " · " + engine.detail()
                + (engine.engineVersion().isEmpty() ? "" : " · v" + engine.engineVersion()));
        modelValue.setText(Models.nameOf(prefs.modelId()));
        if (prefs.hasCustomKey()) {
            keyValue.setText("Custom key set (" + prefs.userOpencodeKey().trim().length() + " chars)");
        } else {
            keyValue.setText("Built-in lane key"
                    + (prefs.hasLaneKey() ? " active" : " — none, add one"));
        }
        String p = prefs.systemPrompt();
        promptValue.setText(TextUtils.isEmpty(p)
                ? "None — the built-in app rules only"
                : "Custom prompt · " + p.length() + " chars");
        a11yState.setText(a11yEnabled()
                ? "Enabled — the AI can see the screen and control apps"
                : "Off — enable 'Zyvo Phone Control' so the AI can control apps");
    }

    /** True when the phone-control accessibility service is turned on. */
    private boolean a11yEnabled() {
        try {
            String s = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains("com.zyvo.code.control.ZyvoAccessibilityService");
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------- dialogs

    private void showModelDialog() {
        String[] names = new String[Models.FREE.length];
        int checked = 0;
        for (int i = 0; i < Models.FREE.length; i++) {
            names[i] = Models.FREE[i][1];
            if (Models.FREE[i][0].equals(prefs.modelId())) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Model")
                .setSingleChoiceItems(names, checked, (d, w) -> {
                    String[] m = Models.FREE[w];
                    prefs.setModel(Prefs.LANE_PROVIDER, m[0]);
                    refresh();
                    engine.restart();
                    UiUtil.toast(this, m[1] + " — engine restarting");
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showKeyDialog() {
        final EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        field.setSingleLine(true);
        field.setText(prefs.userOpencodeKey());
        int p = dp(16);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, dp(8), p, 0);
        box.addView(field);
        new AlertDialog.Builder(this)
                .setTitle("Custom API key")
                .setMessage("Paste an opencode API key. It replaces the built-in lane key and applies after the engine restarts. Clear the field to go back to the built-in key.")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.setOpencodeKey(field.getText().toString());
                    refresh();
                    engine.restart();
                    UiUtil.toast(this, prefs.hasCustomKey()
                            ? "Key saved — engine restarting"
                            : "Custom key cleared — engine restarting");
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showPromptDialog() {
        final EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setMinLines(6);
        field.setGravity(Gravity.TOP);
        field.setText(prefs.systemPrompt());
        int p = dp(16);
        ScrollView box = new ScrollView(this);
        box.setPadding(p, dp(8), p, 0);
        box.addView(field);
        new AlertDialog.Builder(this)
                .setTitle("System prompt")
                .setMessage("Extra instructions for every session. They ride into AGENTS.md ahead of the app rules. Empty = default behaviour.")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.setSystemPrompt(field.getText().toString());
                    refresh();
                    engine.restart();
                    UiUtil.toast(this, "Prompt saved — engine restarting");
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showEngineLog() {
        String tail = engine.tailLog(250);
        if (TextUtils.isEmpty(tail.trim())) tail = "No engine output yet.";
        final String text = tail;
        TextView view = new TextView(this);
        int p = dp(14);
        view.setPadding(p, p, p, p);
        view.setTextSize(10);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setTextColor(Color.parseColor("#E4E4EB"));
        view.setText(text);
        ScrollView box = new ScrollView(this);
        box.addView(view);
        new AlertDialog.Builder(this)
                .setTitle("Engine logs")
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("engine log", text));
                        UiUtil.toast(this, "Copied");
                    }
                })
                .show();
    }

    // ------------------------------------------------------------ builders

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** One rounded card with an amber section heading; returns the column. */
    private LinearLayout card(LinearLayout parent, String heading) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_popup_card);
        int p = dp(14);
        box.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        parent.addView(box, lp);

        TextView t = new TextView(this);
        t.setText(heading);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.parseColor("#E8A33D"));
        box.addView(t);
        return box;
    }

    private TextView value(LinearLayout card, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(Color.parseColor("#E4E4EB"));
        t.setPadding(0, dp(8), 0, 0);
        card.addView(t);
        return t;
    }

    private void action(LinearLayout card, String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackgroundResource(R.drawable.bg_accent_pill);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        b.setOnClickListener(listener);
        card.addView(b);
    }
}

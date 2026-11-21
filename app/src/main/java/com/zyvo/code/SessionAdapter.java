package com.zyvo.code;

import com.termux.R;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** ChatGPT-style chat history list for the session drawer. */
public final class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.VH> {

    public static final class Session {
        public final String id;
        public final String title;
        public final long updated;

        public Session(String id, String title, long updated) {
            this.id = id;
            this.title = title;
            this.updated = updated;
        }
    }

    public interface Listener {
        void onSessionClick(Session session);

        void onSessionLongClick(Session session);
    }

    private final Context ctx;
    private final List<Session> items = new ArrayList<>();
    private final List<Session> shown = new ArrayList<>();
    private final Listener listener;
    private String filter = "";
    private String currentId = "";

    public SessionAdapter(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    public void setItems(List<Session> sessions) {
        items.clear();
        if (sessions != null) items.addAll(sessions);
        notifyDataSetChanged();
    }

    /** "now" / "5m" / "3h" / "2d" — sidebar-style relative task time. */
    private static String relTime(long ms) {
        if (ms <= 0) return "";
        long s = (System.currentTimeMillis() - ms) / 1000;
        if (s < 0) s = 0;
        if (s < 60) return "now";
        long m = s / 60;
        if (m < 60) return m + "m";
        long hr = m / 60;
        if (hr < 24) return hr + "h";
        return hr / 24 + "d";
    }

    public void setFilter(String text) {
        filter = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        notifyDataSetChanged();
    }

    private List<Session> visible() {
        if (filter.isEmpty()) return items;
        shown.clear();
        for (Session s : items) {
            if (s.title != null && s.title.toLowerCase(Locale.ROOT).contains(filter)) shown.add(s);
        }
        return shown;
    }

    public void setCurrent(String id) {
        currentId = id == null ? "" : id;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(ctx).inflate(R.layout.item_session, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Session s = visible().get(position);
        h.title.setText(s.title);
        h.meta.setText(relTime(s.updated));
        boolean current = s.id.equals(currentId);
        h.itemView.setSelected(current);
        h.title.setTextColor(h.itemView.getResources().getColor(
                current ? R.color.text_primary : R.color.text_secondary));
        h.itemView.setOnClickListener(v -> listener.onSessionClick(s));
        h.itemView.setOnLongClickListener(v -> {
            listener.onSessionLongClick(s);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return visible().size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView meta;

        VH(@NonNull View v) {
            super(v);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            // soft press: the row dips while a finger is down and springs back
            float scale = android.provider.Settings.Global.getFloat(v.getContext().getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            if (scale != 0f) {
                v.setOnTouchListener((view, ev) -> {
                    int a = ev.getAction();
                    if (a == android.view.MotionEvent.ACTION_DOWN) {
                        view.animate().cancel();
                        view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                .start();
                    } else if (a == android.view.MotionEvent.ACTION_UP
                            || a == android.view.MotionEvent.ACTION_CANCEL) {
                        view.animate().cancel();
                        view.animate().scaleX(1f).scaleY(1f).setDuration(160)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                .start();
                    }
                    return false;
                });
            }
        }
    }
}

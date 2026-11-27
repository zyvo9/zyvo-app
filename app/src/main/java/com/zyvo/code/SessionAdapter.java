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
        public boolean pinned;

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
    /** The session whose agent turn is running - shows the live spinner. */
    private String busyId = "";

    public void setBusySession(String id) {
        String next = id == null ? "" : id;
        if (busyId.equals(next)) return;
        String prev = busyId;
        busyId = next;
        // only the two affected rows redraw - a full rebind restarts every
        // spinner animation and re-binds the whole drawer
        notifyRow(prev);
        notifyRow(busyId);
    }

    public SessionAdapter(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setItems(List<Session> sessions) {
        items.clear();
        if (sessions != null) {
            java.util.Set<String> pinned = new Prefs(ctx).pinnedSessions();
            for (Session s : sessions) s.pinned = pinned.contains(s.id);
            items.addAll(sessions);
        }
        rebuild();
        notifyDataSetChanged();
    }

    /** Re-sorts the visible rows ONCE per data change. The old code called the
     *  sort inside onBindViewHolder/getItemCount, i.e. once per row per pass. */
    private void rebuild() {
        List<Session> out = new ArrayList<>();
        for (Session s : items) {
            if (filter.isEmpty()
                    || (s.title != null && s.title.toLowerCase(Locale.ROOT).contains(filter))) {
                out.add(s);
            }
        }
        java.util.Collections.sort(out, (a, b) -> {
            if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
            return Long.compare(b.updated, a.updated);
        });
        shown.clear();
        shown.addAll(out);
    }

    private void notifyRow(String id) {
        if (id == null || id.isEmpty()) return;
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).id.equals(id)) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    @Override
    public long getItemId(int position) {
        return shown.get(position).id.hashCode();
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
        rebuild();
        notifyDataSetChanged();
    }

    public void setCurrent(String id) {
        String next = id == null ? "" : id;
        if (currentId.equals(next)) return;
        String prev = currentId;
        currentId = next;
        notifyRow(prev);
        notifyRow(currentId);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(ctx).inflate(R.layout.item_session, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Session s = shown.get(position);
        h.title.setText(s.title);
        h.meta.setText(s.pinned ? "📌 " + relTime(s.updated) : relTime(s.updated));
        // the session with a running agent turn gets the live spinner
        boolean busyRow = !busyId.isEmpty() && s.id.equals(busyId);
        h.busy.setVisibility(busyRow ? View.VISIBLE : View.GONE);
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
        return shown.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView meta;
        final android.widget.ProgressBar busy;

        VH(@NonNull View v) {
            super(v);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            busy = v.findViewById(R.id.session_busy);
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

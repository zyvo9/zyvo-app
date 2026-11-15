package com.zyvo.code;

import com.termux.R;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.linkify.LinkifyPlugin;

/**
 * ZCode-style transcript with zyvo CLI behaviours: collapsing "Thought" rows,
 * inline tool rows with running spinner, output preview on tap, effort-aware
 * model chips and "Worked for ..." turn summaries.
 */
public final class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int V_USER = 0;
    private static final int V_ASSISTANT = 1;
    private static final int V_TOOL = 2;
    private static final int V_SUMMARY = 3;
    private static final int V_THOUGHT = 4;
    private static final int V_WORKING = 5;
    private static final int V_TODO = 6;
    private static final int V_NOTICE = 7;
    private static final int V_ERROR = 8;
    private static final int V_CODE = 9;

    static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    /**
     * Payload for a stream tick: only the text of this row changed. RecyclerView
     * hands it to the 3-arg onBindViewHolder so we can skip markdown parsing,
     * the entrance animation, action-bar visibility and a full re-measure on
     * every frame.
     */
    public static final Object PAYLOAD_STREAM = new Object();

    private final Context ctx;
    private final List<ChatItem> items;
    private final Markwon markwon;
    /** Animator scale 0 (developer setting / battery saver) disables our motion. */
    private final boolean reduceMotion;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.getDefault());
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());

    public ChatAdapter(Context ctx, List<ChatItem> items) {
        this.ctx = ctx;
        this.items = items;
        this.markwon = Markwon.builder(ctx)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(ctx))
                .usePlugin(LinkifyPlugin.create())
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void configureTheme(@NonNull MarkwonTheme.Builder builder) {
                        builder.codeTextColor(ContextCompat.getColor(ctx, R.color.code_text));
                        builder.codeBackgroundColor(ContextCompat.getColor(ctx, R.color.code_bg));
                        builder.codeTypeface(Typeface.MONOSPACE);
                        // Grok-style text: headings stay body-size (bold carries
                        // the hierarchy) instead of the giant default h1/h2
                        builder.headingTextSizeMultipliers(new float[]{1f, 1f, 1f, 1f, 1f, 1f});
                        builder.linkColor(ContextCompat.getColor(ctx, R.color.ok));
                    }
                })
                .build();
        float scale = 1f;
        try {
            scale = android.provider.Settings.Global.getFloat(ctx.getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (Throwable ignored) {
        }
        this.reduceMotion = scale == 0f;
    }

    /** Small entrance for a row the first time it appears; never on re-bind. */
    private void animateIn(View v, ChatItem item) {
        if (item == null || item.animated) return;
        item.animated = true;
        if (reduceMotion) return;
        v.setAlpha(0f);
        v.setTranslationY(6f * ctx.getResources().getDisplayMetrics().density);
        v.animate().alpha(1f).translationY(0f).setDuration(170)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    @Override
    public int getItemViewType(int position) {
        switch (items.get(position).kind) {
            case ChatItem.USER:
                return V_USER;
            case ChatItem.SUMMARY:
                return V_SUMMARY;
            case ChatItem.TOOL:
                return V_TOOL;
            case ChatItem.THOUGHT:
                return V_THOUGHT;
            case ChatItem.WORKING:
                return V_WORKING;
            case ChatItem.TODO:
                return V_TODO;
            case ChatItem.NOTICE:
                return V_NOTICE;
            case ChatItem.ERROR:
                return V_ERROR;
            case ChatItem.CODE:
                return V_CODE;
            default:
                return V_ASSISTANT;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(ctx);
        if (viewType == V_USER) {
            return new MessageVH(inf.inflate(R.layout.item_message_user, parent, false));
        }
        if (viewType == V_SUMMARY) {
            return new SummaryVH(inf.inflate(R.layout.item_summary, parent, false));
        }
        if (viewType == V_TOOL) {
            return new ToolVH(inf.inflate(R.layout.item_tool, parent, false));
        }
        if (viewType == V_THOUGHT) {
            return new ThoughtVH(inf.inflate(R.layout.item_thought, parent, false));
        }
        if (viewType == V_WORKING) {
            return new WorkingVH(inf.inflate(R.layout.item_working, parent, false));
        }
        if (viewType == V_TODO) {
            return new TodoVH(inf.inflate(R.layout.item_todo, parent, false));
        }
        if (viewType == V_NOTICE) {
            return new NoticeVH(inf.inflate(R.layout.item_notice, parent, false));
        }
        if (viewType == V_ERROR) {
            return new ErrorVH(inf.inflate(R.layout.item_error, parent, false));
        }
        if (viewType == V_CODE) {
            return new CodeVH(inf.inflate(R.layout.item_code, parent, false));
        }
        return new MessageVH(inf.inflate(R.layout.item_message_assistant, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_STREAM)) {
            ChatItem item = items.get(position);
            if (holder instanceof MessageVH) {
                bindStreamText((MessageVH) holder, item);
                return;
            }
            if (holder instanceof ThoughtVH) {
                bindStreamThought((ThoughtVH) holder, item, position);
                return;
            }
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    /** Cheap path: only the growing text (and the hidden footer) changes. */
    private void bindStreamText(MessageVH h, ChatItem item) {
        if (item.kind != ChatItem.ASSISTANT) {
            bindMessage(h, item);
            return;
        }
        boolean revealing = item.fullText != null && item.fullText.length() > item.shown;
        if (!revealing) {
            // stream finished - one full bind so markdown, copy and duration land
            bindMessage(h, item);
            return;
        }
        h.text.setText(plainMarkdown(item.text));
        if (h.time.getText().length() > 0) h.time.setText("");
        if (h.actions.getVisibility() == View.VISIBLE) h.actions.setVisibility(View.GONE);
    }

    /** Cheap path for a thinking row: only the live 4-line tail moves. */
    private void bindStreamThought(ThoughtVH h, ChatItem item, int position) {
        if (item.kind != ChatItem.THOUGHT || !item.streaming) {
            bindThought(h, item, position);
            return;
        }
        if (TextUtils.isEmpty(item.text)) return;
        if (h.body.getVisibility() != View.VISIBLE) {
            h.body.setVisibility(View.VISIBLE);
            h.body.setMaxLines(4);
            h.body.setEllipsize(TextUtils.TruncateAt.START);
            h.body.setTextColor(ContextCompat.getColor(ctx, R.color.text_disabled));
            h.body.setTextSize(12f);
        }
        String live = tail(item.text, 4);
        if (!live.contentEquals(h.body.getText())) h.body.setText(live);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatItem item = items.get(position);
        if (holder instanceof MessageVH) {
            bindMessage((MessageVH) holder, item);
        } else if (holder instanceof SummaryVH) {
            bindSummary((SummaryVH) holder, item, position);
        } else if (holder instanceof WorkingVH) {
            bindWorking((WorkingVH) holder, item);
        } else if (holder instanceof ToolVH) {
            bindTool((ToolVH) holder, item, position);
        } else if (holder instanceof ThoughtVH) {
            bindThought((ThoughtVH) holder, item, position);
        } else if (holder instanceof TodoVH) {
            bindTodo((TodoVH) holder, item);
        } else if (holder instanceof NoticeVH) {
            bindNotice((NoticeVH) holder, item);
        } else if (holder instanceof ErrorVH) {
            bindError((ErrorVH) holder, item);
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof ThoughtVH) {
            ((ThoughtVH) holder).stopTicker();
            ((ThoughtVH) holder).stopShimmer();
        }
        if (holder instanceof ToolVH) {
            ((ToolVH) holder).stopTicker();
        }
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // ------------------------------------------------------------ messages

    private void bindMessage(MessageVH h, ChatItem item) {
        animateIn(h.itemView, item);
        if (item.kind == ChatItem.USER) {
            markwon.setMarkdown(h.text, item.text == null ? "" : item.text);
            h.time.setText(stamp(item.timeMs));
            h.time.setVisibility(TextUtils.isEmpty(h.time.getText()) ? View.GONE : View.VISIBLE);
            return;
        }

        boolean revealing = item.fullText != null && item.fullText.length() > item.shown;
        if (revealing) {
            // word-by-word reveal: cheap plain text, no markdown re-parse per frame.
            // Markers are stripped so the live view reads clean; the finished text
            // is re-rendered as full markdown (headings, tables, bullets).
            h.text.setText(plainMarkdown(item.text));
        } else {
            markwon.setMarkdown(h.text, item.text == null ? "" : item.text);
        }
        h.text.setTextColor(ContextCompat.getColor(ctx,
                item.error ? R.color.danger : R.color.text_primary));

        boolean hasText = !TextUtils.isEmpty(item.text) && !revealing;
        h.actions.setVisibility(hasText ? View.VISIBLE : View.GONE);
        h.time.setText(revealing ? "" : footer(item));

        if (hasText) {
            h.btnCopy.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager)
                        ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("zyvo", item.text));
                    UiUtil.toast(ctx, ctx.getString(R.string.copied));
                }
            });
            h.btnRegen.setOnClickListener(v -> {
                if (messageActions != null) messageActions.onRegenerate(item);
            });
            h.btnShare.setOnClickListener(v -> shareText(item.text));
            h.btnTts.setOnClickListener(v -> toggleTts(h, item));
        }
    }

    /** Grok-style share action under a reply. */
    private void shareText(String text) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text == null ? "" : text);
        try {
            Intent chooser = Intent.createChooser(send, ctx.getString(R.string.share));
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(chooser);
        } catch (Throwable t) {
            UiUtil.toast(ctx, ctx.getString(R.string.share_failed));
        }
    }

    /**
     * Cheap single-pass markdown stripper for the streaming view: drops emphasis
     * markers, heading hashes and code fences, turns list dashes into bullets.
     * No regex, no allocation per line beyond the result builder.
     */
    private static String plainMarkdown(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        boolean lineStart = true;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (lineStart) {
                int j = i;
                while (j < s.length() && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) j++;
                int h = j;
                while (h < s.length() && s.charAt(h) == '#') h++;
                if (h > j && h < s.length() && s.charAt(h) == ' ') {
                    i = h + 1;
                    continue;
                }
                if (j < s.length() && (s.charAt(j) == '-' || s.charAt(j) == '*' || s.charAt(j) == '+')
                        && j + 1 < s.length() && s.charAt(j + 1) == ' ') {
                    out.append("• ");
                    i = j + 2;
                    lineStart = false;
                    continue;
                }
                if (j < s.length() && s.charAt(j) == '>' && j + 1 < s.length()
                        && s.charAt(j + 1) == ' ') {
                    i = j + 2;
                    continue;
                }
                lineStart = false;
            }
            if (c == '*' || c == '`') {
                i++;
                continue;
            }
            out.append(c);
            lineStart = c == '\n';
            i++;
        }
        return out.toString();
    }

    /**
     * Strips the shell-env preamble the engine prepends to every bash call
     * (`export HOME=...; export PATH=...;`) so the row shows the real command.
     */
    private static String cleanCommand(String cmd) {
        if (cmd == null) return "";
        String s = cmd.trim();
        int guard = 0;
        while (s.startsWith("export ") && guard++ < 4) {
            int sep = s.indexOf(';');
            if (sep < 0) break;
            s = s.substring(sep + 1).trim();
        }
        return s;
    }

    private String stamp(long ms) {
        if (ms <= 0) return "";
        Calendar now = Calendar.getInstance();
        int doy = now.get(Calendar.DAY_OF_YEAR);
        int year = now.get(Calendar.YEAR);
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(ms);
        boolean today = then.get(Calendar.DAY_OF_YEAR) == doy && then.get(Calendar.YEAR) == year;
        return (today ? timeFmt : dayFmt).format(new Date(ms));
    }

    private static String fmtDuration(long ms) {
        if (ms <= 0) return "";
        if (ms < 1000) return ms + "ms";
        if (ms < 60000) return String.format(Locale.US, "%.1fs", ms / 1000.0);
        long m = ms / 60000;
        long s = (ms % 60000) / 1000;
        return m + "m " + s + "s";
    }

    /** Footer under a reply: "12:24 PM · 5s · interrupted". */
    private String footer(ChatItem item) {
        String t = stamp(item.timeMs);
        String d = item.durationMs > 0 ? fmtDuration(item.durationMs) : "";
        String base = TextUtils.isEmpty(t) ? d : (d.isEmpty() ? t : t + " · " + d);
        if (item.interrupted) {
            return base.isEmpty() ? ctx.getString(R.string.interrupted)
                    : base + " · " + ctx.getString(R.string.interrupted);
        }
        return base;
    }

    // ------------------------------------------------------------- thought

    private void bindWorking(WorkingVH h, ChatItem item) {
        animateIn(h.itemView, item);
        h.startMs = item.startMs > 0 ? item.startMs : System.currentTimeMillis();
        h.baseLabel = ctx.getString(R.string.working_bar);
        h.spin.setText(ChatAdapter.FRAMES[0]);
        h.text.setText(TextUtils.isEmpty(item.text)
                ? ctx.getString(R.string.working) : item.text);
        h.startTicker();
    }

    private void bindThought(ThoughtVH h, ChatItem item, int position) {
        animateIn(h.itemView, item);
        boolean hasTitle = !TextUtils.isEmpty(item.title);
        if (item.streaming) {
            h.title.setText(hasTitle
                    ? ctx.getString(R.string.thinking_of, item.title)
                    : ctx.getString(R.string.thinking));
            h.spinner.setVisibility(View.VISIBLE);
            h.spinner.setAlpha(1f);
            h.chevron.setVisibility(View.GONE);
            h.startTicker();
            h.startShimmer();
        } else {
            h.stopTicker();
            h.stopShimmer();
            h.spinner.setVisibility(View.VISIBLE);
            h.spinner.setAlpha(0.75f);
            String d = fmtDuration(item.durationMs);
            h.title.setText(d.isEmpty()
                    ? ctx.getString(R.string.thought)
                    : ctx.getString(R.string.thought_for, d));
            h.chevron.setVisibility(View.VISIBLE);
            h.chevron.setRotation(item.expanded ? 180f : 0f);
        }

        boolean hasBody = !TextUtils.isEmpty(item.text);
        boolean showBody = hasBody && (item.expanded || item.streaming);
        h.body.setVisibility(showBody ? View.VISIBLE : View.GONE);
        if (showBody) {
            if (item.streaming) {
                // live tail of the reasoning tokens — plain text, no markdown parse
                h.body.setText(tail(item.text, 4));
                h.body.setMaxLines(4);
                h.body.setEllipsize(TextUtils.TruncateAt.START);
                h.body.setTextColor(ContextCompat.getColor(ctx, R.color.text_disabled));
                h.body.setTextSize(12f);
            } else {
                h.body.setMaxLines(Integer.MAX_VALUE);
                h.body.setEllipsize(null);
                h.body.setTextSize(13f);
                h.body.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
                markwon.setMarkdown(h.body, item.text);
            }
        }

        boolean toggleable = !item.streaming && hasBody;
        h.header.setOnClickListener(toggleable ? v -> {
            item.expanded = !item.expanded;
            notifyItemChanged(position);
        } : null);
        h.header.setClickable(toggleable);
    }

    // ------------------------------------------------------------------ todo

    private void bindTodo(TodoVH h, ChatItem item) {
        animateIn(h.itemView, item);
        java.util.List<ChatItem.Todo> todos = item.todos;
        if (todos == null || todos.isEmpty()) {
            h.itemView.setVisibility(View.GONE);
            return;
        }
        h.itemView.setVisibility(View.VISIBLE);
        int done = 0;
        for (ChatItem.Todo t : todos) {
            if ("completed".equals(t.status) || "cancelled".equals(t.status)) done++;
        }
        h.count.setText(done + "/" + todos.size());
        h.rows.removeAllViews();
        for (ChatItem.Todo t : todos) {
            boolean completed = "completed".equals(t.status);
            boolean cancelled = "cancelled".equals(t.status);
            boolean active = "in_progress".equals(t.status);
            String glyph = completed ? "✓ " : cancelled ? "✕ " : active ? "◐ " : "○ ";
            TextView row = new TextView(ctx);
            row.setText(glyph + t.content);
            row.setTextSize(12);
            row.setLineSpacing(dp(2), 1f);
            row.setPadding(0, dp(3), 0, dp(3));
            row.setTextColor(ContextCompat.getColor(ctx,
                    completed || cancelled ? R.color.text_disabled
                            : active ? R.color.text_primary : R.color.text_secondary));
            if (completed || cancelled) {
                row.setPaintFlags(row.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            }
            h.rows.addView(row);
        }
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------- summary
    private void bindSummary(SummaryVH h, ChatItem item, int position) {
        h.text.setText(TextUtils.isEmpty(item.text)
                ? ctx.getString(R.string.working) : item.text);
        h.chevron.setRotation(item.expanded ? 180f : 0f);
        h.itemView.setOnClickListener(v -> {
            item.expanded = !item.expanded;
            notifyItemChanged(position);
            int p = position + 1;
            while (p < items.size()) {
                ChatItem c = items.get(p);
                if (c.kind != ChatItem.TOOL || c.group != item.group) break;
                notifyItemChanged(p);
                p++;
            }
        });
    }

    // ---------------------------------------------------------------- tool

    private void bindTool(ToolVH h, ChatItem item, int position) {
        boolean collapsed = false;
        for (int p = position - 1; p >= 0; p--) {
            ChatItem c = items.get(p);
            if (c.kind == ChatItem.SUMMARY) {
                if (c.group == item.group) collapsed = !c.expanded;
                break;
            }
            if (c.kind != ChatItem.TOOL) break;
        }
        h.itemView.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        if (collapsed) {
            h.stopTicker();
            return;
        }
        boolean running = "running".equals(item.status) || "pending".equals(item.status);
        boolean failed = item.error || "error".equals(item.status);

        animateIn(h.itemView, item);
        if (item.hidden) {
            h.itemView.setVisibility(View.GONE);
            h.stopTicker();
            return;
        }

        h.spinner.setVisibility(running ? View.VISIBLE : View.GONE);
        h.icon.setVisibility(running ? View.GONE : View.VISIBLE);
        if (running) h.startTicker();
        else h.stopTicker();

        h.icon.setImageResource(iconFor(item.toolName, item.command));
        h.icon.setColorFilter(ContextCompat.getColor(ctx,
                failed ? R.color.danger : R.color.text_secondary));

        String clean = cleanCommand(item.command);
        String tool = item.toolName == null ? "" : item.toolName.toLowerCase(Locale.ROOT);

        // Reference layout (user screenshot): [icon] Ran command  +1 -1  Title
        h.label.setText(labelFor(item.toolName, item.status));
        h.label.setTextColor(ContextCompat.getColor(ctx,
                failed ? R.color.danger : R.color.text_secondary));

        String desc = !TextUtils.isEmpty(item.label) ? item.label : clean;
        if (TextUtils.isEmpty(desc)) desc = firstLine(item.output);
        desc = desc == null ? "" : desc.trim();
        String title = desc.isEmpty() ? titleFor(item.toolName, clean) : firstLine(desc);
        if (title.length() > 90) title = title.substring(0, 89) + "…";
        h.name.setText(fadedPrefix(title));
        h.name.setTextColor(ContextCompat.getColor(ctx,
                failed ? R.color.danger : R.color.text_primary));

        // +N −N sits IN FRONT of the title for writes/edits.
        int add = 0;
        int del = 0;
        if (!TextUtils.isEmpty(item.diff)) {
            int[] c = diffCounts(item.diff);
            add = c[0];
            del = c[1];
        }
        boolean hasDiff = add + del > 0;
        if (hasDiff) {
            h.diff.setText(diffLabel(add, del));
            h.diff.setVisibility(View.VISIBLE);
        } else if (item.liveLines > 0) {
            h.diff.setText("+" + item.liveLines);
            h.diff.setTextColor(ContextCompat.getColor(ctx, R.color.ok));
            h.diff.setVisibility(View.VISIBLE);
        } else {
            h.diff.setVisibility(View.GONE);
        }

        // Body: WHAT CHANGED, agent-IDE style. Edits show the unified diff with
        // per-line red/green washes (SS-28); writes show the numbered new file
        // in a green panel (SS-26); everything else shows the plain output.
        boolean hasDiffBody = !TextUtils.isEmpty(item.diff);
        boolean hasContent = !TextUtils.isEmpty(item.content) && !hasDiffBody;
        String plainOut = item.output == null ? "" : item.output;
        boolean hasOutput = hasDiffBody || hasContent || !TextUtils.isEmpty(plainOut);
        boolean hasCmd = !TextUtils.isEmpty(clean);
        int total = 0;
        if (hasDiffBody) total = countLines(item.diff);
        else if (hasContent) {
            total = countLines(item.content) + (plainOut.isEmpty() ? 0 : 1 + countLines(plainOut));
        } else if (!plainOut.isEmpty()) total = countLines(plainOut);
        boolean open = item.expanded || item.full || failed || running;
        int maxLines = item.full ? 400 : (item.expanded ? 10 : 3);
        if (failed && !item.expanded) maxLines = 8;

        boolean showBody = open && (hasCmd || hasOutput || hasDiff);
        h.body.setVisibility(showBody ? View.VISIBLE : View.GONE);
        // panel: a write (pure additions) = green box; an edit diff carries its
        // own per-line washes on the neutral body
        if (hasContent && add > 0 && del == 0) {
            h.body.setBackgroundResource(R.drawable.bg_diff_add);
        } else {
            h.body.setBackgroundResource(0);
        }

        // one card, one body: Terminal rows show ONLY the output on expand (the
        // command lives in the header title); write/edit show diff/numbered content
        h.cmdBox.setVisibility(View.GONE);
        h.cmdOutput.setVisibility(View.GONE);
        h.expand.setVisibility(View.GONE);
        h.cmdFullToggle.setVisibility(View.GONE);

        h.output.setVisibility(open && hasOutput ? View.VISIBLE : View.GONE);
        if (open && hasDiffBody) {
            // edit: unified diff body (SS-28), syntax colored
            h.output.setText(diffSpans(item.diff));
            h.output.setMaxLines(maxLines);
            h.output.setEllipsize(null);
        } else if (open && hasContent) {
            // write: IDE-style numbered content (SS-26 look), syntax colored
            h.output.setText(numberedBody(item.content,
                    plainOut.isEmpty() ? "" : plainOut));
            h.output.setMaxLines(maxLines);
            h.output.setEllipsize(null);
        } else if (open && !plainOut.isEmpty()) {
            boolean tailMode = running && !item.expanded && !item.full && total > maxLines;
            h.output.setText(tailMode ? tail(plainOut, maxLines)
                    : (item.full ? cap(plainOut, 400) : preview(plainOut, maxLines)));
            h.output.setMaxLines(maxLines);
            h.output.setEllipsize(item.full ? null : TextUtils.TruncateAt.END);
        }

        boolean overflow = total > maxLines;
        boolean showMeta = open && overflow;
        h.expandOut.setVisibility(showMeta ? View.VISIBLE : View.GONE);
        if (showMeta) {
            h.expandOut.setText(item.full
                    ? ctx.getString(R.string.tap_collapse)
                    : ctx.getString(R.string.lines_tap_expand, total));
        }

        boolean canOpen = hasCmd || hasOutput || hasDiff;
        h.chevron.setVisibility(canOpen ? View.VISIBLE : View.GONE);
        h.chevron.setRotation(open ? 180f : 0f);

        // the unified diff now lives in the main body — the old chip-toggled
        // second diff box stays off
        h.diffBody.setVisibility(View.GONE);

        h.diff.setOnClickListener(null);
        h.diff.setClickable(false);

        h.expand.setOnClickListener(v -> {
            nextStage(item);
            notifyItemChanged(position);
        });
        h.expandOut.setOnClickListener(v -> {
            nextStage(item);
            notifyItemChanged(position);
        });

        h.card.setOnClickListener(v -> {
            nextStage(item);
            notifyItemChanged(position);
        });
    }

    /** Codex-style verb for the row: "Ran command", "Writing file", ... */
    private static String labelFor(String tool, String status) {
        String n = tool == null ? "" : tool.toLowerCase(Locale.ROOT);
        boolean done = "completed".equals(status);
        if (n.contains("bash") || n.contains("shell") || n.contains("exec")
                || n.contains("terminal")) {
            return "Terminal";
        }
        if (n.contains("write")) return done ? "Write file" : "Writing file";
        if (n.contains("patch")) return done ? "Applied patch" : "Applying patch";
        if (n.contains("edit")) return done ? "Edited file" : "Editing file";
        if (n.equals("read") || n.startsWith("read_") || n.endsWith("read")) {
            return done ? "Read file" : "Reading file";
        }
        if (n.contains("grep")) return done ? "Searched code" : "Searching code";
        if (n.contains("glob") || n.contains("find")) return done ? "Found files" : "Finding files";
        if (n.contains("list")) return done ? "Listed files" : "Listing files";
        if (n.contains("webfetch") || n.contains("web") || n.contains("http")) {
            return done ? "Fetched page" : "Fetching page";
        }
        if (n.contains("task") || n.contains("agent")) return "Subagent";
        if (n.contains("todo")) return "Tasks";
        return done ? "Used tool" : "Using tool";
    }

    /** 10 lines → everything → closed. */
    private static void nextStage(ChatItem item) {
        if (!item.expanded) {
            item.expanded = true;
            item.full = false;
        } else if (!item.full) {
            item.full = true;
        } else {
            item.expanded = false;
            item.full = false;
        }
    }

    private static int[] diffCounts(String diff) {
        int add = 0;
        int del = 0;
        for (String line : diff.split("\n")) {
            if (line.startsWith("+++") || line.startsWith("---")) continue;
            if (line.startsWith("+")) add++;
            else if (line.startsWith("-")) del++;
        }
        return new int[]{add, del};
    }

    private SpannableString diffLabel(int add, int del) {
        SpannableString span = new SpannableString(
                (add > 0 ? " +" + add : "") + (del > 0 ? " −" + del : ""));
        if (add > 0) {
            span.setSpan(new ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.ok)),
                    0, (" +" + add).length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (del > 0) {
            int from = (add > 0 ? (" +" + add).length() : 0);
            span.setSpan(new ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.danger)),
                    from, span.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return span;
    }

    /** The changed lines themselves: added green, removed red, hunk headers dim. */
    /** IDE-style diff: line-number gutter, faint green/red row washes like the
     *  desktop agent UI. Hunk headers give real new-file line numbers. */
    private CharSequence diffSpans(String diff) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        int colorAdd = ContextCompat.getColor(ctx, R.color.ok);
        int colorDel = ContextCompat.getColor(ctx, R.color.danger);
        int colorCtx = ContextCompat.getColor(ctx, R.color.text_secondary);
        int dim = ContextCompat.getColor(ctx, R.color.text_disabled);
        final int bgAdd = 0x66346E3F; // solid green wash (SS-28 look)
        final int bgDel = 0x66763A3A; // solid red wash
        final int numW = 4;
        int lines = 0;
        int fileLine = 1;
        for (String line : diff.split("\n")) {
            if (lines >= 300) {
                sb.append("…");
                break;
            }
            if (line.startsWith("+++") || line.startsWith("---")) continue;
            int start = sb.length();
            if (line.startsWith("@@")) {
                int nl = hunkStart(line);
                if (nl > 0) fileLine = nl;
                sb.append(line);
                sb.setSpan(new ForegroundColorSpan(dim), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append('\n');
                lines++;
                continue;
            }
            boolean add = line.startsWith("+");
            boolean del = line.startsWith("-");
            String text = add || del ? line.substring(1) : line;
            String num = String.valueOf(fileLine);
            for (int p = num.length(); p < numW; p++) sb.append(' ');
            sb.append(num).append("  ");
            sb.setSpan(new ForegroundColorSpan(dim), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int tstart = sb.length();
            sb.append(text);
            sb.setSpan(new ForegroundColorSpan(add ? colorAdd : del ? colorDel : colorCtx),
                    tstart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (add || del) {
                sb.setSpan(new android.text.style.BackgroundColorSpan(add ? bgAdd : bgDel),
                        start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            applySyntax(sb, tstart, sb.length());
            sb.append('\n');
            if (!del) fileLine++;
            lines++;
        }
        return sb;
    }

    /** First new-file line number of a "@@ -a,b +c,d @@" hunk header. */
    private static int hunkStart(String hunk) {
        try {
            int plus = hunk.indexOf('+');
            if (plus < 0) return -1;
            int end = plus + 1;
            while (end < hunk.length() && Character.isDigit(hunk.charAt(end))) end++;
            return Integer.parseInt(hunk.substring(plus + 1, end));
        } catch (Exception e) {
            return -1;
        }
    }

    // -------------------------------------------------------- syntax colors

    private static final String[] SYNTAX_KEYWORDS = {
            "import", "package", "public", "private", "protected", "class", "void",
            "static", "final", "return", "if", "else", "while", "for", "int",
            "float", "double", "boolean", "new", "def", "function", "var", "val",
            "const", "let", "true", "false", "null", "shape", "solid", "corners",
            "stroke", "layer-list", "xmlns", "android", "resources", "item", "color"
    };

    /** IDE token colors over the appended text (SS-31 look): comments green,
     *  strings orange, tags/keywords purple-teal, numbers pale. */
    private void applySyntax(android.text.SpannableStringBuilder sb, int start, int end) {
        if (end - start > 8000) return; // guard pathological lines
        final int kw = 0xFFC586C0;
        final int str = 0xFFCE9178;
        final int com = 0xFF6A9955;
        final int tag = 0xFF4EC9B0;
        final int num = 0xFFB5CEA8;
        int i = start;
        while (i < end) {
            char c = sb.charAt(i);
            if (c == '#' || (c == '/' && i + 1 < end && sb.charAt(i + 1) == '/')) {
                int j = i;
                while (j < end && sb.charAt(j) != '\n') j++;
                sb.setSpan(new ForegroundColorSpan(com), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = j;
                continue;
            }
            if (c == '<' && i + 3 < end && sb.charAt(i + 1) == '!'
                    && sb.charAt(i + 2) == '-' && sb.charAt(i + 3) == '-') {
                int j = i + 4;
                while (j < end && !(sb.charAt(j) == '-' && j + 1 < end
                        && sb.charAt(j + 1) == '-' && sb.charAt(j + 2) == '>')) j++;
                j = Math.min(j + 3, end);
                sb.setSpan(new ForegroundColorSpan(com), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = j;
                continue;
            }
            if (c == '<') {
                int j = i;
                while (j < end && sb.charAt(j) != '>') j++;
                j = Math.min(j + 1, end);
                sb.setSpan(new ForegroundColorSpan(tag), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = j;
                continue;
            }
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < end && sb.charAt(j) != c && sb.charAt(j) != '\n') j++;
                j = Math.min(j + 1, end);
                sb.setSpan(new ForegroundColorSpan(str), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = j;
                continue;
            }
            if (Character.isLetter(c)) {
                int j = i;
                while (j < end && (Character.isLetterOrDigit(sb.charAt(j)) || sb.charAt(j) == '_'
                        || sb.charAt(j) == '-')) j++;
                String word = sb.subSequence(i, j).toString();
                boolean hit = false;
                for (String k : SYNTAX_KEYWORDS) {
                    if (k.equalsIgnoreCase(word)) {
                        hit = true;
                        break;
                    }
                }
                if (hit) {
                    sb.setSpan(new ForegroundColorSpan(kw), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (word.matches("\\d+(\\.\\d+)?")) {
                    sb.setSpan(new ForegroundColorSpan(num), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                i = j;
                continue;
            }
            i++;
        }
    }

    /** IDE-style content body: right-aligned line-number gutter + the written
     *  file, with the tool output trailing in gray (agent-IDE look). */
    private CharSequence numberedBody(String content, String trailing) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        int dim = ContextCompat.getColor(ctx, R.color.text_disabled);
        String[] lines = content.split("\n", -1);
        int cap = Math.min(lines.length, 400);
        int numW = String.valueOf(cap).length();
        for (int i = 0; i < cap; i++) {
            int start = sb.length();
            String num = String.valueOf(i + 1);
            for (int p = num.length(); p < numW; p++) sb.append(' ');
            sb.append(num).append("  ");
            sb.setSpan(new ForegroundColorSpan(dim), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int tstart = sb.length();
            sb.append(lines[i]);
            applySyntax(sb, tstart, sb.length());
            sb.append('\n');
        }
        if (!TextUtils.isEmpty(trailing)) {
            int start = sb.length();
            sb.append('\n').append(trailing);
            sb.setSpan(new ForegroundColorSpan(dim), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private static int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private static String preview(String s, int maxLines) {
        String[] lines = s.split("\n", -1);
        if (lines.length <= maxLines) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /** Last {@code n} lines — what a running command is printing right now. */
    private static String tail(String s, int n) {
        if (s == null || s.isEmpty()) return "";
        int idx = s.length();
        for (int count = 0; count < n; count++) {
            int nl = s.lastIndexOf('\n', idx - 1);
            if (nl < 0) return s;
            idx = nl;
        }
        return s.substring(idx + 1);
    }

    private static String cap(String s, int maxLines) {
        return preview(s, maxLines);
    }

    /** File rows keep the full path but the device preamble renders faint gray,
     *  so the real folder/file part pops like in an agent IDE. */
    private CharSequence fadedPrefix(String s) {
        if (s == null) return "";
        String[] marks = {"/storage/emulated/0/", "/storage/emulated/",
                "/data/data/com.termux/files/home/", "/data/data/com.termux/files/",
                "/data/data/com.termux/"};
        for (String m : marks) {
            int i = s.indexOf(m);
            if (i >= 0) {
                SpannableString sp = new SpannableString(s);
                sp.setSpan(new ForegroundColorSpan(
                        ContextCompat.getColor(ctx, R.color.text_faint)),
                        i, i + m.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                return sp;
            }
        }
        return s;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static boolean isShell(String tool, String command) {
        String n = tool == null ? "" : tool.toLowerCase(Locale.ROOT);
        if (n.contains("bash") || n.contains("shell") || n.contains("exec")) return true;
        return n.isEmpty() && command != null && !command.isEmpty();
    }

    private static String titleFor(String tool, String command) {
        String n = tool == null ? "" : tool.toLowerCase(Locale.ROOT);
        if (n.contains("todo")) return "Tasks";
        if (n.contains("bash") || n.contains("shell") || n.contains("exec")
                || n.contains("terminal")) return "Terminal";
        if (n.equals("read") || n.startsWith("read_")) return "Read";
        if (n.contains("write")) return "Write";
        if (n.contains("edit") || n.contains("patch")) return "Edit";
        if (n.contains("grep") || n.contains("search") || n.contains("find")) return "Search";
        if (n.contains("glob")) return "Glob";
        if (n.contains("web") || n.contains("fetch") || n.contains("http")) return "Web";
        if (n.contains("task") || n.contains("agent") || n.contains("subagent")) return "Agent";
        if (!n.isEmpty()) return Character.toUpperCase(n.charAt(0)) + n.substring(1);
        if (command != null && !command.isEmpty()) return "Terminal";
        return "Tool";
    }

    /** Lucide glyph per tool class (Codex/Grok-style outlined glyphs). */
    private static int iconFor(String tool, String command) {
        String n = tool == null ? "" : tool.toLowerCase(Locale.ROOT);
        if (n.contains("bash") || n.contains("shell") || n.contains("exec")
                || n.contains("terminal")) return R.drawable.ic_lc_square_terminal;
        if (n.contains("todo")) return R.drawable.ic_lc_list_checks;
        if (n.contains("glob") || n.contains("find")) return R.drawable.ic_lc_folder_search;
        if (n.equals("read") || n.startsWith("read_") || n.endsWith("read")) {
            return R.drawable.ic_lc_file_text;
        }
        if (n.contains("write")) return R.drawable.ic_lc_file_pen;
        if (n.contains("edit") || n.contains("patch")) return R.drawable.ic_lc_pen_line;
        if (n.contains("web") || n.contains("fetch") || n.contains("http")) {
            return R.drawable.ic_lc_globe;
        }
        if (n.contains("grep") || n.contains("search")) return R.drawable.ic_lc_search;
        if (n.contains("diff")) return R.drawable.ic_lc_file_diff;
        if (n.contains("task") || n.contains("agent") || n.contains("subagent")) {
            return R.drawable.ic_lc_bot;
        }
        if (n.contains("skill")) return R.drawable.ic_lc_sparkles;
        if (command != null && !command.isEmpty()) return R.drawable.ic_lc_square_terminal;
        return R.drawable.ic_lc_wrench;
    }

    // ------------------------------------------------------------ holders


    // ------------------------------------------------------------- read aloud

    private static android.speech.tts.TextToSpeech tts;
    private static boolean ttsReady;
    private static String ttsKey;

    /** Reply read-aloud (ChatGPT-style speaker): tap to play, tap again to stop. */
    private void toggleTts(MessageVH h, ChatItem item) {
        if (tts != null && ttsReady && item.key.equals(ttsKey)) {
            tts.stop();
            ttsKey = null;
            h.btnTts.setImageTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.text_secondary)));
            return;
        }
        if (tts == null) {
            tts = new android.speech.tts.TextToSpeech(ctx, status -> {
                ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS;
                if (ttsReady) {
                    tts.setLanguage(new Locale("hin", "IND")); // Banglish latin reads fine
                    if (h != null) speakNow(h, item);
                }
            });
        } else if (ttsReady) {
            speakNow(h, item);
        }
    }

    private void speakNow(MessageVH h, ChatItem item) {
        if (tts == null || !ttsReady) return;
        String plain = plainMarkdown(item.text == null ? "" : item.text);
        if (plain.length() > 4000) plain = plain.substring(0, 4000);
        tts.setSpeechRate(1.15f);
        tts.speak(plain, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, item.key);
        ttsKey = item.key;
        h.btnTts.setImageTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.ok)));
    }

    static final class MessageVH extends RecyclerView.ViewHolder {
        final TextView text;
        final View actions;
        final TextView time;
        final ImageButton btnCopy;
        final ImageButton btnRegen;
        final ImageButton btnShare;
        final ImageButton btnTts;

        MessageVH(@NonNull View v) {
            super(v);
            text = v.findViewById(R.id.text);
            actions = v.findViewById(R.id.actions);
            time = v.findViewById(R.id.time);
            btnCopy = v.findViewById(R.id.btn_copy);
            btnRegen = v.findViewById(R.id.btn_regen);
            btnShare = v.findViewById(R.id.btn_share);
            btnTts = v.findViewById(R.id.btn_tts);
        }
    }

    /** Fragment hook for the action row under a reply. */
    public interface MessageActions {
        /** Regenerate the reply at {@code item} (re-sends the prompt above it). */
        void onRegenerate(ChatItem item);
    }

    private MessageActions messageActions;

    public void setMessageActions(MessageActions actions) {
        this.messageActions = actions;
    }

    static final class ErrorVH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView text;

        ErrorVH(@NonNull View v) {
            super(v);
            title = v.findViewById(R.id.error_title);
            text = v.findViewById(R.id.error_text);
        }
    }

    static final class SummaryVH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView text;
        final ImageView chevron;

        SummaryVH(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.summary_icon);
            text = v.findViewById(R.id.summary_text);
            chevron = v.findViewById(R.id.summary_chevron);
        }
    }

    abstract static class TickingVH extends RecyclerView.ViewHolder {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private int frame;
        private final Runnable ticker = new Runnable() {
            @Override
            public void run() {
                onFrame(ChatAdapter.FRAMES[frame = (frame + 1) % ChatAdapter.FRAMES.length]);
                handler.postDelayed(this, 80);
            }
        };

        TickingVH(@NonNull View v) {
            super(v);
        }

        abstract void onFrame(String frame);

        void startTicker() {
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        }

        void stopTicker() {
            handler.removeCallbacks(ticker);
        }
    }

    static final class WorkingVH extends TickingVH {
        final TextView spin;
        final TextView text;
        long startMs;

        WorkingVH(@NonNull View v) {
            super(v);
            spin = v.findViewById(R.id.working_spin);
            text = v.findViewById(R.id.working_text);
        }

        String baseLabel = "Working";

        @Override
        void onFrame(String frame) {
            spin.setText(frame);
            if (startMs > 0) {
                long sec = Math.max(1, (System.currentTimeMillis() - startMs) / 1000);
                text.setText(baseLabel + " · " + sec + "s");
            }
        }
    }

    static final class NoticeVH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView body;
        final TextView retry;

        NoticeVH(@NonNull View v) {
            super(v);
            title = v.findViewById(R.id.notice_title);
            body = v.findViewById(R.id.notice_body);
            retry = v.findViewById(R.id.notice_retry);
        }
    }

    /** Fragment hook so a notice can offer "Retry" without knowing about items. */
    public interface NoticeAction {
        void onRetry();
    }

    private NoticeAction noticeAction;

    public void setNoticeAction(NoticeAction action) {
        this.noticeAction = action;
    }

    private void bindNotice(NoticeVH h, ChatItem item) {
        animateIn(h.itemView, item);
        h.title.setText(item.title);
        h.body.setText(item.text);
        if (noticeAction != null) {
            h.retry.setVisibility(View.VISIBLE);
            h.retry.setOnClickListener(v -> noticeAction.onRetry());
        } else {
            h.retry.setVisibility(View.GONE);
        }
    }

    /** Engine error panel under the reply (left red border + title + message). */
    private void bindError(ErrorVH h, ChatItem item) {
        animateIn(h.itemView, item);
        h.title.setText(TextUtils.isEmpty(item.title)
                ? ctx.getString(R.string.error_panel_title) : item.title);
        h.text.setText(item.text == null ? "" : item.text);
    }

    /** ChatGPT-style code card: lang label + copy + expand + syntax body. */
    private void bindCode(CodeVH h, ChatItem item) {
        animateIn(h.itemView, item);
        h.tag.setText("‹/›");
        h.lang.setText(item.lang.isEmpty() ? "code" : item.lang);
        h.body.setText(syntaxBlock(item.text));
        h.body.setVisibility(View.VISIBLE);
        h.body.setMaxLines(14);
        h.body.setEllipsize(null);
        h.copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("code", item.text));
                UiUtil.toast(ctx, ctx.getString(R.string.copied));
            }
        });
        h.expand.setOnClickListener(v -> showCodeDialog(item.lang.isEmpty() ? "code" : item.lang, item.text));
    }

    /** Full-screen viewer for a long code block (copy available there too). */
    private void showCodeDialog(String lang, String code) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
        TextView tv = new TextView(ctx);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(12);
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        tv.setPadding(dp(12), dp(12), dp(12), dp(12));
        tv.setTextIsSelectable(true);
        tv.setText(syntaxBlock(code));
        scroll.addView(tv);
        new androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(lang)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.copy, (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("code", code));
                        UiUtil.toast(ctx, ctx.getString(R.string.copied));
                    }
                })
                .show();
    }

    /** Colored code body (plain syntax span, no markdown). */
    private CharSequence syntaxBlock(String code) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(code == null ? "" : code);
        applySyntax(sb, 0, sb.length());
        return sb;
    }

    static final class TodoVH extends RecyclerView.ViewHolder {
        final TextView count;
        final android.widget.LinearLayout rows;

        TodoVH(@NonNull View v) {
            super(v);
            count = v.findViewById(R.id.todo_count);
            rows = v.findViewById(R.id.todo_rows);
        }
    }

    static final class ThoughtVH extends TickingVH {
        final View header;
        final ImageView spinner;
        final TextView title;
        final TextView body;
        final ImageView chevron;
        private final int baseColor;
        private android.animation.ValueAnimator shimmer;
        private android.graphics.LinearGradient gradient;
        private final android.graphics.Matrix matrix = new android.graphics.Matrix();

        ThoughtVH(@NonNull View v) {
            super(v);
            header = v.findViewById(R.id.thought_header);
            spinner = v.findViewById(R.id.thought_spinner);
            title = v.findViewById(R.id.thought_title);
            body = v.findViewById(R.id.thought_body);
            chevron = v.findViewById(R.id.thought_chevron);
            baseColor = title.getCurrentTextColor();
        }

        /** Grok-style shimmer: a soft highlight runs across "Thinking…". */
        void startShimmer() {
            if (shimmer != null) return;
            float d = title.getResources().getDisplayMetrics().density;
            final int w = (int) (150 * d);
            if (gradient == null) {
                gradient = new android.graphics.LinearGradient(0f, 0f, w, 0f,
                        new int[]{0x666B6B6B, 0xFFFFFFFF, 0x666B6B6B},
                        new float[]{0f, 0.5f, 1f},
                        android.graphics.Shader.TileMode.CLAMP);
            }
            title.getPaint().setShader(gradient);
            shimmer = android.animation.ValueAnimator.ofFloat(-w, w * 1.4f);
            shimmer.setDuration(1250);
            shimmer.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            shimmer.setInterpolator(new android.view.animation.LinearInterpolator());
            shimmer.addUpdateListener(a -> {
                matrix.setTranslate((float) a.getAnimatedValue(), 0f);
                gradient.setLocalMatrix(matrix);
                title.invalidate();
            });
            shimmer.start();
        }

        void stopShimmer() {
            if (shimmer != null) {
                shimmer.cancel();
                shimmer = null;
            }
            if (title.getPaint().getShader() != null) {
                title.getPaint().setShader(null);
                title.setTextColor(baseColor);
                title.invalidate();
            }
        }

        @Override
        void onFrame(String frame) {
            // soft pulse instead of a braille spinner: the brain breathes while it thinks
            double t = (System.currentTimeMillis() % 1400) / 1400.0;
            float pulse = (float) (0.55 + 0.45 * (0.5 - 0.5 * Math.cos(2 * Math.PI * t)));
            spinner.setAlpha(pulse);
        }
    }

    static final class CodeVH extends RecyclerView.ViewHolder {
        final TextView tag;
        final TextView lang;
        final ImageView copy;
        final ImageView expand;
        final TextView body;

        CodeVH(@NonNull View v) {
            super(v);
            tag = v.findViewById(R.id.code_tag);
            lang = v.findViewById(R.id.code_lang);
            copy = v.findViewById(R.id.code_copy);
            expand = v.findViewById(R.id.code_expand);
            body = v.findViewById(R.id.code_body);
        }
    }

    static final class ToolVH extends TickingVH {
        final View card;
        final View body;
        final TextView spinner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        final View cmdBox;
        final TextView cmdLine;
        final TextView cmdFullToggle;
        final TextView cmdOutput;
        final TextView diff;
        final TextView output;
        final TextView expand;
        final TextView expandOut;
        final TextView diffBody;
        final ImageView chevron;

        ToolVH(@NonNull View v) {
            super(v);
            card = v.findViewById(R.id.tool_card);
            body = v.findViewById(R.id.tool_body);
            spinner = v.findViewById(R.id.tool_spinner);
            icon = v.findViewById(R.id.tool_icon);
            label = v.findViewById(R.id.tool_label);
            name = v.findViewById(R.id.tool_name);
            cmdBox = v.findViewById(R.id.cmd_box);
            cmdLine = v.findViewById(R.id.cmd_line);
            cmdFullToggle = v.findViewById(R.id.cmd_full_toggle);
            cmdOutput = v.findViewById(R.id.cmd_output);
            diff = v.findViewById(R.id.diff);
            output = v.findViewById(R.id.output);
            expand = v.findViewById(R.id.expand);
            expandOut = v.findViewById(R.id.expand_out);
            diffBody = v.findViewById(R.id.diff_body);
            chevron = v.findViewById(R.id.chevron);
            spinner.setText(ChatAdapter.FRAMES[0]);
        }

        @Override
        void onFrame(String frame) {
            spinner.setText(frame);
        }
    }
}

package com.zyvo.code;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Scroll container for tool-card bodies.
 *
 * A long command output must NOT push the whole chat away: the card keeps a
 * fixed share of the screen and the text scrolls INSIDE it. The cap is derived
 * from the screen height, so it behaves the same on every phone size.
 *
 * Deliberately extends the FRAMEWORK ScrollView, not androidx NestedScrollView:
 * the NestedScrollView class merged into a recent APK carried a broken
 * constructor (NestedScrollingChildHelper null -> instant InflateException on
 * every tool card), and these boxes disable nested scrolling anyway
 * (nestedScrollingEnabled=false in the layouts) so the box claims its own
 * drags instead of passing them to the chat list.
 */
public class MaxHeightScrollView extends ScrollView {

    /** Share of the screen height the body may take. */
    private float fraction = 0.40f;
    private int maxPx;

    public MaxHeightScrollView(@NonNull Context context) {
        this(context, null);
    }

    public MaxHeightScrollView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MaxHeightScrollView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float d = context.getResources().getDisplayMetrics().density;
        maxPx = (int) (330 * d);
        setFillViewport(false);
        setScrollBarDefaultDelayBeforeFade(1200);
    }

    public void setMaxFraction(float f) {
        fraction = f;
        requestLayout();
    }

    /**
     * Exact pixel cap (e.g. exactly N code lines). Wins over the screen fraction
     * so a code box keeps the same height no matter how tall the phone is.
     */
    public void setMaxPx(int px) {
        if (px > 0) maxPx = px;
        fraction = 1f;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int cap = (int) (getResources().getDisplayMetrics().heightPixels * fraction);
        cap = Math.min(maxPx, cap);

        View child = getChildAt(0);
        if (child == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        // THE BUG THIS FIXES: capping the CHILD's measure spec (the old code
        // replaced heightMeasureSpec with AT_MOST(cap) before super) made the
        // child measure exactly cap-tall too -> child height == viewport ->
        // NOTHING to scroll and lines past 12 were unreachable. The child must
        // measure its FULL natural height (UNSPECIFIED); only OUR viewport is
        // capped - that is what makes the box scrollable.
        measureChildWithMargins(child, widthMeasureSpec, 0,
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0);

        MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
        int natural = child.getMeasuredHeight() + getPaddingTop() + getPaddingBottom()
                + lp.topMargin + lp.bottomMargin;
        int my = Math.min(natural, cap);

        int w = child.getMeasuredWidth() + getPaddingLeft() + getPaddingRight()
                + lp.leftMargin + lp.rightMargin;
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            w = resolveSize(w, widthMeasureSpec);
        }
        setMeasuredDimension(Math.max(w, getSuggestedMinimumWidth()), my);
    }

    /** True when the content is taller than the visible cap (used for a hint). */
    public boolean isOverflowing() {
        View child = getChildAt(0);
        return child != null && child.getMeasuredHeight() > getMeasuredHeight();
    }
}

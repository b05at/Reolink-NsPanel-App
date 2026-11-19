package at.reolinkpanel.viewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

/**
 * Breite, greifbare Scrollleiste für ScrollViews – auf dem kleinen Panel lässt sich so
 * schnell durch lange Einstellungen springen (Daumen ziehen oder auf die Leiste tippen).
 */
public class ScrollRail extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private ScrollView scroll;
    private boolean dragging;
    private float grabOffset;

    public ScrollRail(Context c) { this(c, null); }

    public ScrollRail(Context c, AttributeSet a) {
        super(c, a);
        track.setColor(0x22FFFFFF);
        thumb.setColor(0xCC4FA3F7);   // Akzentblau
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    /** Mit der ScrollView verbinden. */
    public void attach(ScrollView sv) {
        scroll = sv;
        sv.setVerticalScrollBarEnabled(false);
        sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int x, int y, int ox, int oy) { invalidate(); }
        });
        sv.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() { invalidate(); }
        });
    }

    private int range() {
        if (scroll == null || scroll.getChildCount() == 0) return 0;
        return Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight());
    }

    private float thumbHeight() {
        if (scroll == null || scroll.getChildCount() == 0) return getHeight();
        float content = Math.max(1, scroll.getChildAt(0).getHeight());
        return Math.max(dp(48), getHeight() * (scroll.getHeight() / content));
    }

    private float thumbTop() {
        int range = range();
        if (range == 0) return 0;
        return (getHeight() - thumbHeight()) * (scroll.getScrollY() / (float) range);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (range() <= 0) return;   // nichts zu scrollen
        float w = getWidth();
        float barW = dp(dragging ? 10 : 7);
        float cx = w / 2f;
        r.set(cx - dp(2), dp(4), cx + dp(2), getHeight() - dp(4));
        c.drawRoundRect(r, dp(2), dp(2), track);
        float top = thumbTop();
        r.set(cx - barW / 2, top + dp(2), cx + barW / 2, top + thumbHeight() - dp(2));
        c.drawRoundRect(r, barW / 2, barW / 2, thumb);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (scroll == null || range() <= 0) return false;
        float y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                float top = thumbTop();
                // Auf den Daumen getippt: relativ ziehen, sonst Daumen mittig unter den Finger
                grabOffset = (y >= top && y <= top + thumbHeight()) ? y - top : thumbHeight() / 2f;
                // fall through
            case MotionEvent.ACTION_MOVE:
                float avail = getHeight() - thumbHeight();
                float pos = avail <= 0 ? 0 : (y - grabOffset) / avail;
                pos = Math.max(0f, Math.min(1f, pos));
                scroll.scrollTo(0, Math.round(pos * range()));
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}

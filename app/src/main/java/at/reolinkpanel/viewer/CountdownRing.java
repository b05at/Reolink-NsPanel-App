package at.reolinkpanel.viewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/** Ring um „Auflegen“, der sich über die Zeit bis zum automatischen Schließen leert. */
public class CountdownRing extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private long start, duration;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            invalidate();
            if (duration > 0 && SystemClock.uptimeMillis() - start < duration) postDelayed(this, 200);
        }
    };

    public CountdownRing(Context c) { this(c, null); }

    public CountdownRing(Context c, AttributeSet a) {
        super(c, a);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xFFFFFFFF);
        paint.setStrokeWidth(3 * getResources().getDisplayMetrics().density);
    }

    /** Countdown (neu) starten; durationMs 0 blendet den Ring aus. */
    public void start(long durationMs) {
        removeCallbacks(tick);
        start = SystemClock.uptimeMillis();
        duration = durationMs;
        post(tick);
    }

    public void stop() {
        removeCallbacks(tick);
        duration = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (duration <= 0) return;
        float left = 1f - Math.min(1f, (SystemClock.uptimeMillis() - start) / (float) duration);
        float inset = paint.getStrokeWidth();
        r.set(inset, inset, getWidth() - inset, getHeight() - inset);
        c.drawArc(r, -90, 360f * left, false, paint);
    }
}

package at.reolinkpanel.viewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Steuerkreuz für Schwenken/Neigen. Solange der Finger auf einer Richtung liegt,
 * wird diese gemeldet; beim Loslassen kommt null (= Stopp).
 */
public class DPadView extends View {

    public interface Listener { void onDirection(String op); }

    public static final String UP = "Up", DOWN = "Down", LEFT = "Left", RIGHT = "Right";

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint active = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint center = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();

    private Listener listener;
    private String current;

    public DPadView(Context c) { this(c, null); }

    public DPadView(Context c, AttributeSet a) {
        super(c, a);
        bg.setColor(0xB31B2129);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(2));
        ring.setColor(0x669AA4B2);
        arrow.setColor(0xFFF2F4F7);
        active.setColor(0xCC4FA3F7);   // Blau – kein Rot/Grün
        center.setColor(0xFF2C3644);
    }

    public void setListener(Listener l) { listener = l; }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    protected void onMeasure(int w, int h) {
        int size = Math.min(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - dp(2);
        c.drawCircle(cx, cy, r, bg);

        // Hervorhebung der aktiven Richtung als Kreissegment
        if (current != null) {
            float start = RIGHT.equals(current) ? -45 : DOWN.equals(current) ? 45 : LEFT.equals(current) ? 135 : 225;
            oval.set(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(oval, start, 90, true, active);
        }
        c.drawCircle(cx, cy, r, ring);

        float a = r * 0.22f;          // Pfeilgröße
        float d = r * 0.66f;          // Abstand vom Mittelpunkt
        tri(c, cx, cy - d, 0, -1, a);
        tri(c, cx, cy + d, 0, 1, a);
        tri(c, cx - d, cy, -1, 0, a);
        tri(c, cx + d, cy, 1, 0, a);

        c.drawCircle(cx, cy, r * 0.22f, center);
    }

    /** Dreieck, Spitze in Richtung (dx,dy). */
    private void tri(Canvas c, float x, float y, int dx, int dy, float a) {
        path.reset();
        path.moveTo(x + dx * a, y + dy * a);
        path.lineTo(x - dx * a * 0.6f + dy * a, y - dy * a * 0.6f + dx * a);
        path.lineTo(x - dx * a * 0.6f - dy * a, y - dy * a * 0.6f - dx * a);
        path.close();
        c.drawPath(path, arrow);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
                set(direction(e.getX(), e.getY()));
                return true;
            case MotionEvent.ACTION_UP:
                performClick();
                // fall through
            case MotionEvent.ACTION_CANCEL:
                set(null);
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    private String direction(float x, float y) {
        float dx = x - getWidth() / 2f, dy = y - getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f;
        if (Math.hypot(dx, dy) < r * 0.22f) return null;   // Mitte = Stopp
        if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? RIGHT : LEFT;
        return dy > 0 ? DOWN : UP;
    }

    private void set(String dir) {
        if (dir == null ? current == null : dir.equals(current)) return;
        current = dir;
        invalidate();
        if (listener != null) listener.onDirection(dir);
    }

    /** Bewegung abbrechen (z. B. beim Ausblenden). */
    public void reset() { set(null); }
}

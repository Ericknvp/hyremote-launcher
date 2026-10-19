package com.hyremote.ctrl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * The status icons of the launcher, drawn by hand.
 *
 * The projector has no font with usable WiFi / Bluetooth glyphs, and a
 * system-font emoji would break the look, so the two marks are painted with
 * plain canvas paths: a fan of arcs for WiFi and the Bluetooth rune.
 *
 * They are not decoration: both are tappable, and each one opens the screen
 * where that radio is actually configured.
 */
public class StatusIconView extends View {

    public static final int ICON_WIFI = 0;
    public static final int ICON_BLUETOOTH = 1;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();

    private int icon = ICON_WIFI;
    private boolean active = false;
    private int colorActive = 0xFF4C8DFF;
    private int colorInactive = 0xFF5E6A78;

    public StatusIconView(Context context) {
        super(context);
        init();
    }

    public StatusIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setIcon(int which) {
        this.icon = which;
        invalidate();
    }

    public void setActive(boolean active) {
        if (this.active == active) return;
        this.active = active;
        invalidate();
    }

    public void setColors(int activeColor, int inactiveColor) {
        this.colorActive = activeColor;
        this.colorInactive = inactiveColor;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Draw the mark inside a centred square that is a fraction of the
        // button: a full-bleed glyph next to a rounded background reads as
        // clutter at this size.
        float size = Math.min(w, h) * 0.60f;
        float left = (w - size) / 2f;
        float top = (h - size) / 2f;

        paint.setColor(active ? colorActive : colorInactive);
        paint.setStrokeWidth(Math.max(1.4f, size * 0.115f));

        if (icon == ICON_WIFI) drawWifi(canvas, left, top, size);
        else drawBluetooth(canvas, left, top, size);
    }

    /** Two arcs over the access point dot: the classic WiFi mark. */
    private void drawWifi(Canvas canvas, float left, float top, float size) {
        float cx = left + size / 2f;
        float cy = top + size * 0.86f;

        for (int i = 0; i < 2; i++) {
            float r = size * (0.48f - i * 0.22f);
            oval.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawArc(oval, 215f, 110f, false, paint);
        }

        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, size * 0.075f, paint);
        paint.setStyle(Paint.Style.STROKE);
    }

    /** The Bluetooth rune: a spine with two triangles crossing it. */
    private void drawBluetooth(Canvas canvas, float left, float top, float size) {
        float cx = left + size / 2f;
        float y0 = top + size * 0.06f;
        float y1 = top + size * 0.94f;
        float ym = (y0 + y1) / 2f;
        float dx = size * 0.29f;
        float dy = (y1 - y0) * 0.22f;

        // Spine
        canvas.drawLine(cx, y0, cx, y1, paint);

        // Upper right and lower left, as one zigzag
        path.reset();
        path.moveTo(cx, y0);
        path.lineTo(cx + dx, y0 + dy);
        path.lineTo(cx, ym);
        path.lineTo(cx - dx, ym + dy);
        path.lineTo(cx, y1);
        canvas.drawPath(path, paint);

        // The other two strokes
        path.reset();
        path.moveTo(cx, y0);
        path.lineTo(cx - dx, y0 + dy);
        path.lineTo(cx, ym);
        canvas.drawPath(path, paint);

        path.reset();
        path.moveTo(cx, ym);
        path.lineTo(cx + dx, ym + dy);
        path.lineTo(cx, y1);
        canvas.drawPath(path, paint);
    }
}

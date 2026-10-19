package com.hyremote.ctrl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * The icon set of the launcher, drawn by hand.
 *
 * The projector has no font with usable glyphs for these, and a system emoji
 * would break the look. Everything is painted with plain canvas paths so the
 * whole set shares the same weight and the same accent colour.
 */
public class GlyphView extends View {

    public static final int GEAR = 0;
    public static final int FOLDER = 1;
    public static final int SCREEN = 2;
    public static final int PROJECT = 3;
    public static final int BLUETOOTH = 4;
    public static final int HOME = 5;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private int glyph = GEAR;
    private int color = 0xFF4C8DFF;

    public GlyphView(Context context) {
        super(context);
        init();
    }

    public GlyphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setGlyph(int which) {
        this.glyph = which;
        invalidate();
    }

    public void setColor(int color) {
        this.color = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float s = Math.min(w, h);
        float ox = (w - s) / 2f;
        float oy = (h - s) / 2f;

        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(s * 0.085f);

        switch (glyph) {
            case FOLDER:
                drawFolder(canvas, ox, oy, s);
                break;
            case SCREEN:
                drawScreen(canvas, ox, oy, s);
                break;
            case PROJECT:
                drawProject(canvas, ox, oy, s);
                break;
            case BLUETOOTH:
                drawBluetooth(canvas, ox, oy, s);
                break;
            case HOME:
                drawHome(canvas, ox, oy, s);
                break;
            default:
                drawGear(canvas, ox, oy, s);
                break;
        }
    }

    /** A gear: a ring with eight teeth. */
    private void drawGear(Canvas canvas, float ox, float oy, float s) {
        float cx = ox + s / 2f;
        float cy = oy + s / 2f;
        float r = s * 0.30f;
        float tooth = s * 0.10f;

        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8.0 - Math.PI / 2.0;
            float tx = cx + (float) Math.cos(a) * (r + tooth * 0.85f);
            float ty = cy + (float) Math.sin(a) * (r + tooth * 0.85f);
            rect.set(tx - tooth / 2f, ty - tooth / 2f, tx + tooth / 2f, ty + tooth / 2f);
            canvas.drawRoundRect(rect, tooth / 3f, tooth / 3f, paint);
        }

        canvas.drawCircle(cx, cy, r, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, s * 0.10f, paint);
        paint.setStyle(Paint.Style.STROKE);
    }

    /** A folder. */
    private void drawFolder(Canvas canvas, float ox, float oy, float s) {
        path.reset();
        path.moveTo(ox + s * 0.14f, oy + s * 0.76f);
        path.lineTo(ox + s * 0.14f, oy + s * 0.28f);
        path.lineTo(ox + s * 0.40f, oy + s * 0.28f);
        path.lineTo(ox + s * 0.50f, oy + s * 0.40f);
        path.lineTo(ox + s * 0.86f, oy + s * 0.40f);
        path.lineTo(ox + s * 0.86f, oy + s * 0.76f);
        path.close();
        canvas.drawPath(path, paint);
    }

    /** A screen with a stand: the HDMI input. */
    private void drawScreen(Canvas canvas, float ox, float oy, float s) {
        rect.set(ox + s * 0.12f, oy + s * 0.20f, ox + s * 0.88f, oy + s * 0.64f);
        canvas.drawRoundRect(rect, s * 0.06f, s * 0.06f, paint);
        canvas.drawLine(ox + s * 0.50f, oy + s * 0.64f, ox + s * 0.50f, oy + s * 0.78f, paint);
        canvas.drawLine(ox + s * 0.32f, oy + s * 0.78f, ox + s * 0.68f, oy + s * 0.78f, paint);
    }

    /**
     * Framing brackets: the picture being squared up on the wall, which is
     * what the projection settings do.
     */
    private void drawProject(Canvas canvas, float ox, float oy, float s) {
        float len = s * 0.26f;
        float l = ox + s * 0.16f;
        float r = ox + s * 0.84f;
        float t = oy + s * 0.16f;
        float b = oy + s * 0.84f;

        // top left
        canvas.drawLine(l, t, l + len, t, paint);
        canvas.drawLine(l, t, l, t + len, paint);
        // top right
        canvas.drawLine(r, t, r - len, t, paint);
        canvas.drawLine(r, t, r, t + len, paint);
        // bottom left
        canvas.drawLine(l, b, l + len, b, paint);
        canvas.drawLine(l, b, l, b - len, paint);
        // bottom right
        canvas.drawLine(r, b, r - len, b, paint);
        canvas.drawLine(r, b, r, b - len, paint);
    }

    /** The Bluetooth rune. */
    private void drawBluetooth(Canvas canvas, float ox, float oy, float s) {
        float cx = ox + s * 0.5f;
        float top = oy + s * 0.14f;
        float bottom = oy + s * 0.86f;
        float mid = (top + bottom) / 2f;
        float dx = s * 0.20f;
        float dy = (bottom - top) * 0.25f;

        canvas.drawLine(cx, top, cx, bottom, paint);

        path.reset();
        path.moveTo(cx, top);
        path.lineTo(cx + dx, top + dy);
        path.lineTo(cx, mid);
        path.lineTo(cx - dx, mid + dy);
        path.lineTo(cx, bottom);
        canvas.drawPath(path, paint);

        path.reset();
        path.moveTo(cx, top);
        path.lineTo(cx - dx, top + dy);
        path.lineTo(cx, mid);
        canvas.drawPath(path, paint);

        path.reset();
        path.moveTo(cx, mid);
        path.lineTo(cx + dx, mid + dy);
        path.lineTo(cx, bottom);
        canvas.drawPath(path, paint);
    }

    /** A house: the home screen. */
    private void drawHome(Canvas canvas, float ox, float oy, float s) {
        path.reset();
        path.moveTo(ox + s * 0.50f, oy + s * 0.16f);
        path.lineTo(ox + s * 0.88f, oy + s * 0.50f);
        path.lineTo(ox + s * 0.12f, oy + s * 0.50f);
        path.close();
        canvas.drawPath(path, paint);

        rect.set(ox + s * 0.24f, oy + s * 0.50f, ox + s * 0.76f, oy + s * 0.84f);
        canvas.drawRoundRect(rect, s * 0.05f, s * 0.05f, paint);
    }
}
